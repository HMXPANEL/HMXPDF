/* HMXPDF website — shared interactions. No dependencies. */
(function () {
  'use strict';

  document.documentElement.classList.add('js');

  function ready(fn) {
    if (document.readyState !== 'loading') {
      fn();
    } else {
      document.addEventListener('DOMContentLoaded', fn);
    }
  }

  ready(function () {
    var header = document.getElementById('site-header');
    var toggle = document.getElementById('nav-toggle');
    var mobileNav = document.getElementById('mobile-nav');

    function closeNav() {
      if (!toggle || !mobileNav) return;
      toggle.setAttribute('aria-expanded', 'false');
      mobileNav.classList.add('hidden');
    }

    if (toggle && mobileNav) {
      toggle.addEventListener('click', function () {
        var isOpen = toggle.getAttribute('aria-expanded') === 'true';
        toggle.setAttribute('aria-expanded', isOpen ? 'false' : 'true');
        mobileNav.classList.toggle('hidden', isOpen);
      });

      mobileNav.querySelectorAll('a').forEach(function (link) {
        link.addEventListener('click', closeNav);
      });

      document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') {
          closeNav();
          toggle.focus();
        }
      });

      var desktop = window.matchMedia('(min-width: 1024px)');
      var onDesktop = function (event) {
        if (event.matches) closeNav();
      };
      if (desktop.addEventListener) {
        desktop.addEventListener('change', onDesktop);
      } else if (desktop.addListener) {
        desktop.addListener(onDesktop);
      }
    }

    if (header) {
      var onScroll = function () {
        header.classList.toggle('is-scrolled', window.scrollY > 8);
      };
      onScroll();
      window.addEventListener('scroll', onScroll, { passive: true });
    }

    var revealItems = document.querySelectorAll('.reveal');
    var reduceMotion =
      window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (revealItems.length && (reduceMotion || !('IntersectionObserver' in window))) {
      revealItems.forEach(function (item) {
        item.classList.add('is-visible');
      });
    } else if (revealItems.length) {
      var observer = new IntersectionObserver(
        function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) {
              entry.target.classList.add('is-visible');
              observer.unobserve(entry.target);
            }
          });
        },
        { rootMargin: '0px 0px -8% 0px', threshold: 0.06 }
      );

      revealItems.forEach(function (item) {
        observer.observe(item);
      });
    }

    document.querySelectorAll('[data-year]').forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });
  });
})();
