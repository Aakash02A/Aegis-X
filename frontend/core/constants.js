export const ROLE_HOME = Object.freeze({
  MERCHANT: 'dashboard.html',
  COMPLIANCE_OFFICER: 'compliance.html',
  SETTLEMENT_OPERATOR: 'settlement.html',
  ADMIN: 'operations.html'
});

export const APP_ROUTES = Object.freeze([
  { page: 'dashboard.html', label: 'Merchant Console', icon: 'fa-solid fa-table-columns', roles: ['MERCHANT'] },
  { page: 'compliance.html', label: 'Compliance Queue', icon: 'fa-solid fa-shield', roles: ['COMPLIANCE_OFFICER', 'ADMIN'] },
  { page: 'settlement.html', label: 'Settlement Desk', icon: 'fa-solid fa-landmark', roles: ['COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR', 'ADMIN'] },
  { page: 'operations.html', label: 'Operations & Health', icon: 'fa-solid fa-chart-line', roles: ['ADMIN'] },
  { page: 'integrity.html', label: 'Payment Integrity', icon: 'fa-solid fa-shield-halved', roles: ['ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR'] }
]);

export const INTEGRITY_ROUTES = Object.freeze([
  ['integrity.html', 'Overview', 'fa-solid fa-chart-line'],
  ['integrity-transactions.html', 'Transactions', 'fa-solid fa-receipt'],
  ['integrity-webhooks.html', 'Webhooks', 'fa-solid fa-tower-broadcast'],
  ['integrity-reconciliation.html', 'Reconciliation', 'fa-solid fa-code-compare'],
  ['integrity-evidence.html', 'Evidence', 'fa-solid fa-file-circle-check'],
  ['integrity-exceptions.html', 'Exceptions', 'fa-solid fa-triangle-exclamation'],
  ['integrity-recovery.html', 'Recovery', 'fa-solid fa-life-ring'],
  ['integrity-audit.html', 'Audit Trail', 'fa-solid fa-scroll'],
  ['integrity-settings.html', 'Settings', 'fa-solid fa-gear']
].map(([page, label, icon]) => Object.freeze({ page, label, icon, roles: ['ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR'] })));

export const PROTECTED_PAGE_ROLES = Object.freeze({
  ...Object.fromEntries(APP_ROUTES.map((route) => [route.page, route.roles])),
  'transaction.html': ['MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN'],
  ...Object.fromEntries(INTEGRITY_ROUTES.map((route) => [route.page, route.roles]))
});

export function currentProtectedPagePath() {
  const path = window.location.pathname || '';
  const page = path.split('/').pop();
  if (!Object.hasOwn(PROTECTED_PAGE_ROLES, page)) return null;
  return `${page}${window.location.search || ''}`;
}

export function safeReturnPath(candidate, role) {
  if (typeof candidate !== 'string' || !candidate.trim()) return null;
  try {
    const loginUrl = new URL('login.html', window.location.href);
    const appDirectory = new URL('.', loginUrl);
    const target = new URL(candidate, loginUrl);
    if (target.origin !== loginUrl.origin || !target.pathname.startsWith(appDirectory.pathname)) return null;
    const page = target.pathname.slice(appDirectory.pathname.length);
    if (page.includes('/') || !Object.hasOwn(PROTECTED_PAGE_ROLES, page)) return null;
    if (!PROTECTED_PAGE_ROLES[page].includes(role)) return null;
    return `${page}${target.search}${target.hash}`;
  } catch {
    return null;
  }
}
