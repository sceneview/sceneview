/*
 * SceneView website — the support prompt.
 *
 * A small, dismissable card at the bottom of the page that asks regular readers to fund
 * the project, with amount buttons that open the Open Collective donation flow already
 * filled in (`/donate?amount=20&interval=month`). Open Collective is the destination,
 * GitHub Sponsors the secondary link, and there is no third one (README, companies.html
 * and index.html#fund say the same).
 *
 * It must stay rare, or it turns into the nag people block:
 *   - never on the first screen of a first visit: it waits for a sign of interest, a
 *     second page view, or 40 s spent reading the current page with the tab visible;
 *   - at most once every 30 days per browser, never again after "Don't ask again", and
 *     not for a year after the visitor clicked through to donate;
 *   - when storage is unavailable (private mode, blocked site data) it never shows:
 *     without memory it could not keep the promises above;
 *   - never a modal: no scrim, no focus grab, no scroll lock. On phones it is a card in
 *     the thumb zone, above the safe area.
 *
 * `?support=preview` forces the card on any page that loads this file, without touching
 * the visitor's stored state, for QA and review.
 *
 * Load with `<script src="/assets/support.js" defer></script>` on reading pages only:
 * not on sponsors.html or companies.html (they already are the ask), nor on the tools
 * (playground, viewer) where a bottom card would sit on the controls.
 */
(function () {
  'use strict';

  var OC = 'https://opencollective.com/sceneview/donate';
  var GH = 'https://github.com/sponsors/sceneview';
  var KEY = 'sv-support-v1';
  var DAY = 864e5;
  var SNOOZE = 30 * DAY;      // between two showings
  var AFTER_GIFT = 365 * DAY; // after a click through to Open Collective
  var READ_MS = 40e3;         // visible reading time that counts as interest on a first page
  var SETTLE_MS = 8e3;        // on a second page, let the visitor land first
  var AMOUNTS = [5, 20, 50];

  var preview = /[?&]support=preview\b/.test(location.search);

  function load() {
    try {
      var raw = localStorage.getItem(KEY);
      var s = raw ? JSON.parse(raw) : {};
      return (s && typeof s === 'object') ? s : {};
    } catch (e) { return null; }
  }
  function save(s) {
    if (preview) return true;
    try { localStorage.setItem(KEY, JSON.stringify(s)); return true; } catch (e) { return false; }
  }
  function track(name, params) {
    try { if (typeof window.gtag === 'function') window.gtag('event', name, params || {}); } catch (e) { /* analytics never breaks the page */ }
  }

  var state = load();
  if (!preview) {
    if (!state) return;                          // no storage, no promises: stay silent
    state.pv = (state.pv | 0) + 1;
    if (!save(state)) return;
    var now = Date.now();
    if (state.off) return;
    if (state.gave && now - state.gave < AFTER_GIFT) return;
    if (state.shown && now - state.shown < SNOOZE) return;
  }

  // Visible time on this page: counts only while the tab is in front.
  var visibleMs = 0, since = document.visibilityState === 'visible' ? Date.now() : 0, timer = 0;
  function tick() {
    if (since) { visibleMs += Date.now() - since; since = Date.now(); }
  }
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') { since = Date.now(); schedule(); }
    else { tick(); since = 0; clearTimeout(timer); }
  });
  function schedule() {
    clearTimeout(timer);
    if (!since) return;
    var target = preview ? 600 : (state.pv >= 2 ? SETTLE_MS : READ_MS);
    tick();
    var left = target - visibleMs;
    if (left <= 0) return show(preview ? 'preview' : (state.pv >= 2 ? 'pages' : 'reading'));
    timer = setTimeout(schedule, left);
  }

  var card, interval = 'oneTime';

  function ocUrl(amount) {
    return OC + '?interval=' + interval + (amount ? '&amount=' + amount : '');
  }
  function refreshLinks() {
    Array.prototype.forEach.call(card.querySelectorAll('[data-amount]'), function (a) {
      a.href = ocUrl(+a.getAttribute('data-amount') || 0);
    });
    Array.prototype.forEach.call(card.querySelectorAll('[data-interval]'), function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-interval') === interval));
    });
  }

  function el(tag, attrs, html) {
    var n = document.createElement(tag);
    for (var k in attrs) n.setAttribute(k, attrs[k]);
    if (html != null) n.innerHTML = html;
    return n;
  }

  var X = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="m6 6 12 12M18 6 6 18"/></svg>';
  var HEART = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="M12 20s-7.5-4.4-7.5-10.1A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 7.5 2.6C19.5 15.6 12 20 12 20Z"/></svg>';

  function build() {
    card = el('aside', { 'class': 'svs', 'aria-labelledby': 'svs-title', 'data-state': 'enter' });
    var amounts = AMOUNTS.map(function (a) {
      return '<a class="svs__amt" data-amount="' + a + '" target="_blank" rel="noopener">$' + a + '</a>';
    }).join('') + '<a class="svs__amt svs__amt--other" data-amount="" target="_blank" rel="noopener">Other</a>';
    card.innerHTML =
      '<div class="svs__body">' +
        '<div class="svs__head">' +
          '<div>' +
            '<p class="svs__eyebrow"><span class="svs__heart">' + HEART + '</span>Open source</p>' +
            '<h2 class="svs__title" id="svs-title">Help keep SceneView going</h2>' +
          '</div>' +
          '<button class="svs__close" type="button" aria-label="Close">' + X + '</button>' +
        '</div>' +
        '<p class="svs__text">Free, built by volunteers, funded by the people who use it. If it saves you time, chip in.</p>' +
        '<div class="svs__freq" role="group" aria-label="How often">' +
          '<button type="button" data-interval="oneTime" aria-pressed="true">Once</button>' +
          '<button type="button" data-interval="month" aria-pressed="false">Monthly</button>' +
        '</div>' +
        '<div class="svs__amts">' + amounts + '</div>' +
        '<div class="svs__foot">' +
          '<button class="svs__never" type="button">Don’t ask again</button>' +
          '<a class="svs__alt" href="' + GH + '" target="_blank" rel="noopener">or GitHub Sponsors</a>' +
        '</div>' +
        '<p class="svs__note">Via Open Collective: every expense is public.</p>' +
      '</div>' +
      '<div class="svs__thanks" hidden><p class="svs__title">Thank you.</p><p class="svs__text">Open Collective opened in a new tab.</p></div>';

    refreshLinks();

    card.addEventListener('click', function (e) {
      var t = e.target.closest ? e.target.closest('button, a') : null;
      if (!t || !card.contains(t)) return;
      if (t.hasAttribute('data-interval')) {
        interval = t.getAttribute('data-interval');
        refreshLinks();
      } else if (t.classList.contains('svs__close')) {
        close(false);
      } else if (t.classList.contains('svs__never')) {
        close(true);
      } else if (t.hasAttribute('data-amount') || t.classList.contains('svs__alt')) {
        var isOc = t.hasAttribute('data-amount');
        track('support_prompt_click', {
          amount: isOc ? (+t.getAttribute('data-amount') || 'other') : 'github_sponsors',
          interval: isOc ? interval : 'n/a',
          link_url: t.href,
          link_domain: t.hostname,
          transport_type: 'beacon'
        });
        if (!preview && state) { state.gave = Date.now(); save(state); }
        card.querySelector('.svs__body').hidden = true;
        card.querySelector('.svs__thanks').hidden = false;
        setTimeout(function () { close(null); }, 4000);
      }
    });
    card.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') close(false);
    });
  }

  function close(permanent) {
    if (!card || card.getAttribute('data-state') === 'leave') return;
    if (permanent !== null) track('support_prompt_dismiss', { permanent: !!permanent });
    if (permanent && !preview && state) { state.off = 1; save(state); }
    card.setAttribute('data-state', 'leave');
    setTimeout(function () { if (card && card.parentNode) card.parentNode.removeChild(card); }, 400);
  }

  function show(trigger) {
    if (card) return;
    // The stylesheet is fetched only for the visitors who will see the card.
    var css = el('link', { rel: 'stylesheet', href: '/assets/support.css' });
    css.onload = function () {
      build();
      document.body.appendChild(card);
      // Commit the start style with a forced reflow, then transition. Not rAF: a
      // background tab that comes back to the front would show a half-faded card.
      void card.offsetWidth;
      card.setAttribute('data-state', 'in');
      if (!preview) { state.shown = Date.now(); save(state); }
      track('support_prompt_view', { trigger: trigger, page_views: preview ? 0 : state.pv });
    };
    document.head.appendChild(css);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', schedule);
  else schedule();
})();
