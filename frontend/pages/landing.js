import { API } from '../core/api.js';
import { Auth } from '../core/auth.js';

let activeScrollFrame = null;

function scrollToSection(target) {
  if (activeScrollFrame !== null) cancelAnimationFrame(activeScrollFrame);
  const start = window.scrollY;
  const marginTop = Number.parseFloat(getComputedStyle(target).scrollMarginTop) || 0;
  const destination = Math.max(0, start + target.getBoundingClientRect().top - marginTop);
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    window.scrollTo(0, destination);
    return;
  }
  const duration = Math.min(900, Math.max(360, Math.abs(destination - start) * 0.45));
  const startedAt = performance.now();
  const animate = (timestamp) => {
    const progress = Math.min((timestamp - startedAt) / duration, 1);
    const easedProgress = 1 - Math.pow(1 - progress, 3);
    window.scrollTo(0, start + (destination - start) * easedProgress);
    activeScrollFrame = progress < 1 ? requestAnimationFrame(animate) : null;
  };
  activeScrollFrame = requestAnimationFrame(animate);
}

document.querySelectorAll('a[href^="#"]').forEach((link) => {
  link.addEventListener('click', (event) => {
    const target = document.getElementById(link.hash.slice(1));
    if (!target || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (window.location.hash !== link.hash) window.history.pushState(null, '', link.hash);
    scrollToSection(target);
    link.closest('details')?.removeAttribute('open');
  });
});

if (API.getAuthToken()) {
  API.request('/auth/me').then((user) => {
    const destination = Auth.homeForRole(user.role);
    document.querySelectorAll('[data-console-link]').forEach((link) => {
      link.href = destination;
      const label = link.querySelector('span') || link;
      label.textContent = 'Go to console';
      link.setAttribute('aria-label', 'Go to console');
    });
  }).catch(() => {});
}
