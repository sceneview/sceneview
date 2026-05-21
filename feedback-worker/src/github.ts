import type { Env } from "./env.js";

const GH_API = "https://api.github.com";
const UA = "sceneview-feedback-worker";

/** Import a PKCS#8 PEM RSA private key for RS256 signing. */
async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (ch) => ch.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

function base64Url(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlString(s: string): string {
  return base64Url(new TextEncoder().encode(s));
}

/** Build a short-lived GitHub App JWT (RS256). */
async function appJwt(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlString(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  // iat backdated 60s for clock skew; exp 9 min (GitHub allows up to 10).
  const payload = base64UrlString(
    JSON.stringify({ iat: now - 60, exp: now + 540, iss: env.GITHUB_APP_ID }),
  );
  const signingInput = `${header}.${payload}`;
  const key = await importPrivateKey(env.GITHUB_PRIVATE_KEY);
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64Url(new Uint8Array(sig))}`;
}

/** Exchange the App JWT for an installation access token. */
async function installationToken(env: Env): Promise<string> {
  const jwt = await appJwt(env);
  const res = await fetch(
    `${GH_API}/app/installations/${env.GITHUB_INSTALLATION_ID}/access_tokens`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${jwt}`,
        Accept: "application/vnd.github+json",
        "User-Agent": UA,
      },
    },
  );
  if (!res.ok) {
    // Status only — the response body can echo request detail / secrets.
    throw new Error(`installation token request failed: ${res.status}`);
  }
  const json = (await res.json()) as { token: string };
  return json.token;
}

/** Create the `user-feedback` label if missing. Best-effort — never throws. */
async function ensureLabel(env: Env, token: string): Promise<void> {
  try {
    const headers = {
      Authorization: `token ${token}`,
      Accept: "application/vnd.github+json",
      "User-Agent": UA,
    };
    const existing = await fetch(
      `${GH_API}/repos/${env.GITHUB_REPO}/labels/user-feedback`,
      { headers },
    );
    if (existing.ok) return;
    await fetch(`${GH_API}/repos/${env.GITHUB_REPO}/labels`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({
        name: "user-feedback",
        color: "0e8a16",
        description: "Submitted via the in-app feedback reporter",
      }),
    });
  } catch {
    // Non-critical: the issue is still created, just without the label
    // pre-existing. GitHub creates unknown labels referenced on an issue.
  }
}

export interface CreatedIssue {
  number: number;
  url: string;
}

/** Create a GitHub issue in the target repo via the App installation token. */
export async function createIssue(
  env: Env,
  issue: { title: string; body: string; labels: string[] },
): Promise<CreatedIssue> {
  const token = await installationToken(env);
  await ensureLabel(env, token);
  const res = await fetch(`${GH_API}/repos/${env.GITHUB_REPO}/issues`, {
    method: "POST",
    headers: {
      Authorization: `token ${token}`,
      Accept: "application/vnd.github+json",
      "User-Agent": UA,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(issue),
  });
  if (!res.ok) {
    // Status only — never interpolate the response body into the error.
    throw new Error(`create issue failed: ${res.status}`);
  }
  const json = (await res.json()) as { number: number; html_url: string };
  return { number: json.number, url: json.html_url };
}
