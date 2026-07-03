// ===== DARK MODE — follows localStorage, then system theme =====
(function initTheme() {
  var saved;
  try { saved = localStorage.getItem('sceneview-theme'); } catch(e) {}
  if (saved === 'dark' || saved === 'light') {
    document.documentElement.setAttribute('data-theme', saved);
    return;
  }
  var mq = window.matchMedia('(prefers-color-scheme: dark)');
  function applySystemTheme() {
    document.documentElement.setAttribute('data-theme', mq.matches ? 'dark' : 'light');
  }
  applySystemTheme();
  mq.addEventListener('change', applySystemTheme);
})();

var _themeToggle = document.getElementById('themeToggle');
if (_themeToggle) {
  _themeToggle.addEventListener('click', function () {
    var current = document.documentElement.getAttribute('data-theme');
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sceneview-theme', next); } catch(e) {}
  });
}

// ===== TAB SWITCHING =====
document.querySelectorAll('.tabs').forEach(function (tabGroup) {
  var buttons = tabGroup.querySelectorAll('.tabs__btn');
  var panels = tabGroup.querySelectorAll('.tabs__panel');

  buttons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      var target = btn.getAttribute('data-tab');

      buttons.forEach(function (b) { b.classList.remove('tabs__btn--active'); });
      panels.forEach(function (p) { p.classList.remove('tabs__panel--active'); });

      btn.classList.add('tabs__btn--active');
      var panel = tabGroup.querySelector('[data-panel="' + target + '"]');
      if (panel) panel.classList.add('tabs__panel--active');
    });
  });
});

// ===== MOBILE HAMBURGER =====
var hamburger = document.getElementById('hamburgerMobile') || document.getElementById('hamburger');
var navLinks = document.getElementById('navLinks');

if (hamburger && navLinks) {
  var overlay = document.createElement('div');
  overlay.className = 'nav-overlay';
  document.body.appendChild(overlay);

  function toggleMenu() {
    hamburger.classList.toggle('active');
    navLinks.classList.toggle('open');
    overlay.classList.toggle('active');
    document.body.style.overflow = navLinks.classList.contains('open') ? 'hidden' : '';
  }

  hamburger.addEventListener('click', toggleMenu);
  overlay.addEventListener('click', toggleMenu);

  navLinks.querySelectorAll('.nav__link').forEach(function (link) {
    link.addEventListener('click', function () {
      if (navLinks.classList.contains('open')) { toggleMenu(); }
    });
  });
}

// ===== SMOOTH SCROLL =====
document.querySelectorAll('a[href^="#"]').forEach(function (anchor) {
  anchor.addEventListener('click', function (e) {
    var target = document.querySelector(this.getAttribute('href'));
    if (target) {
      e.preventDefault();
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });
});

// ===== SCROLL REVEAL =====
(function() {
  var reveals = document.querySelectorAll('.reveal');
  if (!reveals.length) return;

  // Respect prefers-reduced-motion: reveal everything immediately (#2568).
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    reveals.forEach(function(el) { el.classList.add('revealed'); });
    return;
  }

  // Immediately reveal elements already in or above the viewport
  function revealVisible() {
    reveals.forEach(function(el) {
      var rect = el.getBoundingClientRect();
      if (rect.top < window.innerHeight + 100) {
        el.classList.add('revealed');
      }
    });
  }
  revealVisible();

  // Observer for elements entering viewport during scroll
  if ('IntersectionObserver' in window) {
    var observer = new IntersectionObserver(function(entries) {
      entries.forEach(function(entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('revealed');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.01, rootMargin: '0px 0px 200px 0px' });
    reveals.forEach(function(el) {
      if (!el.classList.contains('revealed')) {
        observer.observe(el);
      }
    });
  }

  // Scroll fallback — reveal any still-hidden elements on scroll
  var scrollTimer;
  window.addEventListener('scroll', function() {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(revealVisible, 50);
  }, { passive: true });

  // Safety net — reveal everything after 3s regardless
  setTimeout(function() {
    reveals.forEach(function(el) { el.classList.add('revealed'); });
  }, 3000);
})();

// ===== NAV SCROLL EFFECT =====
(function() {
  var nav = document.getElementById('nav');
  if (!nav) return;
  var scrolled = false;
  window.addEventListener('scroll', function() {
    var isScrolled = window.scrollY > 10;
    if (isScrolled !== scrolled) {
      scrolled = isScrolled;
      nav.style.boxShadow = scrolled ? 'var(--shadow-md)' : 'none';
    }
  }, { passive: true });
})();
