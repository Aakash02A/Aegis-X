import { navigationForRole } from '../core/constants.js';
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
  return integrityPage === 'overview' ? 'integrity.html' : 'integrity-' + integrityPage + '.html';
}

function readPreference(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writePreference(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Navigation remains usable when browser storage is unavailable.
  }
}

function createRouteLink(route, activePage, compact = false) {
  const active = route.page === activePage || route.activePages?.includes(activePage);
  const link = element('a', 'flex items-center gap-3 rounded-full text-sm font-medium transition-colors');
  link.href = route.page;
  link.classList.add(...(compact ? ['px-3', 'py-2', 'text-xs'] : ['px-3.5', 'py-2.5']));
  link.classList.add(active ? 'bg-ink' : 'text-ink-soft', active ? 'text-paper' : 'hover:text-ink');
  if (!active) link.classList.add('hover:bg-ink/5');
  if (active) link.setAttribute('aria-current', 'page');
  link.classList.add('sidebar-link');
  link.title = route.label;
  link.setAttribute('aria-label', route.label);
  if (route.page.startsWith('integrity')) link.dataset.integrityRoute = route.page;

  const icon = element('i', route.icon + (compact ? ' w-3.5 h-3.5' : ' w-4 h-4'));
  icon.setAttribute('aria-hidden', 'true');
  link.append(icon, element('span', 'sidebar-link-label', route.label));
  return link;
}

function createNavigationGroup(group, activePage) {
  const wrapper = element('div', 'sidebar-nav-group');
  const isActive = group.routes.some(route =>
    route.page === activePage || route.activePages?.includes(activePage));
  const panel = element('div', 'sidebar-nav-children ml-3 pl-3 border-l border-ink/10 space-y-1');
  panel.id = 'sidebar-group-' + group.id;
  panel.setAttribute('aria-label', group.label + ' pages');

  const preferenceKey = 'aegis_nav_group_' + group.id;
  const expanded = isActive || readPreference(preferenceKey) === 'true';
  panel.hidden = !expanded;

  const toggle = element('button', 'sidebar-link sidebar-group-toggle flex items-center gap-3 w-full px-3.5 py-2.5 rounded-full text-sm font-medium transition-colors');
  toggle.type = 'button';
  toggle.title = group.label;
  toggle.setAttribute('aria-label', group.label);
  toggle.setAttribute('aria-controls', panel.id);
  toggle.setAttribute('aria-expanded', String(expanded));
  toggle.dataset.navGroupToggle = group.id;
  if (isActive) toggle.classList.add('bg-ink/5', 'text-ink');
  else toggle.classList.add('text-ink-soft', 'hover:text-ink', 'hover:bg-ink/5');

  const icon = element('i', group.icon + ' w-4 h-4');
  icon.setAttribute('aria-hidden', 'true');
  const chevron = element('i', 'fa-solid fa-chevron-down sidebar-group-chevron w-3 h-3 ml-auto');
  chevron.setAttribute('aria-hidden', 'true');
  toggle.append(icon, element('span', 'sidebar-link-label', group.label), chevron);

  for (const route of group.routes) panel.append(createRouteLink(route, activePage, true));
  toggle.addEventListener('click', () => {
    const shouldExpand = panel.hidden;
    panel.hidden = !shouldExpand;
    toggle.setAttribute('aria-expanded', String(shouldExpand));
    writePreference(preferenceKey, String(shouldExpand));
  });

  wrapper.append(toggle, panel);
  return wrapper;
}

export function createSidebar({ activePage = currentPageFile(), workspaceStatus = false } = {}) {
  const user = Auth.getUser();
  const role = user?.role || '';
  const name = user?.name || user?.email || 'Account';
  const aside = element('aside', 'app-sidebar bg-paper-dim border-r border-ink/10 flex flex-col justify-between shrink-0');
  aside.setAttribute('aria-label', 'Application sidebar');

  const top = element('div');
  const brandRow = element('div', 'sidebar-brand-row min-h-[4.5rem] px-6 flex items-center gap-3 border-b border-ink/10');
  const logo = element('a', 'sidebar-logo w-8 h-8 rounded-full bg-ink text-paper flex items-center justify-center font-serif text-sm font-bold', 'A');
  logo.href = 'index.html';
  logo.setAttribute('aria-label', 'Aegis-X home');
  const brand = element('div', 'sidebar-brand-copy');
  const wordmark = element('span', 'font-serif text-base tracking-tight font-semibold text-ink block');
  wordmark.append(document.createTextNode('Aegis'));
  wordmark.append(element('span', 'text-rust italic', 'X'));
  brand.append(wordmark, element('span', 'text-[9px] tracking-widest uppercase font-semibold text-ink-soft', role.replaceAll('_', ' ')));
  brandRow.append(logo, brand);

  const nav = element('nav', 'p-4 space-y-1 font-sans');
  nav.setAttribute('aria-label', 'Workspace navigation');
  for (const item of navigationForRole(role)) {
    nav.append(item.type === 'group'
      ? createNavigationGroup(item, activePage)
      : createRouteLink(item, activePage));
  }
  top.append(brandRow, nav);

  const footer = element('div', 'p-4 border-t border-ink/10');
  if (workspaceStatus) {
    footer.classList.add('space-y-2');
    const workspaceLabel = element('label', 'sidebar-workspace-details block text-[10px] uppercase tracking-wider text-ink-soft', 'Workspace');
    const workspaceSelect = element('select', 'mt-1 w-full min-w-0 border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-xs');
    workspaceSelect.id = 'integrity-workspace-switcher';
    workspaceSelect.setAttribute('aria-label', 'Choose an integrity workspace');
    workspaceSelect.append(element('option', '', 'Loading workspaces…'));
    workspaceLabel.append(workspaceSelect);
    const workspace = element('div', 'sidebar-workspace-details text-[11px] text-ink-soft', 'Workspace not loaded');
    workspace.id = 'integrity-workspace';
    const provider = element('div', 'sidebar-workspace-details text-[11px]');
    provider.id = 'integrity-provider';
    footer.append(workspaceLabel, workspace, provider);
  }

  const profileWrap = element('div', 'sidebar-account-wrap');
  const profile = element('div', 'sidebar-profile p-3 rounded-sm bg-white border border-ink/10 flex items-center justify-between');
  const identity = element('button', 'sidebar-account-toggle flex items-center gap-2.5 overflow-hidden min-w-0 text-left');
  identity.type = 'button';
  identity.dataset.accountMenuToggle = '';
  identity.setAttribute('aria-label', 'Open account details');
  identity.setAttribute('aria-controls', 'sidebar-account-menu');
  identity.setAttribute('aria-expanded', 'false');
  const initials = [...name.trim()].find(character => /[\p{L}\p{N}]/u.test(character))?.toLocaleUpperCase() || 'A';
  identity.append(element('span', 'sidebar-avatar w-8 h-8 rounded-full bg-ink/10 text-ink font-bold flex items-center justify-center text-xs shrink-0 font-serif', initials));
  const details = element('span', 'sidebar-profile-details truncate');
  details.append(element('span', 'text-xs font-semibold text-ink truncate block', name));
  details.append(element('span', 'text-[10px] text-ink-soft uppercase font-mono-data block', role.replaceAll('_', ' ')));
  identity.append(details);
  const accountChevron = element('i', 'fa-solid fa-chevron-down w-3 h-3 text-ink-soft sidebar-account-chevron');
  accountChevron.setAttribute('aria-hidden', 'true');
  identity.append(accountChevron);

  const logout = element('button', 'sidebar-logout text-ink-soft hover:text-rust p-2 transition-colors rounded-full');
  logout.type = 'button';
  logout.title = 'Sign out';
  logout.setAttribute('aria-label', 'Sign out');
  logout.dataset.appLogout = '';
  const logoutIcon = element('i', 'fa-solid fa-right-from-bracket w-4 h-4');
  logoutIcon.setAttribute('aria-hidden', 'true');
  logout.append(logoutIcon);
  logout.addEventListener('click', () => Auth.logout());
  profile.append(identity, logout);

  const accountMenu = element('div', 'sidebar-account-menu hidden mt-2 p-3 bg-white border border-ink/10 rounded-sm');
  accountMenu.id = 'sidebar-account-menu';
  accountMenu.setAttribute('role', 'region');
  accountMenu.setAttribute('aria-label', 'Account details');
  accountMenu.hidden = true;
  accountMenu.append(
    element('p', 'text-[10px] uppercase tracking-wider text-ink-soft', 'Signed in as'),
    element('p', 'text-xs font-semibold text-ink mt-1 break-words', name),
    element('p', 'text-xs text-ink-soft mt-1 break-all', user?.email || 'Email unavailable'),
    element('p', 'text-[10px] uppercase tracking-wider text-ink-soft mt-2', role.replaceAll('_', ' '))
  );
  identity.addEventListener('click', () => {
    const shouldOpen = accountMenu.hidden;
    accountMenu.hidden = !shouldOpen;
    identity.setAttribute('aria-expanded', String(shouldOpen));
    if (shouldOpen) aside.dispatchEvent(new Event('aegis-sidebar-expand'));
  });

  profileWrap.append(profile, accountMenu);
  footer.append(profileWrap);
  aside.append(top, footer);
  return aside;
}

export function wireMobileNavigation(aside, header, host) {
  if (!aside || !header || !host || header.querySelector('[data-workspace-menu]') || aside.querySelector('[data-sidebar-toggle]')) return;

  header.classList.add('app-topbar');
  for (const child of header.children) child.classList.add('min-w-0');

  const collapse = element('button', 'sidebar-collapse items-center justify-center text-ink-soft hover:text-ink rounded-full transition-colors');
  collapse.type = 'button';
  collapse.dataset.sidebarToggle = '';
  collapse.setAttribute('aria-controls', 'workspace-nav');
  const collapseIcon = element('i', 'fa-solid fa-angles-left w-4 h-4');
  collapseIcon.setAttribute('aria-hidden', 'true');
  collapse.append(collapseIcon, element('span', 'sr-only', 'Collapse sidebar'));

  const close = element('button', 'sidebar-close items-center justify-center text-ink-soft hover:text-ink rounded-full transition-colors');
  close.type = 'button';
  close.dataset.sidebarClose = '';
  close.setAttribute('aria-label', 'Close workspace navigation');
  const closeIcon = element('i', 'fa-solid fa-xmark w-4 h-4');
  closeIcon.setAttribute('aria-hidden', 'true');
  close.append(closeIcon);
  aside.querySelector('.sidebar-brand-row')?.append(close, collapse);

  const menu = element('button', 'sidebar-menu-toggle shrink-0 aegis-btn-outline py-2 px-3 text-xs');
  menu.type = 'button';
  menu.dataset.workspaceMenu = '';
  menu.setAttribute('aria-label', 'Open workspace navigation');
  menu.setAttribute('aria-expanded', 'false');
  menu.setAttribute('aria-controls', 'workspace-nav');
  const menuIcon = element('i', 'fa-solid fa-bars w-4 h-4');
  menuIcon.setAttribute('aria-hidden', 'true');
  menu.append(menuIcon, element('span', 'sr-only', 'Menu'));
  header.prepend(menu);

  const backdrop = element('button', 'sidebar-backdrop hidden fixed inset-0 z-40 bg-ink/40');
  backdrop.type = 'button';
  backdrop.setAttribute('aria-label', 'Close workspace navigation');
  host.insertBefore(backdrop, header.closest('[data-app-main]') || host.lastElementChild);

  const accountToggle = aside.querySelector('[data-account-menu-toggle]');
  const accountMenu = aside.querySelector('#sidebar-account-menu');
  const isMobile = () => window.matchMedia('(max-width: 1023px)').matches;
  let returnFocus = menu;

  const setCollapsed = (collapsed, persist = true) => {
    if (isMobile()) return;
    aside.classList.toggle('sidebar-collapsed', collapsed);
    collapse.setAttribute('aria-expanded', String(!collapsed));
    collapse.setAttribute('aria-label', collapsed ? 'Expand sidebar' : 'Collapse sidebar');
    collapse.title = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
    collapseIcon.classList.toggle('fa-angles-left', !collapsed);
    collapseIcon.classList.toggle('fa-angles-right', collapsed);
    collapse.querySelector('.sr-only').textContent = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
    if (persist) writePreference('aegis_sidebar_collapsed', String(collapsed));
  };

  collapse.addEventListener('click', () => setCollapsed(!aside.classList.contains('sidebar-collapsed')));
  aside.addEventListener('aegis-sidebar-expand', () => setCollapsed(false));
  setCollapsed(readPreference('aegis_sidebar_collapsed') === 'true', false);

  const closeAccountMenu = () => {
    if (!accountMenu || accountMenu.hidden) return;
    accountMenu.hidden = true;
    accountToggle?.setAttribute('aria-expanded', 'false');
  };

  const closeMenu = () => {
    if (!isMobile()) return;
    aside.classList.remove('sidebar-open');
    aside.setAttribute('aria-hidden', 'true');
    backdrop.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    menu.setAttribute('aria-expanded', 'false');
    aside.inert = true;
    closeAccountMenu();
    returnFocus.focus();
  };

  const openMenu = () => {
    if (!isMobile()) return;
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : menu;
    aside.classList.add('sidebar-open');
    aside.setAttribute('aria-hidden', 'false');
    backdrop.classList.remove('hidden');
    document.body.classList.add('overflow-hidden');
    menu.setAttribute('aria-expanded', 'true');
    aside.inert = false;
    close.focus();
  };

  aside.id = 'workspace-nav';
  aside.inert = isMobile();
  aside.setAttribute('aria-hidden', String(isMobile()));
  menu.addEventListener('click', openMenu);
  close.addEventListener('click', closeMenu);
  backdrop.addEventListener('click', closeMenu);
  aside.querySelectorAll('a').forEach(link => link.addEventListener('click', closeMenu));
  document.addEventListener('pointerdown', event => {
    const accountWrap = aside.querySelector('.sidebar-account-wrap');
    if (accountMenu && !accountMenu.hidden && accountWrap && !accountWrap.contains(event.target)) closeAccountMenu();
  });

  aside.addEventListener('keydown', event => {
    if (event.key !== 'Tab') return;
  const focusable = [...aside.querySelectorAll('a[href], button:not([disabled])')]
      .filter(item => !item.closest('[hidden]') && item.getClientRects().length > 0);
    if (!focusable.length) return;
    if (event.shiftKey && document.activeElement === focusable[0]) {
      event.preventDefault();
      focusable.at(-1).focus();
    } else if (!event.shiftKey && document.activeElement === focusable.at(-1)) {
      event.preventDefault();
      focusable[0].focus();
    }
  });

  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    if (menu.getAttribute('aria-expanded') === 'true') closeMenu();
    else closeAccountMenu();
  });

  window.matchMedia('(min-width: 1024px)').addEventListener('change', event => {
    aside.classList.remove('sidebar-open');
    backdrop.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    menu.setAttribute('aria-expanded', 'false');
    aside.inert = !event.matches;
    aside.setAttribute('aria-hidden', String(!event.matches));
    if (event.matches) setCollapsed(readPreference('aegis_sidebar_collapsed') === 'true', false);
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
