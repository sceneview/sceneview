/**
 * Symbol-existence lookups over the generated public-API index (#2760).
 *
 * The index (`src/generated/symbols.ts`) is parsed from the committed
 * binary-compatibility `.api` dumps at build time — see
 * `scripts/generate-symbols.js` for the full provenance chain. Everything
 * here answers one question for the validator: "does this identifier exist
 * in the real Android/KMP SceneView API?", with edit-distance suggestions
 * when it doesn't.
 *
 * Scope, stated honestly: the dumps cover `sceneview`, `arsceneview` and
 * `sceneview-core` (Android/KMP). SceneViewSwift and sceneview-web have no
 * committed dump, so Swift/Web snippets are NOT symbol-checked.
 */

import { SYMBOLS } from "./generated/symbols.js";

export const SCENEVIEW_NAMESPACE = "io.github.sceneview";

// ─── Lazily-built lookup sets ────────────────────────────────────────────────

let typeNamesCache: Set<string> | null = null;
let classBySimpleNameCache: Map<string, string[]> | null = null;

/**
 * Every PascalCase identifier a Kotlin user can legitimately reference as a
 * type or declarative factory:
 *  - class simple names (`ModelNode`, `WallPlacement`, …),
 *  - PascalCase top-level functions — the composables (`SceneView`,
 *    `ARSceneView`, `WallPlacementScene`, `DynamicSkyNode`, …),
 *  - PascalCase members of the `*Scope` classes — the declarative node
 *    factories (`SceneScope.ModelNode`, `ARSceneScope.…`).
 */
export function knownTypeNames(): Set<string> {
  if (typeNamesCache) return typeNamesCache;
  const names = new Set<string>();
  for (const fqcn of Object.keys(SYMBOLS.classes)) {
    const simple = fqcn.slice(fqcn.lastIndexOf(".") + 1);
    if (/^[A-Z]/.test(simple)) names.add(simple);
  }
  for (const name of Object.keys(SYMBOLS.topLevel)) {
    if (/^[A-Z]/.test(name)) names.add(name);
  }
  for (const [fqcn, entry] of Object.entries(SYMBOLS.classes)) {
    if (!fqcn.endsWith("Scope")) continue;
    for (const member of entry.members) {
      if (/^[A-Z]/.test(member)) names.add(member);
    }
  }
  typeNamesCache = names;
  return names;
}

/** Class simple name → the FQCNs that declare it (usually exactly one). */
function classBySimpleName(): Map<string, string[]> {
  if (classBySimpleNameCache) return classBySimpleNameCache;
  const map = new Map<string, string[]>();
  for (const fqcn of Object.keys(SYMBOLS.classes)) {
    const simple = fqcn.slice(fqcn.lastIndexOf(".") + 1);
    const list = map.get(simple);
    if (list) list.push(fqcn);
    else map.set(simple, [fqcn]);
  }
  classBySimpleNameCache = map;
  return map;
}

export function isKnownTypeName(name: string): boolean {
  return knownTypeNames().has(name);
}

/** Top-level function (any case) — composables and extension helpers. */
export function isKnownTopLevelFunction(name: string): boolean {
  return name in SYMBOLS.topLevel;
}

/** Every real `remember*` composable helper — suggestion pool for typos. */
export function rememberHelperNames(): string[] {
  return Object.keys(SYMBOLS.topLevel).filter((name) => name.startsWith("remember"));
}

/**
 * Resolve an `io.github.sceneview.*` import target.
 *
 * Returns "class" for an exact class FQCN, "package" for a package that
 * exists (covers `import io.github.sceneview.node.*`), "member" for a
 * `Class.member` or package-level function import, and null when nothing
 * in the index matches. Callers must only pass SceneView-namespace paths —
 * other namespaces are out of index scope.
 */
export function resolveImport(path: string): "class" | "package" | "member" | null {
  if (path in SYMBOLS.classes) return "class";
  if (SYMBOLS.packages.includes(path)) return "package";
  const lastDot = path.lastIndexOf(".");
  if (lastDot === -1) return null;
  const parent = path.slice(0, lastDot);
  const leaf = path.slice(lastDot + 1);
  // `import io.github.sceneview.ar.rememberARCameraStream` — a top-level
  // function imported from a package.
  if (SYMBOLS.packages.includes(parent) && leaf in SYMBOLS.topLevel) {
    return "member";
  }
  // `import io.github.sceneview.node.ModelNode.Companion` or a nested
  // class member — accept when the parent class exists and declares it.
  const parentClass = SYMBOLS.classes[parent];
  if (parentClass?.members.includes(leaf)) return "member";
  return null;
}

/** Union of member/property names declared by classes with this simple name. */
export function membersOfClass(simpleName: string): Set<string> | null {
  const fqcns = classBySimpleName().get(simpleName);
  if (!fqcns) return null;
  const members = new Set<string>();
  for (const fqcn of fqcns) {
    for (const m of SYMBOLS.classes[fqcn].members) members.add(m);
  }
  return members;
}

// ─── Edit-distance suggestions ───────────────────────────────────────────────

/** Classic Levenshtein with a band cutoff — returns Infinity past `max`. */
export function editDistance(a: string, b: string, max: number): number {
  if (Math.abs(a.length - b.length) > max) return Number.POSITIVE_INFINITY;
  const la = a.length;
  const lb = b.length;
  let prev = Array.from({ length: lb + 1 }, (_, i) => i);
  let curr = new Array<number>(lb + 1);
  for (let i = 1; i <= la; i++) {
    curr[0] = i;
    let rowMin = i;
    for (let j = 1; j <= lb; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
      if (curr[j] < rowMin) rowMin = curr[j];
    }
    if (rowMin > max) return Number.POSITIVE_INFINITY;
    [prev, curr] = [curr, prev];
  }
  return prev[lb] <= max ? prev[lb] : Number.POSITIVE_INFINITY;
}

/** camelCase identifier → lowercased token set (`loadModelAsync` → load,model,async). */
function camelTokens(name: string): Set<string> {
  return new Set(
    name
      .split(/(?=[A-Z])/)
      .map((t) => t.toLowerCase())
      .filter((t) => t.length > 0)
  );
}

/** Jaccard similarity of the camelCase token sets of two identifiers. */
export function tokenSimilarity(a: string, b: string): number {
  const ta = camelTokens(a);
  const tb = camelTokens(b);
  let inter = 0;
  for (const t of ta) if (tb.has(t)) inter++;
  const union = ta.size + tb.size - inter;
  return union === 0 ? 0 : inter / union;
}

/**
 * Closest candidates to `name`, nearest first, by a hybrid metric:
 *  - Levenshtein within a length-scaled band (2 for short names, 3 for long)
 *    catches character typos (`ModellNode` → `ModelNode`);
 *  - camelCase token overlap (Jaccard ≥ 0.5) catches structural
 *    hallucinations that raw edit distance can't reach —
 *    `createModelInstanceAsync` → `loadModelInstanceAsync` is distance 5,
 *    but shares 3 of 5 camelCase tokens.
 * Token matches are scored `10 × (1 − similarity)` so an exact-ish edit
 * match always outranks a merely structural one.
 */
export function suggestClosest(name: string, candidates: Iterable<string>, limit = 3): string[] {
  const max = name.length < 12 ? 2 : 3;
  const lower = name.toLowerCase();
  const scored: Array<[string, number]> = [];
  for (const candidate of candidates) {
    const d = editDistance(lower, candidate.toLowerCase(), max);
    if (d !== Number.POSITIVE_INFINITY) {
      scored.push([candidate, d]);
      continue;
    }
    const similarity = tokenSimilarity(name, candidate);
    if (similarity >= 0.5) scored.push([candidate, 10 * (1 - similarity)]);
  }
  scored.sort((x, y) => x[1] - y[1] || x[0].localeCompare(y[0]));
  return scored.slice(0, limit).map(([candidate]) => candidate);
}

/** Human-readable " Did you mean …?" suffix, or "" when nothing is close. */
export function didYouMean(name: string, candidates: Iterable<string>): string {
  const close = suggestClosest(name, candidates);
  if (close.length === 0) return "";
  return ` Did you mean ${close.map((c) => `\`${c}\``).join(", ")}?`;
}
