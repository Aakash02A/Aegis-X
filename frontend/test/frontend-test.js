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
  'operations.html'
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
  'css/animations.css',
  'css/components.css'
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

const componentsCss = fs.readFileSync(path.join(frontendDir, 'css/components.css'), 'utf-8');
assert(componentsCss.includes('--bg-obsidian'), 'Design tokens contain --bg-obsidian');
assert(componentsCss.includes('--brand-cyan'), 'Design tokens contain --brand-cyan');
assert(componentsCss.includes('.aegis-card'), 'Component class .aegis-card is defined');
assert(componentsCss.includes('.aegis-btn-primary'), 'Component class .aegis-btn-primary is defined');

// 3. Verify JavaScript Modules
console.log('\n3. Checking JavaScript Modules:');
const requiredJs = [
  'js/api.js',
  'js/auth.js',
  'js/dashboard.js',
  'js/compliance.js',
  'js/transaction.js',
  'js/upload.js',
  'js/ocr.js',
  'js/ai.js',
  'js/policy.js',
  'js/audit.js',
  'js/notifications.js',
  'js/utils.js',
  'js/operations.js'
];

requiredJs.forEach(js => {
  const filePath = path.join(frontendDir, js);
  const exists = fs.existsSync(filePath);
  assert(exists, `Module [${js}] exists`);
});

// 4. Verify Registration -> Login Redirect Requirement
console.log('\n4. Checking Mandatory UX Rules:');
const authJs = fs.readFileSync(path.join(frontendDir, 'js/auth.js'), 'utf-8');
assert(authJs.includes('login.html?registered=true'), 'Registration redirects to Login without auto-login (Stage 5 fix)');

// 5. Verify Evidence Upload Implementation
const uploadJs = fs.readFileSync(path.join(frontendDir, 'js/upload.js'), 'utf-8');
assert(uploadJs.includes('dragover'), 'Drag & drop dragover event is implemented');
assert(uploadJs.includes('uploadDocument'), 'Multipart uploadDocument API call is integrated');

const apiJs = fs.readFileSync(path.join(frontendDir, 'js/api.js'), 'utf-8');
assert(apiJs.includes('X-Correlation-ID'), 'Requests carry correlation IDs');
assert(apiJs.includes('sessionStorage'), 'JWT storage is session scoped');
assert(apiJs.includes("body.success === false"), 'API-level error envelopes are rejected');

const authHtml = fs.readFileSync(path.join(frontendDir, 'register.html'), 'utf-8');
assert(authHtml.includes('fullName,') && authHtml.includes('email,') && authHtml.includes('password'), 'Registration submits backend User fields');
assert(authJs.includes("API.request('/auth/me')"), 'Protected pages refresh role from the backend');
assert(!authJs.includes('merchant@aegisx.io'), 'Login contains no demo credentials');

const dashboardJs = fs.readFileSync(path.join(frontendDir, 'js/dashboard.js'), 'utf-8');
assert(dashboardJs.includes('beneficiaryName:'), 'Transaction creation uses backend beneficiaryName field');
assert(dashboardJs.includes('purposeCode:'), 'Transaction creation uses backend purposeCode field');
assert(dashboardJs.includes("t.status === 'EVIDENCE_PENDING'"), 'Evidence KPI matches the backend lifecycle enum');

const complianceJs = fs.readFileSync(path.join(frontendDir, 'js/compliance.js'), 'utf-8');
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

const settlementJs = fs.readFileSync(path.join(frontendDir, 'js/settlement.js'), 'utf-8');
assert(settlementJs.includes('/v1/settlements/status/'), 'Settlements use the existing status page API');
assert(settlementJs.includes('/retry'), 'Settlement retry uses the existing retry API');
assert(settlementJs.includes('/reconciliation/process'), 'Settlement reconciliation uses the existing API');

const transactionJs = fs.readFileSync(path.join(frontendDir, 'js/transaction.js'), 'utf-8');
assert(transactionJs.includes('/transactions/${this.transactionId}/documents'), 'Evidence list uses the transaction documents API');
assert(transactionJs.includes('/documents/${encodeURIComponent(uploadedDocument.id)}/process'), 'OCR uses the uploaded document ID');
assert(transactionJs.includes('noteText: text'), 'Review notes use the backend noteText field');
assert(transactionJs.includes('/notifications`'), 'Transaction notification history is API backed');

const aiJs = fs.readFileSync(path.join(frontendDir, 'js/ai.js'), 'utf-8');
assert(aiJs.includes('AI findings are advisory only. Final decisions are made by the deterministic policy engine.'), 'AI advisory notice is explicit');
assert(!aiJs.includes('overallRiskScore') && !aiJs.includes('94.5'), 'AI view has no fabricated risk fallback');

const operationsJs = fs.readFileSync(path.join(frontendDir, 'js/operations.js'), 'utf-8');
assert(operationsJs.includes("API.request('/actuator/health')"), 'Operations reads live actuator health');
assert(operationsJs.includes("API.request('/actuator/metrics')"), 'Operations reads actuator meters');
assert(operationsJs.includes("API.request('/performance/metrics')"), 'Operations reads backend performance observations');
assert(dashboardJs.includes("/transactions/${encodeURIComponent(transaction.id)}/audit"), 'Merchant activity feed reads audit events');
assert(dashboardJs.includes('transaction-pagination'), 'Merchant transaction table has pagination controls');
assert(complianceJs.includes('compliance-pagination'), 'Compliance table has pagination controls');
assert(!requiredPages.map(page => fs.readFileSync(path.join(frontendDir, page), 'utf-8')).some(content => content.includes('cdn.tailwindcss.com')), 'HTML pages do not load Tailwind from a runtime CDN');

// Every entry page, not just the eight core workflows: the integrity console is served from the
// same build and used to pull Font Awesome from a CDN.
const allHtmlPages = fs.readdirSync(frontendDir).filter(file => file.endsWith('.html'));
const externalAssetPattern = /(?:unpkg\.com|cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)/;
assert(!allHtmlPages.some(page => externalAssetPattern.test(fs.readFileSync(path.join(frontendDir, page), 'utf-8'))), 'HTML pages load no runtime third-party CDN assets');
assert(allHtmlPages.every(page => fs.readFileSync(path.join(frontendDir, page), 'utf-8').includes('css/icons.css')), 'HTML pages load the self-hosted icon stylesheet');

// Stylesheets are part of the runtime asset graph too, and the HTML scan above cannot see a request
// made from CSS. components.css used to @import Google Fonts, so every page load reached a third
// party even though the icons were already self-hosted. The fonts now come from pinned @fontsource
// packages through css/fonts.css, and neither an @import nor a url() may point off-origin again.
const cssDir = path.join(frontendDir, 'css');
const cssFiles = fs.readdirSync(cssDir).filter(file => file.endsWith('.css'));
const externalCssUrl = /(?:@import\s+(?:url\()?\s*['"]?https?:\/\/|url\(\s*['"]?https?:\/\/)/i;
const cssOffenders = cssFiles.filter(file => externalCssUrl.test(fs.readFileSync(path.join(cssDir, file), 'utf-8')));
assert(cssOffenders.length === 0, `Stylesheets load no runtime third-party assets (offending: ${cssOffenders.join(', ') || 'none'})`);
assert(cssFiles.includes('fonts.css'), 'The webfonts are self-hosted in css/fonts.css');
assert(fs.readFileSync(path.join(cssDir, 'components.css'), 'utf-8').includes("@import './fonts.css'"), 'components.css loads the self-hosted webfont stylesheet');

const packageJson = JSON.parse(fs.readFileSync(path.join(frontendDir, 'package.json'), 'utf-8'));
const fontPackages = ['fraunces', 'inter', 'jetbrains-mono', 'urbanist', 'work-sans'];
assert(fontPackages.every(name => packageJson.dependencies[`@fontsource/${name}`]), 'Every self-hosted webfont family is a pinned dependency');

globalThis.window = { AEGIS_API_BASE_URL: 'https://aegis.test/api' };
const storage = new Map([['aegis_jwt_token', 'test-session-token']]);
globalThis.sessionStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
globalThis.localStorage = { removeItem() {} };
let lastRequest;
globalThis.fetch = async (url, options) => {
  lastRequest = { url, options };
  return { ok: true, status: 200, text: async () => JSON.stringify({ success: true, data: { value: 7 } }) };
};
const { API } = await import('../js/api.js');
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
