/*
 * SceneView website analytics — the ONE GA4 loader for every static page.
 *
 * GA4 Property: SceneView Website | Stream ID: 14357002837 | Measurement ID: G-HX1JWGSMTH
 *
 * Why a shared file instead of a copy-pasted inline snippet: the tag used to live inline
 * in `index.html` only, so 15 of the 16 static pages — playground, showcase, docs, web,
 * privacy, the /preview/ viewer, the /go/ hub — reported nothing at all. One file means
 * one measurement id, one event vocabulary, and no page can drift out of the funnel.
 *
 * Load it SYNCHRONOUSLY (`<script src="/assets/analytics.js"></script>`, no `defer`,
 * no `async`) from every page's `<head>`. The `/go/*` redirect interstitials call
 * `gtag()` from their own inline script right after this one, so `gtag` must already be
 * defined when their script runs. The gtag *library* is still fetched asynchronously —
 * only the tiny dataLayer shim is synchronous.
 *
 * CSP: every static page ships `script-src 'self' 'unsafe-inline' …
 * https://www.googletagmanager.com`, so both this 'self' file and the googletagmanager
 * library it injects are allowed. `connect-src` already lists google-analytics.com.
 *
 * This file instruments the WEBSITE only. The SceneView SDK itself ships no telemetry
 * and the demo apps' published privacy policy promises none — never import analytics
 * into a library module or a sample app.
 */
(function () {
  'use strict';

  var MEASUREMENT_ID = 'G-HX1JWGSMTH';

  // Honour the browser's opt-out signals BEFORE anything is fetched — privacy.html
  // promises it, so it has to be true in code: Do Not Track (`navigator.doNotTrack`,
  // legacy `window.doNotTrack` / `navigator.msDoNotTrack`) and Global Privacy Control.
  // When opted out, `gtag` stays a stub that still runs `event_callback` so the /go/*
  // interstitials redirect immediately instead of waiting for their timeout.
  var optedOut =
    navigator.doNotTrack === '1' || window.doNotTrack === '1' ||
    navigator.msDoNotTrack === '1' || navigator.globalPrivacyControl === true;
  if (optedOut) {
    window.gtag = function () {
      var params = arguments[2];
      if (params && typeof params.event_callback === 'function') params.event_callback();
    };
    return;
  }

  window.dataLayer = window.dataLayer || [];
  function gtag() { window.dataLayer.push(arguments); }
  window.gtag = gtag;

  // Fetch the tag library. `async` keeps it off the critical path; queued dataLayer
  // calls are replayed once it lands.
  var tag = document.createElement('script');
  tag.async = true;
  tag.src = 'https://www.googletagmanager.com/gtag/js?id=' + MEASUREMENT_ID;
  (document.head || document.documentElement).appendChild(tag);

  // Coarse content grouping so the funnel can be read per surface in GA4 without
  // hand-maintaining a page list.
  function contentGroup() {
    var path = window.location.pathname;
    if (path.indexOf('/go/') === 0) return 'go';
    if (path.indexOf('/preview/') === 0 ||
        path.indexOf('/embed/') === 0 ||
        path.indexOf('/open/') === 0 ||
        path.indexOf('/rerun/') === 0) return 'viewer';
    return 'website';
  }

  gtag('js', new Date());
  gtag('config', MEASUREMENT_ID, {
    page_title: document.title,
    content_group: contentGroup(),
    // First-party measurement only — no Google Signals, no ad personalisation. This is
    // what lets privacy.html say "no advertising features are enabled".
    allow_google_signals: false,
    allow_ad_personalization_signals: false
  });

  function onReady(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  function each(selector, fn) {
    var nodes = document.querySelectorAll(selector);
    Array.prototype.forEach.call(nodes, fn);
  }

  onReady(function () {
    // Tab switches on the landing page / playground.
    each('[data-tab]', function (tab) {
      tab.addEventListener('click', function () {
        gtag('event', 'tab_click', { tab_name: this.dataset.tab });
      });
    });

    // Adoption CTAs: repo, Claude deep link, Discord, npm.
    each('a[href*="github.com/sceneview"], a[href*="claude://"], a[href*="discord"], a[href*="npmjs"]',
      function (link) {
        link.addEventListener('click', function () {
          gtag('event', 'cta_click', {
            link_url: this.href,
            link_text: (this.textContent || '').trim()
          });
        });
      });

    // Revenue funnel. `github.com/sponsors/sceneview` does NOT match the
    // `github.com/sceneview` selector above, so the sponsor CTA — the only live
    // revenue channel — was the one link on the site with zero attribution. These
    // selectors are disjoint from the `cta_click` ones: no double counting.
    each('a[href*="github.com/sponsors"], a[href*="polar.sh"]', function (link) {
      link.addEventListener('click', function () {
        gtag('event', 'outbound_click', {
          link_url: this.href,
          link_text: (this.textContent || '').trim(),
          link_domain: this.hostname,
          transport_type: 'beacon'
        });
      });
    });

    // Install-command copies.
    each('.code-block', function (block) {
      block.addEventListener('click', function () {
        gtag('event', 'code_copy', {
          code_snippet: (this.textContent || '').substring(0, 100)
        });
      });
    });
  });
})();
