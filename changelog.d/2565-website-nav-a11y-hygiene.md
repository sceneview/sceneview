<!-- category: Fixed -->
- **Website**: "Pricing" nav item now appears on every page (was only on the homepage), same position and markup, desktop and mobile menus (#2565).
- **Website**: added `:focus-visible` styles — keyboard users get a visible, token-themed focus ring on links, buttons and form controls in both themes (WCAG 2.4.7) (#2566).
- **Website**: hygiene — routine `SceneView:` info logs gated behind `window.SCENEVIEW_DEBUG`, dead CSS grids removed, the permanently-hidden duplicate `#hamburger` button removed from all 9 pages, scroll-reveal consolidated into `script.js` (single implementation, now honoring `prefers-reduced-motion` everywhere) (#2568).
