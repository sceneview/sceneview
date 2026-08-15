/** @jsxImportSource hono/jsx */

import type { FC } from "hono/jsx";
import { Layout } from "./layout.js";
import { renderToHtml } from "./render.js";
import type { PlanId } from "../billing/tiers.js";

/** Single tier card shown in the pricing grid. */
interface TierCardProps {
  name: string;
  price: string;
  period: string;
  description: string;
  features: readonly string[];
  /** When set, the card shows a self-serve Subscribe button. */
  plan?: { monthly: PlanId; yearly: PlanId };
  /** When set, shows a fallback "contact us" CTA. */
  contact?: { href: string; label: string };
  featured?: boolean;
}

const TierCard: FC<TierCardProps> = (props) => (
  <div
    class={`pricing-card${props.featured ? " pricing-card--featured" : ""}`}
  >
    <h3>{props.name}</h3>
    <div class="price">
      {props.price}
      <small> / {props.period}</small>
    </div>
    <p>{props.description}</p>
    <ul>
      {props.features.map((f) => (
        <li>{f}</li>
      ))}
    </ul>
    {props.plan ? (
      <form
        method="post"
        action="/billing/checkout"
        style="display:flex;flex-direction:column;gap:.5rem;"
      >
        <input type="hidden" name="plan" value={props.plan.monthly} />
        <button
          type="submit"
          class={`btn ${props.featured ? "btn-primary" : "btn-secondary"}`}
        >
          Subscribe monthly
        </button>
      </form>
    ) : props.contact ? (
      <a
        href={props.contact.href}
        class={`btn ${props.featured ? "btn-primary" : "btn-secondary"}`}
      >
        {props.contact.label}
      </a>
    ) : null}
    {props.plan ? (
      <form
        method="post"
        action="/billing/checkout"
        style="display:flex;flex-direction:column;gap:.5rem;margin-top:.25rem;"
      >
        <input type="hidden" name="plan" value={props.plan.yearly} />
        <button type="submit" class="btn btn-secondary">
          Subscribe yearly (save 2 months)
        </button>
      </form>
    ) : null}
  </div>
);

/** `/pricing` page. */
export const Pricing: FC = () => (
  <Layout
    title="Pricing"
    description="Simple, transparent pricing for SceneView MCP. Start with the free stdio package or upgrade to Pro at 19 EUR/month or Team at 49 EUR/month."
    active="pricing"
  >
    <section style="text-align:center;">
      <h1>Pricing</h1>
      <p class="lead" style="max-width:640px;margin:1rem auto 0;">
        Click Subscribe below to pay with a card. Your API key is shown
        on the confirmation page right after checkout — save it somewhere
        safe because it will only be displayed once.
      </p>
    </section>

    <section class="pricing-grid">
      <TierCard
        name="Free"
        price="0 EUR"
        period="forever"
        description="Every developer tool — setup, samples, validator, migration, docs."
        features={[
          "29 free tools",
          "Setup guides for every platform (Android, iOS, Web, Flutter, RN, Desktop, TV)",
          "Code samples + validator + migration tooling",
          "Full SceneView API reference + known issues",
          "Self-hosted stdio npm package",
        ]}
        contact={{
          href: "/docs#claude-desktop",
          label: "Install free — see docs",
        }}
      />
      <TierCard
        name="Pro"
        price="19 EUR"
        period="month"
        description="For developers building specialised vertical apps."
        features={[
          "Everything in Free",
          "5 vertical packages (Automotive, Gaming, Healthcare, Interior, Rerun — 35 specialised tools)",
          "3 generation helpers (3D preview, artifact, scene generation)",
          "Hosted HTTP endpoint, 50k calls / month",
          "Email support",
        ]}
        plan={{ monthly: "pro-monthly", yearly: "pro-yearly" }}
        featured
      />
      <TierCard
        name="Team"
        price="49 EUR"
        period="month"
        description="For organisations with multiple developers."
        features={[
          "Everything in Pro",
          "250k calls / month (5× the Pro quota)",
          "Per-seat API keys",
          "Priority support",
          "Custom invoicing",
        ]}
        plan={{ monthly: "team-monthly", yearly: "team-yearly" }}
      />
    </section>

    <section style="margin-top:3rem;">
      <h2>Annual plans</h2>
      <p>
        Save roughly two months on every tier by picking the yearly
        option above: Pro at 190 EUR / year and Team at 490 EUR / year.
      </p>
    </section>

    <section style="margin-top:2.5rem;">
      <h2>Frequently asked</h2>
      <h3>How do I receive my API key?</h3>
      <p>
        After paying you are redirected to a success page that shows
        your <code>sv_live_</code> key one time. Paste it into your
        Claude Desktop or Cursor config — we do not send it by email.
      </p>
      <h3>What happens when I hit my monthly quota?</h3>
      <p>
        Calls beyond the quota return a JSON-RPC <code>rate_limited</code>
        error without breaking the connection. Upgrade or wait until the
        next billing cycle; no auto-overage charges.
      </p>
      <h3>Can I self-host?</h3>
      <p>
        Yes. The free tier runs fully local with{" "}
        <code>npx -y sceneview-mcp</code> (31 tools, no signup, no
        network round-trip). After subscribing, set{" "}
        <code>SCENEVIEW_API_KEY</code> in your MCP client config — Pro
        tool calls (vertical packages, generation helpers) then route
        transparently through the hosted gateway.
      </p>
      <h3>Do you store my prompts?</h3>
      <p>
        No. The gateway logs per-call metadata (tool name, status,
        timestamp) for billing and rate limiting. Request bodies and
        responses are never persisted.
      </p>
      <h3>What about taxes?</h3>
      <p>
        SceneView MCP is operated by a French auto-entrepreneur under
        the <em>franchise en base de TVA</em> regime (CGI art. 293 B),
        so prices on this page are final — no VAT is collected or
        added at checkout regardless of your billing country. Stripe
        emails you a receipt automatically; if your accounting needs a
        formal invoice, email{" "}
        <a href="mailto:hello@sceneview.dev">hello@sceneview.dev</a>.
      </p>
      <h3>I lost my key. What now?</h3>
      <p>
        Email <a href="mailto:hello@sceneview.dev">hello@sceneview.dev</a>
        {" "}with the address you used at checkout and we will issue a
        replacement key.
      </p>
    </section>
  </Layout>
);

/** Top-level renderer used by the route handler. */
export function renderPricing(): Promise<string> {
  return renderToHtml(<Pricing />);
}
