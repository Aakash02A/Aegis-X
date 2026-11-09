import { APP_ROUTES, INTEGRITY_ROUTES } from '../core/constants.js';
import { Auth } from '../core/auth.js';
import { enhanceResponsiveTables } from './responsive-table.js';
import { mountPageHeader } from './page-header.js';

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function currentPageFile() {
  const integrityPage = document.body.dataset.integrityPage;
  if (!integrityPage) return location.pathname.split('/').pop();
  return integrityPage === 'overview' ? 'integrity.html' : `integrity-${integrityPage}.html`;
}

export function createSidebar({ activePage = currentPageFile(), className = '', workspaceStatus = false } = {}) {
  const user = Auth.getUser();
  const role = user?.role || '';
  const name = user?.name || user?.email || 'Account';
  const aside = element('aside', className || 'w-64 bg-paper-dim border-r border-ink/10 flex flex-col justify-between hidden md:flex shrink-0');
  aside.setAttribute('aria-label', 'Application sidebar');

  const top = element('div');
  const brandRow = element('div', 'min-h-[4.5rem] px-6 flex items-center gap-3 border-b border-ink/10');
  const logo = element('a', 'w-8 h-8 rounded-full bg-ink text-paper flex items-center justify-center font-serif text-sm font-bold', 'A');
  logo.href = 'index.html';
  logo.setAttribute('aria-label', 'Aegis-X home');
  const brand = element('div');
  const wordmark = element('span', 'font-serif text-base tracking-tight font-semibold text-ink block');
  wordmark.append(document.createTextNode('Aegis'));
  wordmark.append(element('span', 'text-rust italic', 'X'));
  const descriptor = element('span', 'text-[9px] tracking-widest uppercase font-semibold text-ink-soft', role.replaceAll('_', ' '));
  brand.append(wordmark, descriptor);
  brandRow.append(logo, brand);

  const nav = element('nav', 'p-4 space-y-1 font-sans');
  nav.setAttribute('aria-label', 'Workspace navigation');
  for (const route of APP_ROUTES) {
    if (!route.roles.includes(role)) continue;
    const link = element('a', 'flex items-center gap-3 px-3.5 py-2.5 rounded-full text-sm font-medium transition-all');
    link.href = route.page;
    link.classList.add(route.page === activePage
      ? 'bg-ink'
      : 'text-ink-soft', route.page === activePage ? 'text-paper' : 'hover:text-ink');
    if (route.page !== activePage) link.classList.add('hover:bg-ink/5');
    if (route.page === activePage) link.setAttribute('aria-current', 'page');
    const icon = element('i', `${route.icon} w-4 h-4`);
    icon.setAttribute('aria-hidden', 'true');
    link.append(icon, element('span', '', route.label));
    nav.append(link);
  }
  top.append(brandRow, nav);
  if (workspaceStatus) {
    const subnav = element('nav', 'px-4 pb-4 space-y-1 font-sans');
    subnav.setAttribute('aria-label', 'Payment Integrity pages');
    for (const route of INTEGRITY_ROUTES) {
      const link = element('a', 'flex items-center gap-3 px-3.5 py-2 rounded-full text-xs font-medium transition-all');
      link.href = route.page;
      link.classList.add(route.page === activePage ? 'bg-ink' : 'text-ink-soft', route.page === activePage ? 'text-paper' : 'hover:text-ink');
      if (route.page !== activePage) link.classList.add('hover:bg-ink/5');
      if (route.page === activePage) link.setAttribute('aria-current', 'page');
      const icon = element('i', `${route.icon} w-3.5 h-3.5`);
      icon.setAttribute('aria-hidden', 'true');
      link.append(icon, element('span', '', route.label));
      subnav.append(link);
    }
    top.append(subnav);
  }

  const footer = element('div', 'p-4 border-t border-ink/10');
  if (workspaceStatus) {
    footer.classList.add('space-y-2');
    const workspace = element('div', 'text-[11px] text-ink-soft', 'Workspace not loaded');
    workspace.id = 'integrity-workspace';
    const provider = element('div', 'text-[11px]');
    provider.id = 'integrity-provider';
    footer.append(workspace, provider);
  }
  const profile = element('div', 'p-3 rounded-sm bg-white border border-ink/10 flex items-center justify-between');
  const identity = element('div', 'flex items-center gap-2.5 overflow-hidden');
  const initials = [...name.trim()].find((character) => /[\p{L}\p{N}]/u.test(character))?.toLocaleUpperCase() || 'A';
  identity.append(element('div', 'w-8 h-8 rounded-full bg-ink/10 text-ink font-bold flex items-center justify-center text-xs shrink-0 font-serif', initials));
  const details = element('div', 'truncate');
  details.append(element('p', 'text-xs font-semibold text-ink truncate', name));
  details.append(element('p', 'text-[10px] text-ink-soft uppercase font-mono-data', role.replaceAll('_', ' ')));
  identity.append(details);
  const logout = element('button', 'text-ink-soft hover:text-rust p-1 transition-colors');
  logout.type = 'button';
  logout.title = 'Sign out';
  logout.setAttribute('aria-label', 'Sign out');
  logout.dataset.appLogout = '';
  const logoutIcon = element('i', 'fa-solid fa-right-from-bracket w-4 h-4');
  logoutIcon.setAttribute('aria-hidden', 'true');
  logout.append(logoutIcon);
  logout.addEventListener('click', () => Auth.logout());
  profile.append(identity, logout);
  footer.append(profile);
  aside.append(top, footer);
  return aside;
}

export function wireMobileNavigation(aside, header, host) {
  if (!aside || !header || !host || header.querySelector('[data-workspace-menu]')) return;
  header.classList.add('flex-wrap');
  for (const child of header.children) child.classList.add('min-w-0', 'flex-wrap');
  const menu = element('button', 'md:hidden shrink-0 aegis-btn-outline py-2 px-3 text-xs');
  menu.type = 'button';
  menu.dataset.workspaceMenu = '';
  menu.setAttribute('aria-label', 'Open workspace navigation');
  menu.setAttribute('aria-expanded', 'false');
  menu.setAttribute('aria-controls', 'workspace-nav');
  const menuIcon = element('i', 'fa-solid fa-bars w-4 h-4');
  menuIcon.setAttribute('aria-hidden', 'true');
  menu.append(menuIcon, element('span', 'sr-only', 'Menu'));
  header.prepend(menu);

  const backdrop = element('button', 'hidden fixed inset-0 z-40 bg-ink/40 md:hidden');
  backdrop.type = 'button';
  backdrop.setAttribute('aria-label', 'Close workspace navigation');
  host.insertBefore(backdrop, header.closest('[data-app-main]') || host.lastElementChild);

  let returnFocus = menu;
  const isMobile = () => window.matchMedia('(max-width: 767px)').matches;
  const closeMenu = () => {
    if (!isMobile()) return;
    aside.classList.add('hidden');
    aside.classList.remove('fixed', 'inset-y-0', 'left-0', 'z-50');
    backdrop.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    menu.setAttribute('aria-expanded', 'false');
    aside.inert = isMobile();
    if (isMobile()) returnFocus.focus();
  };
  const openMenu = () => {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : menu;
    aside.classList.remove('hidden');
    aside.classList.add('fixed', 'inset-y-0', 'left-0', 'z-50');
    backdrop.classList.remove('hidden');
    document.body.classList.add('overflow-hidden');
    menu.setAttribute('aria-expanded', 'true');
    aside.inert = false;
    aside.querySelector('a')?.focus();
  };
  aside.id = 'workspace-nav';
  aside.inert = isMobile();
  menu.addEventListener('click', openMenu);
  backdrop.addEventListener('click', closeMenu);
  aside.querySelectorAll('a').forEach((link) => link.addEventListener('click', closeMenu));
  aside.addEventListener('keydown', (event) => {
    if (event.key !== 'Tab') return;
    const focusable = [...aside.querySelectorAll('a[href], button:not([disabled])')];
    if (!focusable.length) return;
    if (event.shiftKey && document.activeElement === focusable[0]) {
      event.preventDefault();
      focusable.at(-1).focus();
    } else if (!event.shiftKey && document.activeElement === focusable.at(-1)) {
      event.preventDefault();
      focusable[0].focus();
    }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu.getAttribute('aria-expanded') === 'true') closeMenu();
  });
  window.matchMedia('(min-width: 768px)').addEventListener('change', (event) => {
    if (event.matches) {
      aside.classList.remove('fixed', 'inset-y-0', 'left-0', 'z-50');
      backdrop.classList.add('hidden');
      document.body.classList.remove('overflow-hidden');
      menu.setAttribute('aria-expanded', 'false');
      aside.inert = false;
    } else {
      aside.classList.add('hidden');
      aside.inert = true;
    }
  });
}

export const AppShell = {
  mount(options = {}) {
    const aside = createSidebar(options);
    const main = document.querySelector('[data-app-main]');
    if (main?.parentElement) main.parentElement.insertBefore(aside, main);
    if (main) {
      const header = main.querySelector('header');
      if (header) {
        wireMobileNavigation(aside, header, main.parentElement);
        mountPageHeader(main, header);
      }
      enhanceResponsiveTables(main);
    }
    return aside;
  }
};
