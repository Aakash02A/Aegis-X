// End-to-end HTTP verification against a running Aegis-X backend.
// Usage: B=http://localhost:8080 CP=<classpath> OUT_DIR=<abs dir> node e2e-flows.mjs
import crypto from 'node:crypto';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const B = process.env.B || 'http://localhost:8080';
const RAZORPAY_WEBHOOK_SECRET = process.env.RAZORPAY_WEBHOOK_SECRET;
const OUT_DIR = process.env.OUT_DIR || '.';
const results = [];
let token = null;

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail ?? ''}`);
}

async function req(method, path, { body, headers = {}, raw, token: useToken = true } = {}) {
  const h = { ...headers };
  if (useToken && token) h.Authorization = `Bearer ${token}`;
  let payload = raw;
  if (body !== undefined && raw === undefined) {
    h['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(B + path, { method, headers: h, body: payload });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-json */ }
  return { status: res.status, json, text };
}

const email = `e2e-${Date.now()}@aegis-x.test`;
const password = process.env.E2E_USER_PASSWORD || ('Tst_' + Math.random().toString(36).slice(2) + '!Aa1');

try {
  const r = await req('GET', '/actuator/health', { token: false });
  record('GET /actuator/health', r.status === 200 && r.json?.status === 'UP', `status=${r.status} ${r.json?.status}`);
} catch (e) { record('GET /actuator/health', false, e.message); }

try {
  const r = await req('POST', '/api/auth/register', { body: { fullName: 'E2E Operator', email, password }, token: false });
  record('POST /api/auth/register', r.status === 201, `status=${r.status} role=${r.json?.role}`);
} catch (e) { record('POST /api/auth/register', false, e.message); }

try {
  const r = await req('POST', '/api/auth/login', { body: { email, password }, token: false });
  token = r.json?.token ?? null;
  record('POST /api/auth/login', r.status === 200 && !!token, `status=${r.status} role=${r.json?.role}`);
} catch (e) { record('POST /api/auth/login', false, e.message); }

try {
  const r = await req('GET', '/api/auth/me');
  record('GET /api/auth/me', r.status === 200 && r.json?.email === email, `status=${r.status}`);
} catch (e) { record('GET /api/auth/me', false, e.message); }

try {
  const r = await req('GET', '/api/integrity/workspaces/default', { token: false });
  record('Unauthenticated request refused', r.status === 401, `status=${r.status}`);
} catch (e) { record('Unauthenticated request refused', false, e.message); }

// Switch to a privileged operator for the integrity flows (role-based endpoints).
const merchantToken = token;
if (process.env.ADMIN_EMAIL) {
  try {
    const r = await req('POST', '/api/auth/login', { body: { email: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD }, token: false });
    const adminToken = r.json?.token ?? null;
    record('POST /api/auth/login (admin operator)', r.status === 200 && !!adminToken, `status=${r.status} role=${r.json?.role}`);
    if (adminToken) token = adminToken;
  } catch (e) { record('POST /api/auth/login (admin operator)', false, e.message); }
}
void merchantToken;

let wsId = null;
try {
  const r = await req('GET', '/api/integrity/workspaces/default');
  wsId = r.json?.data?.id ?? null;
  record('GET /api/integrity/workspaces/default', r.status === 200 && !!wsId, `status=${r.status} mode=${r.json?.data?.providerMode}`);
} catch (e) { record('GET /api/integrity/workspaces/default', false, e.message); }

if (!wsId) {
  // No workspace means none of the flows below can run. Reporting success here would let an
  // unreachable or misconfigured backend look like a passing verification.
  fs.writeFileSync(`${OUT_DIR}/e2e-report.json`, JSON.stringify(results, null, 2));
  console.log('\n=== E2E: aborted, no default workspace ===');
  process.exit(1);
}

if (process.env.E2E_SIMULATED_PROVIDER === 'true') {
  try {
    const javaBin = process.env.JAVA_BIN || 'java';
    const cp = process.env.CP || '';
    const mysqlTool = `${process.env.HARNESS_DIR || '.'}/MySqlTool.java`;
    const database = process.env.E2E_DB || 'aegisx_audit';
    execFileSync(javaBin, [
      '-cp', cp,
      mysqlTool,
      database,
      'sql',
      `UPDATE pi_workspaces SET provider_mode='SIMULATED' WHERE id='${wsId}'`,
    ], { stdio: 'pipe' });
    const updated = await req('GET', '/api/integrity/workspaces/default');
    record('Use in-process simulated provider',
      updated.status === 200 && updated.json?.data?.providerMode === 'SIMULATED',
      `status=${updated.status} mode=${updated.json?.data?.providerMode}`);
  } catch {
    record('Use in-process simulated provider', false, 'test fixture setup failed');
  }
}
const base = `/api/integrity/workspaces/${wsId}`;

let order = null;
try {
  const r = await req('POST', `${base}/orders`, { body: { amountMinor: 150000, currency: 'INR', description: 'E2E verification order' } });
  order = r.json?.data ?? null;
  record('POST orders (create)', r.status === 200 && !!order?.orderId, `status=${r.status} ref=${order?.internalReference} providerStatus=${order?.providerStatus}`);
} catch (e) { record('POST orders (create)', false, e.message); }

const orderId = order?.orderId;
const providerOrderId = order?.providerOrderId;

try {
  const l = await req('GET', `${base}/orders`);
  record('GET orders (list)', l.status === 200 && Array.isArray(l.json?.data) && l.json.data.length >= 1, `status=${l.status} count=${l.json?.data?.length}`);
  const d = await req('GET', `${base}/orders/${orderId}`);
  record('GET orders/{id} (detail + transitions)', d.status === 200 && Array.isArray(d.json?.data?.transitions), `status=${d.status} transitions=${d.json?.data?.transitions?.length}`);
} catch (e) { record('GET orders', false, e.message); }

try {
  const r = await req('GET', `${base}/payments`);
  record('GET payments', r.status === 200 && Array.isArray(r.json?.data), `status=${r.status} count=${r.json?.data?.length}`);
} catch (e) { record('GET payments', false, e.message); }

if (RAZORPAY_WEBHOOK_SECRET) {
  const providerPaymentId = `pay_e2e_${Date.now()}`;
  const eventId = `evt_e2e_${Date.now()}`;
  const rawBody = JSON.stringify({
    event: 'payment.captured',
    created_at: Math.floor(Date.now() / 1000),
    payload: { payment: { entity: {
      id: providerPaymentId, order_id: providerOrderId, status: 'captured',
      amount: 150000, currency: 'INR', method: 'card',
    } } },
  });
  const signature = crypto.createHmac('sha256', RAZORPAY_WEBHOOK_SECRET).update(rawBody).digest('hex');
  try {
    const first = await req('POST', `/api/integrity/webhooks/razorpay/${wsId}`, {
      raw: rawBody, token: false,
      headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': signature, 'X-Razorpay-Event-Id': eventId },
    });
    const second = await req('POST', `/api/integrity/webhooks/razorpay/${wsId}`, {
      raw: rawBody, token: false,
      headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': signature, 'X-Razorpay-Event-Id': eventId },
    });
    record('POST webhook signed (accept)', first.status === 200 && first.json?.duplicate !== true,
      `status=${first.status} duplicate=${first.json?.duplicate}`);
    record('POST webhook duplicate (dedupe)', second.status === 200 && second.json?.duplicate === true,
      `status=${second.status} duplicate=${second.json?.duplicate}`);
  } catch (e) { record('POST webhook signed', false, e.message); }
} else {
  record('POST webhook signed (skipped)', true, 'requires RAZORPAY_WEBHOOK_SECRET');
}

try {
  const r = await req('POST', `/api/integrity/webhooks/razorpay/${wsId}`, {
    raw: '{}', token: false,
    headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': 'deadbeef', 'X-Razorpay-Event-Id': 'evt_bad' },
  });
  record('POST webhook bad signature refused', r.status === 401 || r.status === 400, `status=${r.status}`);
} catch (e) { record('POST webhook bad signature refused', false, e.message); }

try {
  const r = await req('GET', `${base}/webhooks`);
  record('GET webhooks (history)', r.status === 200 && Array.isArray(r.json?.data?.events),
    `status=${r.status} events=${r.json?.data?.events?.length} duplicates=${r.json?.data?.duplicateDeliveryCount}`);
} catch (e) { record('GET webhooks', false, e.message); }

try {
  const r = await req('POST', `${base}/reconciliation/runs`, { body: { refreshProviderState: true } });
  record('POST reconciliation/runs', r.status === 200, `status=${r.status} findings=${r.json?.data?.findings?.length ?? r.json?.data?.findingCount ?? '?'}`);
  const f = await req('GET', `${base}/reconciliation/findings?limit=10`);
  record('GET reconciliation/findings', f.status === 200 && Array.isArray(f.json?.data), `status=${f.status} count=${f.json?.data?.length}`);
  const m = await req('GET', `${base}/reconciliation/methods`);
  record('GET reconciliation/methods', m.status === 200, `status=${m.status}`);
} catch (e) { record('POST reconciliation/runs', false, e.message); }

let exceptionId = null;
try {
  const r = await req('GET', `${base}/exceptions?status=OPEN`);
  exceptionId = r.json?.data?.content?.[0]?.id ?? null;
  record('GET exceptions', r.status === 200 && Array.isArray(r.json?.data?.content),
    `status=${r.status} open=${r.json?.data?.content?.length} total=${r.json?.data?.totalElements}`);
} catch (e) { record('GET exceptions', false, e.message); }

if (exceptionId) {
  try {
    const op = await req('POST', `${base}/exceptions/${exceptionId}/open`, { body: { note: 'Opening for E2E review' } });
    record('POST exception open', op.status === 200, `status=${op.status}`);
    const n = await req('POST', `${base}/exceptions/${exceptionId}/notes`, { body: { note: 'E2E human review note' } });
    record('POST exception note', n.status === 200, `status=${n.status}`);
    const res = await req('POST', `${base}/exceptions/${exceptionId}/resolve`, { body: { summary: 'Resolved during E2E verification', evidence: 'Verified against provider state' } });
    record('POST exception resolve', res.status === 200, `status=${res.status}`);
  } catch (e) { record('POST exception workflow', false, e.message); }
}

for (const [name, path] of [
  ['GET dashboard', `${base}/dashboard`],
  ['GET audit', `${base}/audit?page=0&size=10`],
  ['GET recommendations', `${base}/recommendations`],
  ['GET benchmark/method', `/api/integrity/benchmark/method`],
]) {
  try {
    const r = await req('GET', path);
    record(name, r.status === 200 && r.json?.success === true, `status=${r.status}`);
  } catch (e) { record(name, false, e.message); }
}

try {
  const outPath = `${OUT_DIR}/e2e-invoice.pdf`;
  const javaBin = process.env.JAVA_BIN || 'java';
  const cp = process.env.CP || '';
  execFileSync(javaBin, ['-cp', cp, `${process.env.HARNESS_DIR || '.'}/GenInvoice.java`, 'INV-E2E-0001',
    order?.internalReference ?? 'ORD-E2E', 'INR', '1500.00', outPath],
    { cwd: process.env.BACKEND_DIR || '.', stdio: 'inherit' });
  const pdfBytes = fs.readFileSync(outPath);

  const form = new FormData();
  form.append('file', new Blob([pdfBytes], { type: 'application/pdf' }), 'invoice-e2e.pdf');
  form.append('orderId', orderId);
  form.append('documentType', 'COMMERCIAL_INVOICE');
  const up = await fetch(`${B}${base}/evidence/documents`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const upJson = await up.json().catch(() => null);
  const docId = upJson?.data?.document?.id ?? null;
  record('POST evidence/documents (upload)', up.status === 200 && upJson?.data?.extractionFailed === false,
    `status=${up.status} extractionFailed=${upJson?.data?.extractionFailed}`);
  if (docId) {
    const list = await req('GET', `${base}/evidence/documents?orderId=${orderId}`);
    record('GET evidence/documents', list.status === 200 && Array.isArray(list.json?.data), `status=${list.status} count=${list.json?.data?.length}`);
    const ex = await req('GET', `${base}/evidence/documents/${docId}/extractions`);
    record('GET evidence extractions', ex.status === 200, `status=${ex.status} extractions=${ex.json?.data?.length}`);
    const corr = await req('POST', `${base}/evidence/documents/${docId}/corrections`, { body: { field: 'invoiceNumber', value: 'INV-E2E-CONFIRMED' } });
    record('POST evidence correction', corr.status === 200, `status=${corr.status}`);
    const sum = await req('GET', `${base}/evidence/summary?orderId=${orderId}`);
    record('GET evidence/summary (status separation)', sum.status === 200 && 'providerPaymentStatus' in (sum.json?.data ?? {}),
      `status=${sum.status} provider=${sum.json?.data?.providerPaymentStatus} internal=${sum.json?.data?.internalPaymentStatus} evidence=${sum.json?.data?.evidenceStatus}`);
    const reqs = await req('GET', `${base}/evidence/requirements?orderId=${orderId}`);
    record('GET evidence/requirements', reqs.status === 200, `status=${reqs.status}`);
    const assess = await req('POST', `${base}/evidence/assess?orderId=${orderId}`);
    record('POST evidence/assess', assess.status === 200, `status=${assess.status}`);
    const content = await fetch(`${B}${base}/evidence/documents/${docId}/content`, { headers: { Authorization: `Bearer ${token}` } });
    record('GET evidence content (download)', content.status === 200, `status=${content.status} type=${content.headers.get('content-type')}`);
  }
} catch (e) { record('POST evidence/documents (upload)', false, e.message); }

fs.writeFileSync(`${OUT_DIR}/e2e-report.json`, JSON.stringify(results, null, 2));
const failed = results.filter((r) => !r.ok).length;
console.log(`\n=== E2E: ${results.length - failed}/${results.length} passed, ${failed} failed ===`);
// Without this the runner exits 0 even when checks failed, and the surrounding script reports
// success on a broken build.
process.exit(failed ? 1 : 0);
