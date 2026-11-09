/**
 * Aegis-X Frontend Verification Test Suite (Stage 19)
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendDir = path.resolve(__dirname, '..');

console.log('🚀 Running Aegis-X Frontend Verification Tests...\n');

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (condition) {
    console.log(`  ✅ PASS: ${message}`);
    passed++;
  } else {
    console.error(`  ❌ FAIL: ${message}`);
    failed++;
  }
}

// 1. Verify Pages Existence
console.log('1. Checking Core HTML Pages:');
const requiredPages = [
  'index.html',
  'login.html',
  'register.html',
  'dashboard.html',
  'compliance.html',
  'transaction.html',
  'settlement.html',
  'operations.html',
  'not-permitted.html',
  '404.html',
  'error.html',
  'integrity.html',
  'integrity-transactions.html',
  'integrity-evidence.html',
  'integrity-webhooks.html',
  'integrity-reconciliation.html',
  'integrity-exceptions.html',
  'integrity-recovery.html',
  'integrity-audit.html',
  'integrity-settings.html'
];

requiredPages.forEach(page => {
  const filePath = path.join(frontendDir, page);
  const exists = fs.existsSync(filePath);
  assert(exists, `Page [${page}] exists`);
  if (exists) {
    const content = fs.readFileSync(filePath, 'utf-8');
    assert(content.includes('<!DOCTYPE html>'), `Page [${page}] contains valid DOCTYPE`);
    assert(content.includes('Aegis-X') || content.includes('AEGIS'), `Page [${page}] contains Aegis-X branding`);
  }
});

// 2. Verify Stylesheets & Tokens
console.log('\n2. Checking CSS Design Tokens & Animations:');
const requiredCss = [
  'styles/animations.css',
  'styles/components.css'
];

requiredCss.forEach(css => {
  const filePath = path.join(frontendDir, css);
  const exists = fs.existsSync(filePath);
  assert(exists, `Stylesheet [${css}] exists`);
  if (exists) {
    const content = fs.readFileSync(filePath, 'utf-8');
    assert(content.length > 200, `Stylesheet [${css}] is populated`);
  }
});

const componentsCss = fs.readFileSync(path.join(frontendDir, 'styles/components.css'), 'utf-8');
const designBaseline = JSON.parse(fs.readFileSync(path.join(frontendDir, '..', 'docs', 'design-baseline.json'), 'utf-8'));
const currentTokens = {};
const currentFonts = new Set();
for (const file of fs.readdirSync(path.join(frontendDir, 'styles')).filter(file => file.endsWith('.css'))) {
  const css = fs.readFileSync(path.join(frontendDir, 'styles', file), 'utf-8');
  for (const match of css.matchAll(/(--[A-Za-z0-9_-]+)\s*:\s*([^;]+);/g)) currentTokens[match[1]] = match[2].trim();
  for (const match of css.matchAll(/font-family\s*:\s*([^;]+);/g)) currentFonts.add(match[1].trim());
}
assert(JSON.stringify(Object.fromEntries(Object.entries(currentTokens).sort())) === JSON.stringify(Object.fromEntries(Object.entries(designBaseline.cssCustomProperties).sort())), 'CSS custom properties match the approved design baseline exactly');
assert(JSON.stringify([...currentFonts].sort()) === JSON.stringify([...designBaseline.fontFamilyDeclarations].sort()), 'CSS font-family declarations match the approved design baseline exactly');
assert(componentsCss.includes('--bg-obsidian'), 'Design tokens contain --bg-obsidian');
assert(componentsCss.includes('--brand-cyan'), 'Design tokens contain --brand-cyan');
assert(componentsCss.includes('.aegis-card'), 'Component class .aegis-card is defined');
assert(componentsCss.includes('.aegis-btn-primary'), 'Component class .aegis-btn-primary is defined');

// 3. Verify JavaScript Modules
console.log('\n3. Checking JavaScript Modules:');
const requiredJs = [
  'core/api.js',
  'core/auth.js',
  'core/guards.js',
  'core/constants.js',
  'components/app-shell.js',
  'components/page-header.js',
  'components/responsive-table.js',
  'components/status-chip.js',
  'pages/merchant-dashboard.js',
  'pages/compliance-review.js',
  'pages/transaction-detail.js',
  'components/file-uploader.js',
  'services/ocr.js',
  'services/ai-advisory.js',
  'services/policy.js',
  'components/audit-timeline.js',
  'components/toast.js',
  'core/formatters.js',
  'pages/operations-dashboard.js'
];
requiredJs.push(
  'pages/login.js', 'pages/register.js', 'pages/landing.js',
  'pages/route-outcome.js',
  'pages/integrity/workspace.js',
  ...['overview', 'transactions', 'evidence', 'webhooks', 'reconciliation', 'exceptions', 'recovery', 'audit', 'settings']
    .map(page => `pages/integrity-${page}.js`)
);

requiredJs.forEach(js => {
  const filePath = path.join(frontendDir, js);
  const exists = fs.existsSync(filePath);
  assert(exists, `Module [${js}] exists`);
});

const appShellJs = fs.readFileSync(path.join(frontendDir, 'components/app-shell.js'), 'utf-8');
const responsiveTableJs = fs.readFileSync(path.join(frontendDir, 'components/responsive-table.js'), 'utf-8');
const pageHeaderJs = fs.readFileSync(path.join(frontendDir, 'components/page-header.js'), 'utf-8');
assert(appShellJs.includes('data-workspace-menu') && appShellJs.includes('aria-expanded'), 'Shared shell exposes an accessible mobile navigation control');
assert(appShellJs.includes("event.key === 'Escape'") && appShellJs.includes('aside.inert'), 'Mobile navigation supports Escape and hides closed links from keyboard and assistive technology');
assert(responsiveTableJs.includes('data-label') && responsiveTableJs.includes('MutationObserver'), 'Responsive tables label both initial and dynamically added rows');
assert(pageHeaderJs.includes('[data-page-heading]') && pageHeaderJs.includes('header.insertBefore'), 'Shared page heading mounts in the responsive application header');
const dashboardHtml = fs.readFileSync(path.join(frontendDir, 'dashboard.html'), 'utf-8');
assert(!dashboardHtml.includes('md:hidden flex items-center gap-2'), 'Dashboard does not duplicate the shared mobile brand mark');

// 4. Verify Registration -> Login Redirect Requirement
console.log('\n4. Checking Mandatory UX Rules:');
const authJs = fs.readFileSync(path.join(frontendDir, 'core/auth.js'), 'utf-8');
assert(authJs.includes('login.html?registered=true'), 'Registration redirects to Login without auto-login (Stage 5 fix)');

// 5. Verify Evidence Upload Implementation
const uploadJs = fs.readFileSync(path.join(frontendDir, 'components/file-uploader.js'), 'utf-8');
assert(uploadJs.includes('dragover'), 'Drag & drop dragover event is implemented');
assert(uploadJs.includes('uploadDocument'), 'Multipart uploadDocument API call is integrated');

const apiJs = fs.readFileSync(path.join(frontendDir, 'core/api.js'), 'utf-8');
assert(apiJs.includes('X-Correlation-ID'), 'Requests carry correlation IDs');
assert(apiJs.includes('sessionStorage'), 'JWT storage is session scoped');
assert(apiJs.includes("body.success === false"), 'API-level error envelopes are rejected');

const authHtml = fs.readFileSync(path.join(frontendDir, 'register.html'), 'utf-8');
const registerJs = fs.readFileSync(path.join(frontendDir, 'pages/register.js'), 'utf-8');
assert(authHtml.includes('id="reg-company"') && registerJs.includes('fullName,') && registerJs.includes('email,') && registerJs.includes('password'), 'Registration submits backend User fields');
assert(authJs.includes("API.request('/auth/me')"), 'Protected pages refresh role from the backend');
assert(!authJs.includes('merchant@aegisx.io'), 'Login contains no demo credentials');
assert(authJs.includes('safeReturnPath(requested, result.role)'), 'Login returns only to a page allowed for the authenticated role');
assert(authJs.includes("window.location.assign('not-permitted.html')"), 'Wrong-role access leads to the not-permitted page');
assert(['pages/login.js', 'pages/register.js', 'pages/landing.js'].every(file => fs.existsSync(path.join(frontendDir, file))), 'Public pages use external JavaScript modules');
const allPageHtml = requiredPages.map(page => fs.readFileSync(path.join(frontendDir, page), 'utf-8'));
assert(allPageHtml.every(content => !/<script\s+type="module">|\sonclick=/i.test(content)), 'HTML pages contain no inline modules or event handlers');

const dashboardJs = fs.readFileSync(path.join(frontendDir, 'pages/merchant-dashboard.js'), 'utf-8');
assert(dashboardJs.includes('beneficiaryName:'), 'Transaction creation uses backend beneficiaryName field');
assert(dashboardJs.includes('purposeCode:'), 'Transaction creation uses backend purposeCode field');
assert(dashboardJs.includes("t.status === 'EVIDENCE_PENDING'"), 'Evidence KPI matches the backend lifecycle enum');

const complianceJs = fs.readFileSync(path.join(frontendDir, 'pages/compliance-review.js'), 'utf-8');
assert(complianceJs.includes("/v1/compliance/queue"), 'Compliance reads the review queue API');
assert(complianceJs.includes("/v1/compliance/review"), 'Compliance decisions use the review API');
assert(complianceJs.includes('justification.length < 10'), 'Review decisions require justification');
assert(complianceJs.includes('URLSearchParams') && complianceJs.includes('result.totalPages'), 'Compliance queue uses server pagination');
assert(!complianceJs.includes('transactions.slice('), 'Compliance queue does not paginate only the loaded client subset');
assert(complianceJs.includes('No escalated cases are awaiting a decision.'), 'Compliance queue has a specific empty state');
assert(complianceJs.includes('this.loadError') && complianceJs.includes("retry.textContent = 'Retry'"), 'Compliance queue shows an error state with retry');
assert(complianceJs.includes('result?.resultingState'), 'Compliance UI displays the decision result state from the API');
assert(!complianceJs.includes('event.submitter'), 'Review decision is selected explicitly and cannot default to Reject on Enter');
const complianceHtml = fs.readFileSync(path.join(frontendDir, 'compliance.html'), 'utf-8');
assert(complianceHtml.includes('Only escalated cases awaiting a human decision appear here.'), 'Compliance UI explains the reviewable queue scope');
assert(!complianceHtml.includes('OCR Failure') && !complianceHtml.includes('Settlement Failure'), 'Compliance UI does not offer non-reviewable failure filters');

const settlementJs = fs.readFileSync(path.join(frontendDir, 'pages/settlements.js'), 'utf-8');
assert(settlementJs.includes('/v1/settlements/status/'), 'Settlements use the existing status page API');
assert(settlementJs.includes('/retry'), 'Settlement retry uses the existing retry API');
assert(settlementJs.includes('/reconciliation/process'), 'Settlement reconciliation uses the existing API');
assert(settlementJs.includes('renderStatusChip') && !settlementJs.includes('renderSettlementBadge'), 'Settlement status uses the shared status chip');

const transactionJs = fs.readFileSync(path.join(frontendDir, 'pages/transaction-detail.js'), 'utf-8');
assert(transactionJs.includes('/transactions/${this.transactionId}/documents'), 'Evidence list uses the transaction documents API');
assert(transactionJs.includes('/documents/${encodeURIComponent(uploadedDocument.id)}/process'), 'OCR uses the uploaded document ID');
assert(transactionJs.includes("const canViewAdvisory = ['MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN'].includes(role)"), 'Transaction detail avoids advisory endpoints unavailable to Settlement Operators');
assert(transactionJs.includes("await this.loadAllSubsystems({ reevaluatePolicy: true })"), 'A new evidence upload reevaluates the Java policy outcome');
assert(transactionJs.includes('documents.length && (!policyData || reevaluatePolicy)'), 'Policy evaluation waits for evidence and can be refreshed after uploads');
assert(transactionJs.includes("requireUser(['MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN'])"), 'Transaction detail route excludes roles that the transaction API cannot resolve');
assert(transactionJs.includes("Auth.getUser()?.role === 'SETTLEMENT_OPERATOR'"), 'Settlement Operator transaction view hides notification actions unavailable to that role');
assert(transactionJs.includes('noteText: text'), 'Review notes use the backend noteText field');
assert(transactionJs.includes('/notifications`'), 'Transaction notification history is API backed');

const aiJs = fs.readFileSync(path.join(frontendDir, 'services/ai-advisory.js'), 'utf-8');
assert(aiJs.includes('AI findings are advisory only. Final decisions are made by the deterministic policy engine.'), 'AI advisory notice is explicit');
assert(!aiJs.includes('overallRiskScore') && !aiJs.includes('94.5'), 'AI view has no fabricated risk fallback');
const policyJs = fs.readFileSync(path.join(frontendDir, 'services/policy.js'), 'utf-8');
assert(policyJs.includes('No policy outcome has been recorded yet.') && !policyJs.includes('pending AI analysis'), 'Policy waiting copy does not make AI a prerequisite for deterministic evaluation');

const operationsJs = fs.readFileSync(path.join(frontendDir, 'pages/operations-dashboard.js'), 'utf-8');
assert(operationsJs.includes("API.request('/actuator/health')"), 'Operations reads live actuator health');
assert(operationsJs.includes("API.request('/actuator/metrics')"), 'Operations reads actuator meters');
assert(operationsJs.includes("API.request('/actuator/prometheus')") && !operationsJs.includes("API.request('/prometheus')"), 'Operations uses the configured actuator Prometheus route');
assert(operationsJs.includes("API.request('/performance/metrics')"), 'Operations reads backend performance observations');
assert(!dashboardJs.includes("API.request('/performance/metrics')"), 'Merchant dashboard does not call a staff-only performance endpoint');
const settlementPageJs = fs.readFileSync(path.join(frontendDir, 'pages/settlements.js'), 'utf-8');
assert(settlementPageJs.includes("API.request('/v1/settlements/overdue')") && settlementPageJs.includes("role === 'ADMIN'"), 'Admin settlement view uses the Admin-authorized overdue endpoint');
assert(settlementPageJs.includes("'SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER', 'ADMIN'"), 'Settlement page route matches the roles supported by its UI');
const integrityWorkspaceJs = fs.readFileSync(path.join(frontendDir, 'pages/integrity/workspace.js'), 'utf-8');
assert(integrityWorkspaceJs.includes("API.request('/integrity/workspaces')") && integrityWorkspaceJs.includes('integrity-workspace-switcher'), 'Integrity workspace switcher loads only the signed-in user workspace list');
assert(integrityWorkspaceJs.includes("['ADMIN', 'COMPLIANCE_OFFICER'].includes(Auth.getUser()?.role)"), 'Integrity review and threshold controls are limited to authorized roles');
assert(dashboardJs.includes("/transactions/${encodeURIComponent(transaction.id)}/audit"), 'Merchant activity feed reads audit events');
assert(dashboardJs.includes('transaction-pagination'), 'Merchant transaction table has pagination controls');
assert(dashboardJs.includes('data-transaction-id') && !dashboardJs.includes('onclick='), 'Transaction row navigation uses delegated listeners without inline handlers');
assert(complianceJs.includes('compliance-pagination'), 'Compliance table has pagination controls');
assert(!requiredPages.map(page => fs.readFileSync(path.join(frontendDir, page), 'utf-8')).some(content => content.includes('cdn.tailwindcss.com')), 'HTML pages do not load Tailwind from a runtime CDN');

// Every entry page, not just the eight core workflows: the integrity console is served from the
// same build and used to pull Font Awesome from a CDN.
const allHtmlPages = fs.readdirSync(frontendDir).filter(file => file.endsWith('.html'));
const externalAssetPattern = /(?:unpkg\.com|cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)/;
assert(!allHtmlPages.some(page => externalAssetPattern.test(fs.readFileSync(path.join(frontendDir, page), 'utf-8'))), 'HTML pages load no runtime third-party CDN assets');
assert(allHtmlPages.every(page => fs.readFileSync(path.join(frontendDir, page), 'utf-8').includes('styles/icons.css')), 'HTML pages load the self-hosted icon stylesheet');

// Stylesheets are part of the runtime asset graph too, and the HTML scan above cannot see a request
// made from CSS. components.css used to @import Google Fonts, so every page load reached a third
// party even though the icons were already self-hosted. The fonts now come from pinned @fontsource
// packages through styles/fonts.css, and neither an @import nor a url() may point off-origin again.
const cssDir = path.join(frontendDir, 'styles');
const cssFiles = fs.readdirSync(cssDir).filter(file => file.endsWith('.css'));
const externalCssUrl = /(?:@import\s+(?:url\()?\s*['"]?https?:\/\/|url\(\s*['"]?https?:\/\/)/i;
const cssOffenders = cssFiles.filter(file => externalCssUrl.test(fs.readFileSync(path.join(cssDir, file), 'utf-8')));
assert(cssOffenders.length === 0, `Stylesheets load no runtime third-party assets (offending: ${cssOffenders.join(', ') || 'none'})`);
assert(cssFiles.includes('fonts.css'), 'The webfonts are self-hosted in styles/fonts.css');
assert(fs.readFileSync(path.join(cssDir, 'components.css'), 'utf-8').includes("@import './fonts.css'"), 'components.css loads the self-hosted webfont stylesheet');

const packageJson = JSON.parse(fs.readFileSync(path.join(frontendDir, 'package.json'), 'utf-8'));
const fontPackages = ['fraunces', 'inter', 'jetbrains-mono', 'urbanist', 'work-sans'];
assert(fontPackages.every(name => packageJson.dependencies[`@fontsource/${name}`]), 'Every self-hosted webfont family is a pinned dependency');

let redirectTarget = '';
globalThis.window = { AEGIS_API_BASE_URL: 'https://aegis.test/api', location: {
  href: 'https://aegis.test/dashboard.html', origin: 'https://aegis.test', pathname: '/dashboard.html', search: '',
  assign: target => { redirectTarget = target; }
} };
globalThis.location = { pathname: '/dashboard.html' };
const storage = new Map([['aegis_jwt_token', 'test-session-token']]);
globalThis.sessionStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
const localStorageRemovals = [];
globalThis.localStorage = { removeItem: key => localStorageRemovals.push(key) };
let lastRequest;
globalThis.fetch = async (url, options) => {
  lastRequest = { url, options };
  return { ok: true, status: 200, text: async () => JSON.stringify({ success: true, data: { value: 7 } }) };
};
const { API } = await import('../core/api.js');
const { safeReturnPath } = await import('../core/constants.js');
assert(safeReturnPath('transaction.html?id=sample', 'MERCHANT') === 'transaction.html?id=sample', 'Same-origin role-allowed return paths preserve the page query');
assert(safeReturnPath('compliance.html', 'MERCHANT') === null, 'Return paths cannot cross role boundaries');
assert(safeReturnPath('transaction.html?id=sample', 'SETTLEMENT_OPERATOR') === null, 'Return paths cannot open transaction details unavailable to Settlement Operators');
assert(safeReturnPath('https://outside.example/operations.html', 'ADMIN') === null, 'Return paths cannot leave the application origin');
const envelopeData = await API.request('/v1/compliance/queue');
assert(envelopeData.value === 7, 'API client unwraps standard success envelopes');
assert(lastRequest.options.headers.Authorization === 'Bearer test-session-token', 'API client injects bearer token');
assert(Boolean(lastRequest.options.headers['X-Correlation-ID']), 'API client sends a generated correlation ID');
await API.request('/actuator/health');
assert(lastRequest.url === 'https://aegis.test/actuator/health', 'Actuator requests use the backend root route');
globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => JSON.stringify({ success: false, message: 'denied' }) });
let envelopeRejected = false;
try {
  await API.request('/v1/compliance/queue');
} catch (error) {
  envelopeRejected = error.message === 'denied';
}
assert(envelopeRejected, 'API client rejects unsuccessful response envelopes');
globalThis.window.location.search = '?id=sample';
globalThis.fetch = async () => ({ ok: false, status: 401, text: async () => '{"message":"expired"}' });
let unauthorizedStatus;
try { await API.request('/transactions'); } catch (error) { unauthorizedStatus = error.status; }
assert(redirectTarget === 'login.html?session=expired&return=dashboard.html%3Fid%3Dsample', 'API client redirects expired sessions back to the current page');
assert(unauthorizedStatus === 401, 'API errors retain their HTTP status for route guards');
assert(!storage.has('aegis_jwt_token') && localStorageRemovals.includes('aegis_user'), '401 handling clears stored authentication state');

const viteConfig = fs.readFileSync(path.join(frontendDir, 'vite.config.js'), 'utf-8');
assert(allHtmlPages.every(page => viteConfig.includes(`'${page.replace(/\.html$/, '')}'`)), 'Production build includes every HTML entry page');

console.log(`\n========================================`);
console.log(`Test Results: ${passed} Passed, ${failed} Failed`);
console.log(`========================================\n`);

if (failed > 0) {
  process.exit(1);
} else {
  console.log('🎉 All Aegis-X Frontend Verification Tests Passed Successfully!');
}
