// Evidence-intelligence verification against a running Aegis-X backend.
// Usage: B=http://localhost:8080 CP=<classpath> OUT_DIR=<abs> HARNESS_DIR=<abs> BACKEND_DIR=<win path> node evidence-verify.mjs
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const B = process.env.B || 'http://localhost:8080';
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

function genPdf(label, invoiceNumber, orderRef, currency, total, filename) {
  const outPath = `${OUT_DIR}/${filename}`;
  execFileSync(process.env.JAVA_BIN || 'java',
    ['-cp', process.env.CP || '', `${process.env.HARNESS_DIR || '.'}/GenInvoice.java`,
      invoiceNumber, orderRef, currency, total, outPath],
    { cwd: process.env.BACKEND_DIR || '.', stdio: 'ignore' });
  return outPath;
}

async function upload(path, orderId, docType, filename) {
  const bytes = fs.readFileSync(path);
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: 'application/pdf' }), filename);
  form.append('orderId', orderId);
  form.append('documentType', docType);
  const res = await fetch(`${B}${base}/evidence/documents`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const json = await res.json().catch(() => null);
  return { status: res.status, json };
}

async function createOrder(amountMinor = 150000, currency = 'INR') {
  const r = await req('POST', `${base}/orders`, { body: { amountMinor, currency, description: 'Evidence verification order' } });
  if (!r.json?.data) { record('create order (debug)', false, `status=${r.status} body=${r.text?.slice(0, 200)}`); }
  return r.json?.data ?? null;
}

// Evidence findings materialise as exception cases (the queue the operator works from),
// so they are read from /exceptions rather than /reconciliation/findings.
async function findingsFor(orderId) {
  await req('POST', `${base}/evidence/assess?orderId=${orderId}`);
  const q = await req('GET', `${base}/exceptions?page=0&size=200`);
  const all = Array.isArray(q.json?.data?.content) ? q.json.data.content : [];
  return all.filter((x) => x.orderId === orderId).map((x) => x.findingType);
}

async function summaryFor(orderId) {
  const s = await req('GET', `${base}/evidence/summary?orderId=${orderId}`);
  return s.json?.data ?? {};
}

// login as the privileged operator seeded by run-e2e.sh
const login = await req('POST', '/api/auth/login', {
  body: { email: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD }, token: false,
});
token = login.json?.token ?? null;
record('login admin', login.status === 200 && !!token, `status=${login.status}`);

const ws = await req('GET', '/api/integrity/workspaces/default');
const wsId = ws.json?.data?.id;
record('resolve default workspace', !!wsId, `workspace=${wsId}`);
const base = `/api/integrity/workspaces/${wsId}`;
if (!wsId || !token) { fs.writeFileSync(`${OUT_DIR}/evidence-report.json`, JSON.stringify(results, null, 2)); process.exit(0); }

// ---------------------------------------------------------------- Scenario 1: correct invoice (no synthetic provider outcome)
const orderA = await createOrder(150000);
const pdfA = genPdf('a', 'INV-EV-0001', orderA.internalReference, 'INR', '1500.00', 'ev-correct.pdf');
const upA = await upload(pdfA, orderA.orderId, 'COMMERCIAL_INVOICE', 'ev-correct.pdf');
const docA = upA.json?.data?.document?.id;
record('S1 upload correct invoice', upA.status === 200 && upA.json?.data?.extractionFailed === false, `status=${upA.status} extractionFailed=${upA.json?.data?.extractionFailed}`);

// measure extraction correctness against the manually authored truth
// Amounts in this engine are integer minor units, so 1500.00 INR is expected as 150000.
const TRUTH = { invoiceNumber: 'INV-EV-0001', orderReference: orderA.internalReference, currency: 'INR', total: 150000 };
const ex = await req('GET', `${base}/evidence/documents/${docA}/extractions`);
const latest = Array.isArray(ex.json?.data) ? ex.json.data[ex.json.data.length - 1] : null;
let extracted = {};
try { extracted = JSON.parse(latest?.extractedData || '{}'); } catch { /* ignore */ }
const checks = [
  ['invoiceNumber', extracted.invoiceNumber === TRUTH.invoiceNumber, extracted.invoiceNumber],
  ['orderReference', extracted.orderReference === TRUTH.orderReference, extracted.orderReference],
  ['currency', extracted.currency === TRUTH.currency, extracted.currency],
  ['total', Number(extracted.total) === TRUTH.total, `${extracted.total} (minor units; expected ${TRUTH.total} for 1500.00 INR)`],
];
for (const [field, ok, got] of checks) {
  record(`S1 extracted ${field} matches manual truth`, ok, `expected=${JSON.stringify(TRUTH[field])} got=${JSON.stringify(got)}`);
}
record('S1 no uncertain fields for a clean invoice', Array.isArray(latest?.uncertainFields) ? latest.uncertainFields.length === 0 : String(latest?.uncertainFields || '').length <= 2,
  `uncertain=${JSON.stringify(latest?.uncertainFields)}`);

const sumA = await summaryFor(orderA.orderId);
record('S1 payment status and evidence status are distinct fields',
  Object.hasOwn(sumA, 'evidenceStatus') && (!sumA.providerPaymentStatus || sumA.providerPaymentStatus === 'UNKNOWN'),
  `provider=${sumA.providerPaymentStatus ?? 'not configured'} internal=${sumA.internalPaymentStatus} evidence=${sumA.evidenceStatus}`);

// ---------------------------------------------------------------- Scenario 2: amount mismatch
const orderB = await createOrder(150000);
const pdfB = genPdf('b', 'INV-EV-0002', orderB.internalReference, 'INR', '9999.00', 'ev-amount.pdf');
await upload(pdfB, orderB.orderId, 'COMMERCIAL_INVOICE', 'ev-amount.pdf');
const fB = await findingsFor(orderB.orderId);
record('S2 amount mismatch detected (EVIDENCE_AMOUNT_MISMATCH)', fB.includes('EVIDENCE_AMOUNT_MISMATCH'), `findings=${JSON.stringify([...new Set(fB)])}`);

// ---------------------------------------------------------------- Scenario 3: currency mismatch
const orderC = await createOrder(150000);
const pdfC = genPdf('c', 'INV-EV-0003', orderC.internalReference, 'USD', '1500.00', 'ev-currency.pdf');
await upload(pdfC, orderC.orderId, 'COMMERCIAL_INVOICE', 'ev-currency.pdf');
const fC = await findingsFor(orderC.orderId);
record('S3 currency mismatch detected (EVIDENCE_CURRENCY_MISMATCH)', fC.includes('EVIDENCE_CURRENCY_MISMATCH'), `findings=${JSON.stringify([...new Set(fC)])}`);

// ---------------------------------------------------------------- Scenario 4: unreadable document
const orderD = await createOrder(150000);
const corruptPath = `${OUT_DIR}/ev-corrupt.pdf`;
// Unique per run so the byte-identical upload guard does not short-circuit this scenario.
fs.writeFileSync(corruptPath, `%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nthis is not a valid PDF body ${Date.now()}\n%%EOF\n`);
const upD = await upload(corruptPath, orderD.orderId, 'COMMERCIAL_INVOICE', 'ev-corrupt.pdf');
record('S4 corrupt document reported as failed-to-read',
  upD.status === 200 && upD.json?.data?.extractionFailed === true,
  `status=${upD.status} extractionFailed=${upD.json?.data?.extractionFailed} processingStatus=${upD.json?.data?.document?.processingStatus}`);
const fD = await findingsFor(orderD.orderId);
record('S4 unreadable is not treated as missing',
  fD.includes('EVIDENCE_UNREADABLE') && !fD.includes('MISSING_REQUIRED_EVIDENCE'),
  `findings=${JSON.stringify([...new Set(fD)])}`);

// ---------------------------------------------------------------- Scenario 5: genuinely missing invoice
const orderE = await createOrder(150000);
const fE = await findingsFor(orderE.orderId);
record('S5 missing invoice detected (MISSING_REQUIRED_EVIDENCE)', fE.includes('MISSING_REQUIRED_EVIDENCE'), `findings=${JSON.stringify([...new Set(fE)])}`);

// ---------------------------------------------------------------- Scenario 6: duplicate document
// A byte-identical re-upload is refused at intake (409) rather than stored as a second document;
// the DUPLICATE_EVIDENCE_DOCUMENT finding covers the different-bytes/same-invoice-number reissue.
const orderF = await createOrder(150000);
const pdfF = genPdf('f', 'INV-EV-0006', orderF.internalReference, 'INR', '1500.00', 'ev-dup.pdf');
const upF1 = await upload(pdfF, orderF.orderId, 'COMMERCIAL_INVOICE', 'ev-dup.pdf');
const upF2 = await upload(pdfF, orderF.orderId, 'COMMERCIAL_INVOICE', 'ev-dup-copy.pdf');
record('S6 byte-identical re-upload refused at intake', upF1.status === 200 && upF2.status === 409,
  `first=${upF1.status} second=${upF2.status} message=${String(upF2.json?.message || '').slice(0, 70)}`);
const docsF = await req('GET', `${base}/evidence/documents?orderId=${orderF.orderId}`);
record('S6 refused duplicate did not create a second current document',
  Array.isArray(docsF.json?.data) && docsF.json.data.length === 1, `current=${docsF.json?.data?.length}`);

// ---------------------------------------------------------------- Scenario 7: human-reviewed resolution
const queue = await req('GET', `${base}/exceptions?page=0&size=200`);
const caseForG = (queue.json?.data?.content || []).find((c) =>
  c.orderId === orderB.orderId && c.findingType === 'EVIDENCE_AMOUNT_MISMATCH');
if (caseForG) {
  const res = await req('POST', `${base}/exceptions/${caseForG.id}/resolve`, {
    body: { summary: 'Invoice amount discrepancy reviewed', evidence: 'Corrected invoice verified by an operator' },
  });
  record('S7 human-reviewed evidence resolution recorded', res.status === 200,
    `status=${res.status} newStatus=${res.json?.data?.status}`);
} else {
  record('S7 human-reviewed evidence resolution recorded', false,
    'no open evidence amount-mismatch case found');
}

fs.writeFileSync(`${OUT_DIR}/evidence-report.json`, JSON.stringify(results, null, 2));
const failed = results.filter((r) => !r.ok).length;
console.log(`\n=== EVIDENCE: ${results.length - failed}/${results.length} passed, ${failed} failed ===`);
