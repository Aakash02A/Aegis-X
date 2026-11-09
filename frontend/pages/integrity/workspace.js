/**
 * Aegis-X Payment Integrity console.
 *
 * One module drives every integrity page. Each page is a thin HTML shell that declares
 * `data-integrity-page` on <body>; this module renders the navigation, the page body and the
 * interactions, and reads everything it shows from the backend.
 *
 * Nothing here is hard-coded sample data: every number, row and badge comes from
 * `/api/integrity/...`. Historical simulated provenance remains visible and distinct from
 * provider-confirmed outcomes.
 */

import { API } from '../../core/api.js';
import { requireUser } from '../../core/guards.js';
import { createSidebar, wireMobileNavigation } from '../../components/app-shell.js';
import { enhanceResponsiveTables } from '../../components/responsive-table.js';

const state = {
  workspaceId: null,
  workspace: null,
  dashboard: null,
  page: document.body.dataset.integrityPage || 'overview',
  orderId: new URLSearchParams(location.search).get('order'),
  exceptionId: new URLSearchParams(location.search).get('exception'),
  statusFilter: [],
  strategy: 'PROPOSED_EVIDENCE_BASED'
};

// ------------------------------------------------------------------ utilities

const esc = (value) => String(value ?? '')
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&#39;');

/** Amounts are stored as integer minor units and are formatted as such, never as a float. */
function money(minor, currency) {
  if (minor === null || minor === undefined) return '—';
  const value = Number(minor);
  const sign = value < 0 ? '-' : '';
  const absolute = Math.abs(value);
  const units = Math.floor(absolute / 100);
  const cents = String(absolute % 100).padStart(2, '0');
  return `${sign}${units.toLocaleString('en-IN')}.${cents} ${esc(currency || '')}`.trim();
}

function shortId(value) {
  if (!value) return '—';
  const text = String(value);
  return text.length > 12 ? `${text.slice(0, 8)}…` : text;
}

function time(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return esc(value);
  return `${date.toLocaleString('en-GB', { hour12: false })}`;
}

/** Tone is never the only carrier of meaning: every badge also carries its status text. */
function badge(text, tone = 'neutral') {
  return `<span class="editorial-badge badge-${tone}">${esc(text)}</span>`;
}

const SIMULATED_TAG = '<span class="editorial-badge badge-warn" title="Historical simulated provenance; not provider-confirmed">SIMULATED</span>';

const TONES = {
  CAPTURED: 'ok', PAID: 'ok', PROCESSED: 'ok', RESOLVED: 'ok', SUCCEEDED: 'ok', OK: 'ok', EXECUTED: 'ok',
  FAILED: 'danger', REJECTED: 'danger', DISMISSED: 'danger', CRITICAL: 'danger', HIGH: 'danger',
  CREATED: 'neutral', ATTEMPTED: 'neutral', PENDING: 'warn', AUTHORIZED: 'warn', REQUESTED: 'warn',
  TIMEOUT_UNKNOWN: 'warn', IN_REVIEW: 'warn', OPEN: 'warn', DEGRADED: 'warn', UNAVAILABLE: 'warn',
  PARTIALLY_REFUNDED: 'warn', REFUNDED: 'neutral', REVERSED: 'warn', EXPIRED: 'neutral',
  CANCELLED: 'neutral', NOT_ENABLED: 'neutral', NOT_CONFIGURED: 'neutral', SIMULATED: 'warn',
  MEDIUM: 'warn', LOW: 'neutral', INFO: 'neutral', CERTAIN: 'ok', LIKELY: 'warn', UNCERTAIN: 'neutral'
};

const tone = (value) => TONES[String(value ?? '').toUpperCase()] || 'neutral';

function statusCell(value) {
  if (!value) return '—';
  return badge(value, tone(value));
}

function toast(message, kind = 'info') {
  let host = document.getElementById('integrity-toast');
  if (!host) {
    host = document.createElement('div');
    host.id = 'integrity-toast';
    host.className = 'fixed bottom-4 right-4 z-50 space-y-2 w-full max-w-md';
    document.body.appendChild(host);
  }
  const tone = kind === 'error' ? 'danger' : kind === 'success' ? 'ok' : 'neutral';
  const node = document.createElement('div');
  node.className = 'editorial-card p-3 text-sm flex items-start gap-2';
  node.innerHTML = `${badge(kind === 'error' ? 'ERROR' : 'INFO', tone)}<span class="text-ink-soft">${esc(message)}</span>`;
  host.appendChild(node);
  setTimeout(() => node.remove(), 7000);
}

function table(columns, rows, options = {}) {
  if (!rows.length) {
    return `<p class="text-sm text-ink-soft py-6">${esc(options.empty || 'No records.')}</p>`;
  }
  const head = columns.map((column) => `<th class="px-3 py-2 text-left text-[10px] uppercase tracking-widest text-ink-soft font-semibold">${esc(column.label)}</th>`).join('');
  const body = rows.map((row) => {
    const cells = columns.map((column) => {
      const rendered = column.render ? column.render(row) : esc(row[column.key] ?? '—');
      return `<td data-label="${esc(column.label)}" class="px-3 py-2 align-top text-ink">${rendered}</td>`;
    }).join('');
    const rowClass = options.onRow ? 'border-t border-ink/10 cursor-pointer hover:bg-ink/5' : 'border-t border-ink/10';
    const rowData = options.onRow ? `data-row="${esc(options.onRow(row))}"` : '';
    return `<tr class="${rowClass}" ${rowData}>${cells}</tr>`;
  }).join('');
  return `<div class="overflow-x-auto"><table data-responsive-table class="responsive-table w-full text-sm">
    <thead class="bg-paper-dim">${head}</thead><tbody>${body}</tbody></table></div>`;
}

function jsonBlock(label, raw) {
  if (!raw) return '';
  let pretty = raw;
  try {
    pretty = JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    pretty = String(raw);
  }
  return `<div class="mt-2">
    <div class="editorial-eyebrow mb-1">${esc(label)}</div>
    <pre class="text-xs bg-paper-dim border border-ink/10 rounded p-3 overflow-x-auto whitespace-pre-wrap">${esc(pretty)}</pre>
  </div>`;
}

function metric(label, value, note = '', accent = '') {
  return `<div class="editorial-card p-4">
    <div class="editorial-eyebrow mb-1">${esc(label)}</div>
    <div class="font-serif text-2xl font-medium ${accent || 'text-ink'}">${esc(value)}</div>
    ${note ? `<div class="text-xs text-ink-soft mt-1">${esc(note)}</div>` : ''}
  </div>`;
}

function section(title, eyebrow, body) {
  return `<section class="space-y-3">
    <div class="border-b border-ink/10 pb-2">
      <div class="editorial-eyebrow">${esc(eyebrow)}</div>
      <h2 class="font-serif text-xl font-medium text-ink">${esc(title)}</h2>
    </div>
    ${body}
  </section>`;
}

// --------------------------------------------------------------------- layout

function shell(title, subtitle, eyebrow) {
  return `<div class="flex-1 flex flex-col min-w-0" data-integrity-content>
    <header class="min-h-[4.5rem] py-2 bg-paper/90 backdrop-blur border-b border-ink/10 px-4 sm:px-6 flex items-center justify-between gap-3 sticky top-0 z-30">
      <div class="min-w-0">
        <div class="editorial-eyebrow">${esc(eyebrow)}</div>
        <h1 class="font-serif text-lg font-medium text-ink">${esc(title)}</h1>
        <p class="text-xs text-ink-soft truncate">${esc(subtitle)}</p>
      </div>
      <div id="integrity-header-actions" class="flex items-center gap-2"></div>
    </header>
    <main class="p-6 md:p-8 space-y-8 max-w-6xl w-full mx-auto">
      <div id="integrity-body"><p class="text-sm text-ink-soft">Loading…</p></div>
    </main>
  </div>`;
}

const PAGE_META = {
  overview: ['Integrity operations overview', 'Payment lifecycle health, unresolved exceptions and reconciliation state, read from MySQL.', 'Payment Integrity'],
  transactions: ['Transaction registry', 'Canonical order, payment, refund and transfer records with their provider-confirmed state and evidence history.', 'Module B'],
  webhooks: ['Webhook delivery history', 'Every delivery with its processing outcome. Repeats are expected: the provider delivers at least once.', 'Module C'],
  reconciliation: ['Reconciliation runs', 'Each run records the method used, what it compared and the findings it produced.', 'Modules E and F'],
  evidence: ['Evidence review queue', 'Supporting documents, what was read from them, what disagrees with the payment, and what is still missing. A legible document is not a genuine one, and this page does not claim otherwise.', 'Module I'],
  exceptions: ['Exception queue', 'Each case shows why it exists, the evidence it rests on and what is still unknown.', 'Module F'],
  recovery: ['Recovery recommendations', 'Recommended next actions with their safety classification. Money-moving actions always need a human.', 'Module G'],
  audit: ['Audit trail', 'Append-only record of events, decisions and provider interactions.', 'Module H'],
  settings: ['Integration settings', 'Workspace configuration and provider capability status.', 'Module A']
};

// ------------------------------------------------------------------ api calls

const base = () => `/integrity/workspaces/${state.workspaceId}`;

async function loadWorkspace() {
  const workspace = await API.request('/integrity/workspaces/default');
  state.workspace = workspace;
  state.workspaceId = workspace.id;
  const node = document.getElementById('integrity-workspace');
  if (node) node.innerHTML = `${esc(workspace.name)}<br />${badge(workspace.environment, 'neutral')} ${badge(workspace.providerMode, tone(workspace.providerMode))}`;
  if (document.getElementById('integrity-provider')) {
    document.getElementById('integrity-provider').innerHTML = workspace.providerMode === 'SIMULATED'
      ? `${badge('HISTORICAL SIMULATED DATA', 'warn')}`
      : `${badge('RAZORPAY TEST MODE', 'ok')}`;
  }
  return workspace;
}

const api = {
  dashboard: () => API.request(`${base()}/dashboard`),
  orders: () => API.request(`${base()}/orders`),
  order: (id) => API.request(`${base()}/orders/${id}`),
  createOrder: (body) => API.request(`${base()}/orders`, { method: 'POST', body: JSON.stringify(body) }),
  payments: () => API.request(`${base()}/payments`),
  refunds: () => API.request(`${base()}/refunds`),
  transfers: () => API.request(`${base()}/transfers`),
  webhooks: () => API.request(`${base()}/webhooks`),
  runs: () => API.request(`${base()}/reconciliation/runs`),
  runFindings: (runId) => API.request(`${base()}/reconciliation/runs/${runId}/findings`),
  recentFindings: (limit = 50) => API.request(`${base()}/reconciliation/findings?limit=${limit}`),
  methods: () => API.request(`${base()}/reconciliation/methods`),
  run: (body) => API.request(`${base()}/reconciliation/runs`, { method: 'POST', body: JSON.stringify(body) }),
  exceptionQueue: (statuses) => API.request(`${base()}/exceptions${statuses.length ? `?${statuses.map((s) => `status=${s}`).join('&')}` : ''}`),
  exception: (id) => API.request(`${base()}/exceptions/${id}`),
  openException: (id, note) => API.request(`${base()}/exceptions/${id}/open`, { method: 'POST', body: JSON.stringify({ note }) }),
  addNote: (id, note) => API.request(`${base()}/exceptions/${id}/notes`, { method: 'POST', body: JSON.stringify({ note }) }),
  resolveException: (id, summary, evidence) => API.request(`${base()}/exceptions/${id}/resolve`, { method: 'POST', body: JSON.stringify({ summary, evidence }) }),
  dismissException: (id, reason) => API.request(`${base()}/exceptions/${id}/dismiss`, { method: 'POST', body: JSON.stringify({ reason }) }),
  recommendations: () => API.request(`${base()}/recommendations`),
  decide: (id, action, rationale) => API.request(`${base()}/recommendations/${id}/${action}`, { method: 'POST', body: JSON.stringify({ rationale }) }),
  audit: (page = 0, size = 50) => API.request(`${base()}/audit?page=${page}&size=${size}`),
  connectivity: () => API.request(`${base()}/connectivity-check`, { method: 'POST' }),
  setThreshold: (seconds) => API.request(`${base()}/staleness-threshold`, { method: 'PATCH', body: JSON.stringify({ stalenessThresholdSeconds: seconds }) }),
  evidenceSummary: (orderId) => API.request(`${base()}/evidence/summary?orderId=${orderId}`),
  evidenceReadiness: () => API.request(`${base()}/evidence/readiness`),
  evidenceModel: () => API.request(`${base()}/evidence/model`),
  evidenceRequirements: (orderId) => API.request(`${base()}/evidence/requirements?orderId=${orderId}`),
  evidenceDocuments: (orderId, includeSuperseded = false) => API.request(
    `${base()}/evidence/documents?orderId=${orderId}${includeSuperseded ? '&includeSuperseded=true' : ''}`),
  evidenceExtractions: (documentId) => API.request(`${base()}/evidence/documents/${documentId}/extractions`),
  evidenceExtract: (documentId) => API.request(`${base()}/evidence/documents/${documentId}/extract`, { method: 'POST' }),
  evidenceCorrect: (documentId, field, value) => API.request(
    `${base()}/evidence/documents/${documentId}/corrections`,
    { method: 'POST', body: JSON.stringify({ field, value }) }),
  evidenceUpload: (orderId, documentType, file) => {
    const form = new FormData();
    form.append('file', file);
    form.append('orderId', orderId);
    form.append('documentType', documentType);
    return API.request(`${base()}/evidence/documents`, { method: 'POST', body: form });
  },
  evidenceAssess: (orderId) => API.request(
    `${base()}/evidence/assess${orderId ? `?orderId=${orderId}` : ''}`, { method: 'POST' }),
  benchmarkMethod: () => API.request('/integrity/benchmark/method'),
  runBenchmark: (seed) => API.request(`/integrity/benchmark/run${seed ? `?seed=${seed}` : ''}`, { method: 'POST' })
};

// ----------------------------------------------------------------- overview

async function renderOverview(root) {
  const summary = await api.dashboard();
  state.dashboard = summary;

  const providerTone = summary.providerReachable === false ? 'warn' : 'ok';
  const cards = [
    metric('Orders', summary.totalOrders, 'internal registry entries'),
    metric('Payments', summary.totalPayments, `${summary.confirmedSuccessfulPayments} provider-confirmed successful`),
    metric('Failed payments', summary.failedPayments, 'provider-confirmed failures', summary.failedPayments > 0 ? 'text-rust' : ''),
    metric('Pending payments', summary.pendingPayments, 'not yet a provider-confirmed outcome'),
    metric('Unresolved exceptions', summary.unresolvedExceptions, `${summary.openExceptions} open, ${summary.inReviewExceptions} in review`, summary.unresolvedExceptions > 0 ? 'text-rust' : ''),
    metric('Reconciliation mismatches', summary.reconciliationMismatches, `${summary.latestRunFindings} findings in the latest run`),
    metric('Duplicate deliveries', summary.duplicateDeliveries, `of ${summary.totalWebhookDeliveries} deliveries`),
    metric('Financial execution', summary.financialExecutionEnabled ? 'ENABLED' : 'DISABLED', 'money-moving actions require a human', summary.financialExecutionEnabled ? 'text-rust' : 'text-olive-deep')
  ].join('');

  const capability = `<div class="editorial-card p-4 space-y-2 text-sm">
    <div class="flex items-center gap-2">${statusCell(summary.providerConfigured)} <span class="text-ink-soft">provider configuration</span></div>
    <div class="flex items-center gap-2">${statusCell(summary.transferCapability)} <span class="text-ink-soft">Route transfer capability</span></div>
    <div class="flex items-center gap-2">${summary.simulated ? SIMULATED_TAG : badge('RAZORPAY TEST MODE', 'ok')} <span class="text-ink-soft">provider mode: ${esc(summary.providerMode)}</span></div>
    <div class="flex items-center gap-2">${statusCell(summary.lastReconciliationProviderReachable ? 'OK' : 'UNAVAILABLE')}
      <span class="text-ink-soft">provider reachable in the last run (${esc(summary.lastReconciliationStrategy || 'none')} at ${esc(time(summary.lastReconciliationAt))})</span></div>
  </div>`;

  const byType = Object.entries(summary.exceptionsByType || {});
  const bySeverity = Object.entries(summary.exceptionsBySeverity || {});
  const breakdown = `<div class="grid md:grid-cols-2 gap-4">
    ${table([{ label: 'Finding type', key: 'type' }, { label: 'Cases', render: (row) => esc(row.count) }],
      byType.map(([type, count]) => ({ type, count })), { empty: 'No exceptions have been detected in this workspace.' })}
    ${table([{ label: 'Severity', render: (row) => statusCell(row.severity) }, { label: 'Cases', render: (row) => esc(row.count) }],
      bySeverity.map(([severity, count]) => ({ severity, count })), { empty: 'No exceptions have been detected in this workspace.' })}
  </div>`;

  const activity = table([
    { label: 'Event', render: (row) => esc(row.eventType) },
    { label: 'Category', render: (row) => esc(row.category) },
    { label: 'Actor', render: (row) => esc(row.actorLabel || '—') },
    { label: 'When', render: (row) => esc(time(row.occurredAt)) },
    { label: 'Origin', render: (row) => (row.simulated ? SIMULATED_TAG : '—') }
  ], summary.recentActivity || [], { empty: 'No activity recorded yet.'});

  const recommendations = `<div class="grid sm:grid-cols-3 gap-4">
    ${metric('Proposed actions', summary.recommendationsProposed)}
    ${metric('Requiring a human', summary.recommendationsRequiringHumanApproval)}
    ${metric('Money-moving', summary.recommendationsFinancial, 'never executed automatically')}
  </div>`;

  root.innerHTML = `<div class="space-y-8">
    <div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">${cards}</div>
    ${section('Provider and safety status', 'Read from persisted configuration', capability)}
    ${summary.unresolvedExceptions > 0 ? `<div class="editorial-card p-4 border-l-4 border-rust"><div class="text-sm text-ink">${esc(summary.unresolvedExceptions)} unresolved exception(s) need attention. Open the <a class="underline" href="integrity-exceptions.html">exception queue</a>.</div></div>` : ''}
    ${section('Exceptions by classification', 'Module F', breakdown)}
    ${section('Recovery recommendations', 'Module G', recommendations)}
    ${section('Recent activity', 'Module H', activity)}
  </div>`;
}

// ------------------------------------------------------------- transactions

async function renderTransactions(root) {
  const [orders, payments, refunds, transfers] = await Promise.all([api.orders(), api.payments(), api.refunds(), api.transfers()]);

  const createForm = `<form id="create-order" class="editorial-card p-4 grid sm:grid-cols-4 gap-3 items-end">
    <label class="text-xs text-ink-soft">Amount (minor units)
      <input name="amountMinor" type="number" min="1" value="150000" required class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink" /></label>
    <label class="text-xs text-ink-soft">Currency
      <input name="currency" maxlength="3" value="INR" required class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink" /></label>
    <label class="text-xs text-ink-soft">Description
      <input name="description" value="Test Mode order" class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink" /></label>
    <button type="submit" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Create order</button>
  </form>`;

  const orderRows = table([
    { label: 'Internal reference', render: (row) => `<a class="underline" href="integrity-transactions.html?order=${esc(row.id)}">${esc(row.internalReference)}</a>` },
    { label: 'Provider order', render: (row) => esc(row.providerOrderId || 'not created') },
    { label: 'Expected amount', render: (row) => esc(money(row.expectedAmountMinor, row.currency)) },
    { label: 'Internal state', render: (row) => statusCell(row.state) },
    { label: 'Provider-confirmed state', render: (row) => (row.providerState ? statusCell(row.providerState) : '—') },
    { label: 'Created', render: (row) => esc(time(row.createdAt)) },
    { label: 'Origin', render: (row) => (row.simulated ? SIMULATED_TAG : '—') }
  ], orders, { empty: 'No orders yet. Create one above.' });

  const paymentRows = table([
    { label: 'Provider payment', render: (row) => esc(row.providerPaymentId || '—') },
    { label: 'Amount', render: (row) => esc(money(row.amountMinor, row.currency)) },
    { label: 'Internal state', render: (row) => statusCell(row.state) },
    { label: 'Provider-confirmed state', render: (row) => (row.providerState ? statusCell(row.providerState) : '—') },
    { label: 'Evidence rank', render: (row) => esc(row.stateEvidenceRank ?? '—') },
    { label: 'Last observed', render: (row) => esc(time(row.lastObservedAt)) }
  ], payments, { empty: 'No payments recorded.' });

  const refundRows = table([
    { label: 'Provider refund', render: (row) => esc(row.providerRefundId || '—') },
    { label: 'Amount', render: (row) => esc(money(row.amountMinor, row.currency)) },
    { label: 'Internal state', render: (row) => statusCell(row.state) },
    { label: 'Provider-confirmed state', render: (row) => (row.providerState ? statusCell(row.providerState) : '—') },
    { label: 'Last observed', render: (row) => esc(time(row.lastObservedAt)) }
  ], refunds, { empty: 'No refunds recorded.' });

  const transferRows = table([
    { label: 'Provider transfer', render: (row) => esc(row.providerTransferId || '—') },
    { label: 'Linked account', render: (row) => esc(row.linkedAccountId || '—') },
    { label: 'Amount', render: (row) => esc(money(row.amountMinor, row.currency)) },
    { label: 'Internal state', render: (row) => statusCell(row.state) },
    { label: 'Provider-confirmed state', render: (row) => (row.providerState ? statusCell(row.providerState) : '—') }
  ], transfers, { empty: 'No transfers recorded. Route must be enabled for an account before transfers appear.' });

  root.innerHTML = `<div class="space-y-8">
    ${section('Create an order', 'Module B', createForm)}
    ${section('Orders', `${orders.length} record(s)`, orderRows)}
    ${section('Payments', `${payments.length} record(s)`, paymentRows)}
    ${section('Refunds', `${refunds.length} record(s)`, refundRows)}
    ${section('Transfers', `${transfers.length} record(s)`, transferRows)}
    ${state.orderId ? '<div id="order-detail" class="space-y-4"></div>' : ''}
  </div>`;

  document.getElementById('create-order').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    try {
      const created = await api.createOrder({
        amountMinor: Number(form.get('amountMinor')),
        currency: String(form.get('currency')).toUpperCase(),
        description: String(form.get('description'))
      });
      toast(`Order ${created.internalReference} created. ${created.note || ''}`, 'success');
      await renderTransactions(root);
    } catch (error) {
      toast(error.message, 'error');
    }
  });

  if (state.orderId) await renderOrderDetail();
}

async function renderOrderDetail() {
  const host = document.getElementById('order-detail');
  const detail = await api.order(state.orderId);
  const { order, observations, transitions } = detail;

  const observationRows = table([
    { label: 'Resource', render: (row) => esc(row.resourceType) },
    { label: 'Provider resource', render: (row) => esc(row.providerResourceId) },
    { label: 'Evidence source', render: (row) => badge(row.source, row.evidenceRank >= 4 ? 'ok' : row.evidenceRank >= 3 ? 'neutral' : 'warn') },
    { label: 'Observed state', render: (row) => statusCell(row.observedState) },
    { label: 'Observed amount', render: (row) => esc(money(row.observedAmountMinor, row.observedCurrency)) },
    { label: 'Observed at', render: (row) => esc(time(row.observedAt)) },
    { label: 'Origin', render: (row) => (row.simulated ? SIMULATED_TAG : '—') }
  ], observations || [], { empty: 'No observations recorded.' });

  const transitionRows = table([
    { label: 'Entity', render: (row) => esc(row.entityType) },
    { label: 'From', render: (row) => statusCell(row.fromState) },
    { label: 'To', render: (row) => statusCell(row.toState) },
    { label: 'Evidence', render: (row) => badge(row.evidenceSource || '—', row.providerConfirmed ? 'ok' : 'warn') },
    { label: 'Outcome', render: (row) => (row.conflict ? badge('CONFLICT RECORDED', 'warn') : badge('APPLIED', 'ok')) },
    { label: 'Actor', render: (row) => esc(row.actor || '—') },
    { label: 'Reason', render: (row) => esc(row.reason || '—') },
    { label: 'When', render: (row) => esc(time(row.occurredAt)) }
  ], transitions || [], { empty: 'No state transitions recorded.' });

  host.innerHTML = `${section(`Order ${order.internalReference}`, 'Module B transaction detail', `
    <div class="grid sm:grid-cols-3 gap-4">
      ${metric('Expected amount', money(order.expectedAmountMinor, order.currency))}
      ${metric('Internal state', order.state)}

      ${metric('Provider-confirmed state', order.providerState || 'not confirmed')}
    </div>
    <p class="text-xs text-ink-soft">The order's internal state is what Aegis-X recorded. The provider-confirmed state is what the payment provider last reported. They are shown separately because they can differ while a state change is in flight.</p>
    ${jsonBlock('Correlation identifiers', JSON.stringify({ internalOrderId: order.id, providerOrderId: order.providerOrderId, correlationId: order.correlationId }))}
  `)}
  <div id="order-evidence" class="space-y-4"><p class="text-sm text-ink-soft">Loading evidence status…</p></div>
  ${section('Evidence: provider observations', `${(observations || []).length} observation(s)`, observationRows)}
  ${section('Evidence: state transitions', `${(transitions || []).length} transition(s)`, transitionRows)}`;

  // The four statuses and the documents are loaded after the payment detail, so a slow evidence
  // query never hides the provider-confirmed facts that are already available.
  const evidenceHost = host.querySelector('#order-evidence');
  try {
    await mountEvidencePanel(evidenceHost, state.orderId);
  } catch (error) {
    evidenceHost.innerHTML = `<div class="editorial-card p-4 text-sm text-rust">Evidence status could not be loaded: ${esc(error.message)}</div>`;
  }

}

// ----------------------------------------------------------------- webhooks

async function renderWebhooks(root) {
  const history = await api.webhooks();
  const deliveriesByEvent = history.deliveriesByEvent || {};

  const eventRows = table([
    { label: 'Event type', render: (row) => esc(row.eventType) },
    { label: 'Provider event id', render: (row) => esc(row.providerEventId) },
    { label: 'Resource', render: (row) => esc(row.providerResourceId || 'unmatched') },
    { label: 'Signature', render: (row) => (row.signatureVerified ? badge('VERIFIED', 'ok') : badge('UNVERIFIED', 'danger')) },
    { label: 'Processing', render: (row) => statusCell(row.processingState) },
    { label: 'Deliveries', render: (row) => esc(deliveriesByEvent[row.providerEventId] ?? 0) },
    { label: 'Provider time', render: (row) => esc(time(row.providerCreatedAt)) },
    { label: 'Received', render: (row) => esc(time(row.receivedAt)) },
    { label: 'Origin', render: (row) => (row.simulated ? SIMULATED_TAG : '—') },
    { label: 'Error', render: (row) => esc(row.errorMessage || '—') }
  ], history.events || [], { empty: 'No webhook deliveries have been received for this workspace.' });

  const deliveryRows = table([
    { label: 'Provider event id', render: (row) => esc(row.providerEventId) },
    { label: 'Event type', render: (row) => esc(row.eventType) },
    { label: 'Delivery', render: (row) => (row.firstDelivery ? badge('FIRST', 'ok') : badge('REPEAT (disregarded)', 'warn')) },
    { label: 'Signature', render: (row) => (row.signatureVerified ? badge('VERIFIED', 'ok') : badge('UNVERIFIED', 'danger')) },
    { label: 'Body hash', render: (row) => `<span title="${esc(row.bodySha256 || '')}">${esc(shortId(row.bodySha256))}</span>` },
    { label: 'Received', render: (row) => esc(time(row.receivedAt)) }
  ], history.deliveries || [], { empty: 'No deliveries recorded.' });

  root.innerHTML = `<div class="space-y-8">
    <div class="grid sm:grid-cols-3 gap-4">
      ${metric('Events stored', (history.events || []).length, 'one row per distinct provider event id')}
      ${metric('Deliveries', (history.deliveries || []).length, 'at-least-once delivery, so repeats are normal')}
      ${metric('Repeat deliveries', history.duplicateDeliveryCount, 'disregarded after the first', history.duplicateDeliveryCount > 0 ? 'text-rust' : '')}
    </div>
    <div class="editorial-card p-4 text-sm text-ink-soft">
      Signature verification uses HMAC-SHA256 over the exact raw request body. A duplicate delivery is acknowledged, never reprocessed.
      Endpoint: <code class="break-all">${esc(history.endpointTemplate || '/api/integrity/webhooks/{workspaceId}')}</code>
      ${history.failedEventCount > 0 ? `<div class="mt-2 text-rust">${esc(history.failedEventCount)} event(s) failed processing and need investigation.</div>` : ''}
    </div>
    ${section('Events', 'persisted provider events', eventRows)}
    ${section('Deliveries', 'every HTTP delivery, including repeats', deliveryRows)}
  </div>`;
}

// ---------------------------------------------------------- reconciliation

async function renderReconciliation(root) {
  const [runs, methods, recent] = await Promise.all([api.runs(), api.methods(), api.recentFindings(100)]);

  const methodOptions = methods.map((method) => {
    const value = method.strategy || method.name || method.method;
    const label = method.displayName || method.description || value;
    return `<option value="${esc(value)}" ${value === state.strategy ? 'selected' : ''}>${esc(label)}</option>`;
  }).join('');

  const runForm = `<div class="editorial-card p-4 flex flex-wrap gap-3 items-end">
    <label class="min-w-0 flex-1 text-xs text-ink-soft">Decision procedure
      <select id="strategy" class="mt-1 block w-full min-w-0 max-w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm">${methodOptions}</select></label>
    <button id="run-reconciliation" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Run reconciliation</button>
    <div id="run-result" class="text-xs text-ink-soft"></div>
  </div>`;

  const runRows = table([
    { label: 'Started', render: (row) => esc(time(row.startedAt)) },
    { label: 'Procedure', render: (row) => badge(row.strategy, row.strategy === 'PROPOSED_EVIDENCE_BASED' ? 'ok' : 'neutral') },
    { label: 'Trigger', render: (row) => esc(row.trigger) },
    { label: 'Compared', render: (row) => esc(`${row.ordersChecked} orders, ${row.paymentsChecked} payments, ${row.refundsChecked} refunds, ${row.transfersChecked} transfers`) },
    { label: 'Findings', render: (row) => esc(row.findingsCount) },
    { label: 'New cases', render: (row) => esc(row.newExceptions) },
    { label: 'Reopened', render: (row) => (row.reopenedExceptions > 0 ? badge(row.reopenedExceptions, 'warn') : esc(row.reopenedExceptions)) },
    { label: 'Provider reachable', render: (row) => (row.providerReachable ? badge('YES', 'ok') : badge('NO — stored evidence only', 'warn')) },
    { label: 'Notes', render: (row) => esc(row.notes || '—') }
  ], runs, { empty: 'No reconciliation run has been executed yet.' });

  const findingRows = table([
    { label: 'Detected', render: (row) => esc(time(row.createdAt)) },
    { label: 'Finding', render: (row) => esc(row.findingType) },
    { label: 'Severity', render: (row) => statusCell(row.severity) },
    { label: 'Certainty', render: (row) => statusCell(row.certainty) },
    { label: 'Summary', render: (row) => esc(row.summary) },
    { label: 'Evidence', render: (row) => `<span title="${esc(row.evidenceJson || '')}">${esc(shortId(row.evidenceJson))}</span>` }
  ], recent, { empty: 'No findings recorded yet.' });

  root.innerHTML = `<div class="space-y-8">
    ${section('Run a reconciliation', 'Module E', runForm)}
    ${section('Runs', `${runs.length} run(s)`, runRows)}
    ${section('Recent findings', `${recent.length} finding(s)`, findingRows)}
  </div>`;

  document.getElementById('run-reconciliation').addEventListener('click', async () => {
    const strategy = document.getElementById('strategy').value;
    state.strategy = strategy;
    try {
      const result = await api.run({ strategy, refreshProviderState: true });
      document.getElementById('run-result').textContent =
        `${result.findingsCount} finding(s), ${result.newExceptions} new case(s), ${result.reopenedExceptions} reopened.`;
      toast('Reconciliation run completed', 'success');
      await renderReconciliation(root);
    } catch (error) {
      toast(error.message, 'error');
    }
  });
}

// ------------------------------------------------------------- exceptions

async function renderExceptions(root) {
  const filters = [
    { value: 'OPEN', label: 'Open' },
    { value: 'IN_REVIEW', label: 'In review' },
    { value: 'RESOLVED', label: 'Resolved' },
    { value: 'DISMISSED', label: 'Dismissed' }
  ];
  const filterBar = `<div class="flex flex-wrap gap-2">
    <button data-status="" class="px-3 py-1.5 rounded-full text-xs font-medium ${state.statusFilter.length === 0 ? 'bg-ink text-paper' : 'border border-ink/30 text-ink'}">All</button>
    ${filters.map((filter) => `<button data-status="${filter.value}" class="px-3 py-1.5 rounded-full text-xs font-medium ${state.statusFilter.includes(filter.value) ? 'bg-ink text-paper' : 'border border-ink/30 text-ink'}">${esc(filter.label)}</button>`).join('')}
  </div>`;

  const queue = await api.exceptionQueue(state.statusFilter);
  const rows = queue.content || [];

  const queueTable = table([
    { label: 'Detected', render: (row) => esc(time(row.createdAt)) },
    { label: 'Finding', render: (row) => esc(row.findingType) },
    { label: 'Title', render: (row) => `<a class="underline" href="integrity-exceptions.html?exception=${esc(row.id)}">${esc(row.title)}</a>` },
    { label: 'Severity', render: (row) => statusCell(row.severity) },
    { label: 'Certainty', render: (row) => statusCell(row.certainty) },
    { label: 'Status', render: (row) => statusCell(row.status) },
    { label: 'Detections', render: (row) => esc(row.detectionCount) },
    { label: 'Provider evidence', render: (row) => (row.providerUnreachable ? badge('UNCONFIRMED', 'warn') : badge('CONFIRMED', 'ok')) },
    { label: 'Resolution', render: (row) => esc(row.resolutionSummary || '—') }
  ], rows, { empty: 'No exception matches the current filter. That is a good sign, not a missing feature.' });

  root.innerHTML = `<div class="space-y-8">
    ${section('Queue', `${queue.totalElements ?? rows.length} case(s)`, `${filterBar}<div class="mt-4">${queueTable}</div>`)}
    ${state.exceptionId ? '<div id="exception-detail" class="space-y-4"></div>' : ''}
  </div>`;

  document.querySelectorAll('[data-status]').forEach((button) => button.addEventListener('click', async () => {
    const value = button.dataset.status;
    state.statusFilter = value ? [value] : [];
    await renderExceptions(root);
  }));

  if (state.exceptionId) await renderExceptionDetail();
}

async function renderExceptionDetail() {
  const host = document.getElementById('exception-detail');
  const detail = await api.exception(state.exceptionId);
  const item = detail.exceptionCase || detail.exception || detail;

  const recommendations = (detail.recommendations || []).map((recommendation) => `
    <div class="editorial-card p-4 space-y-2">
      <div class="flex flex-wrap items-center gap-2">
        ${badge(recommendation.type, 'neutral')}
        ${badge(recommendation.category, recommendation.category === 'FINANCIAL' ? 'danger' : recommendation.category === 'HUMAN_APPROVAL_REQUIRED' ? 'warn' : 'ok')}
        ${statusCell(recommendation.status)}
        ${recommendation.requiresHumanApproval ? badge('HUMAN APPROVAL REQUIRED', 'warn') : badge('NO APPROVAL NEEDED', 'neutral')}
      </div>
      <p class="text-sm text-ink">${esc(recommendation.rationale)}</p>
      ${recommendation.riskNote ? `<p class="text-xs text-rust">Risk: ${esc(recommendation.riskNote)}</p>` : ''}
      ${recommendation.executionOutcome ? `<p class="text-xs text-ink-soft">Outcome: ${esc(recommendation.executionOutcome)}</p>` : ''}
      <div class="flex flex-wrap gap-2">
        <button data-decide="approve:${esc(recommendation.id)}" class="px-3 py-1.5 rounded-full bg-ink text-paper text-xs font-medium">Approve</button>
        <button data-decide="reject:${esc(recommendation.id)}" class="px-3 py-1.5 rounded-full border border-ink/30 text-xs font-medium text-ink">Reject</button>
        <button data-decide="execute:${esc(recommendation.id)}" class="px-3 py-1.5 rounded-full border border-ink/30 text-xs font-medium text-ink">Execute (read-only only)</button>
      </div>
    </div>`).join('') || '<p class="text-sm text-ink-soft">No recommendation attached to this case.</p>';

  const reviewActions = table([
    { label: 'When', render: (row) => esc(time(row.createdAt)) },
    { label: 'Action', render: (row) => badge(row.actionType, row.actionType === 'AUTO_REOPEN' ? 'warn' : 'neutral') },
    { label: 'Actor role', render: (row) => esc(row.actorRole || 'SYSTEM') },
    { label: 'Rationale', render: (row) => esc(row.rationale || '—') },
    { label: 'Resulting status', render: (row) => (row.resultingExceptionStatus ? statusCell(row.resultingExceptionStatus) : '—') }
  ], detail.reviewActions || [], { empty: 'No human decision recorded yet.' });

  const findings = table([
    { label: 'Detected', render: (row) => esc(time(row.createdAt)) },
    { label: 'Severity', render: (row) => statusCell(row.severity) },
    { label: 'Cert.nty', render: (row) => statusCell(row.certainty) },
    { label: 'Summary', render: (row) => esc(row.summary) }
  ], detail.findings || [], { empty: 'No finding history.' });

  host.innerHTML = `${section(`Case: ${item.title}`, 'Exception investigation', `
    <div class="grid sm:grid-cols-3 gap-4">
      ${metric('Severity', item.severity)}
      ${metric('Certainty', item.certainty || 'not graded')}
      ${metric('Status', item.status)}
    </div>
    <div class="editorial-card p-4 space-y-2">
      <div class="editorial-eyebrow">Why this exception exists</div>
      <p class="text-sm text-ink">${esc(item.diagnosis)}</p>
      <div class="editorial-eyebrow mt-3">Recommended next action</div>
      <p class="text-sm text-ink">${esc(item.recommendedAction || '—')}</p>
      ${item.providerUnreachable ? `<p class="text-xs text-rust mt-2">The provider was unreachable during the run that detected this, so provider-side values are carried forward rather than freshly confirmed.</p>` : ''}
      ${jsonBlock('Supporting evidence', item.supportingEvidenceJson)}
      ${jsonBlock('Evidence still missing', item.missingEvidenceJson)}
      ${item.resolutionSummary ? `<div class="editorial-eyebrow mt-3">Recorded resolution</div><p class="text-sm text-ink">${esc(item.resolutionSummary)}</p>` : ''}
    </div>
    <div class="editorial-card p-4 space-y-3">
      <div class="editorial-eyebrow">Human review</div>
      <div class="grid gap-2">
        <textarea id="review-note" rows="2" placeholder="Investigation note" class="w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm"></textarea>
        <div class="flex flex-wrap gap-2">
          <button data-act="open" class="px-3 py-1.5 rounded-full bg-ink text-paper text-xs font-medium">Open case</button>
          <button data-act="note" class="px-3 py-1.5 rounded-full border border-ink/30 text-xs font-medium text-ink">Add note</button>
          <button data-act="resolve" class="px-3 py-1.5 rounded-full bg-olive-deep text-paper text-xs font-medium">Resolve</button>
          <button data-act="dismiss" class="px-3 py-1.5 rounded-full border border-ink/30 text-xs font-medium text-ink">Dismiss</button>
        </div>
        <p class="text-[11px] text-ink-soft">A resolution is provisional: the next reconciliation run that still sees this discrepancy will reopen the case automatically.</p>
      </div>
    </div>
  `)}
  ${section('Recommendations', 'what the engine suggests next', recommendations)}
  ${section('Finding history', `${(detail.findings || []).length} finding(s)`, findings)}
  ${section('Review actions', `${(detail.reviewActions || []).length} decision(s)`, reviewActions)}`;

  host.querySelectorAll('[data-decide]').forEach((button) => button.addEventListener('click', async () => {
    const [action, id] = button.dataset.decide.split(':');
    const rationale = document.getElementById('review-note')?.value || null;
    try {
      const result = await api.decide(id, action, rationale);
      toast(`${action} accepted: ${result.status || ''} ${result.detail || ''}`, 'success');
      await renderExceptionDetail();
    } catch (error) {
      toast(error.message, 'error');
    }
  }));

  host.querySelectorAll('[data-act]').forEach((button) => button.addEventListener('click', async () => {
    const note = document.getElementById('review-note')?.value || '';
    try {
      if (button.dataset.act === 'open') await api.openException(state.exceptionId, note || null);
      else if (button.dataset.act === 'note') await api.addNote(state.exceptionId, note);
      else if (button.dataset.act === 'resolve') await api.resolveException(state.exceptionId, note, null);
      else await api.dismissException(state.exceptionId, note);
      toast('Decision recorded', 'success');
      await renderExceptionDetail();
    } catch (error) {
      toast(error.message, 'error');
    }
  }));
}

// --------------------------------------------------------------- recovery

async function renderRecovery(root) {
  const recommendations = await api.recommendations();
  const financial = recommendations.filter((item) => item.category === 'FINANCIAL');

  root.innerHTML = `<div class="space-y-8">
    <div class="grid sm:grid-cols-4 gap-4">
      ${metric('Proposed', recommendations.filter((item) => item.status === 'PROPOSED').length)}
      ${metric('Awaiting a human', recommendations.filter((item) => item.requiresHumanApproval && item.status === 'PROPOSED').length)}
      ${metric('Money-moving', financial.length, 'never executed by the engine', financial.length > 0 ? 'text-rust' : '')}
      ${metric('Executed', recommendations.filter((item) => item.status === 'EXECUTED').length, 'read-only actions only')}
    </div>
    ${section('All recommendations', `${recommendations.length} record(s)`, table([
      { label: 'Created', render: (row) => esc(time(row.createdAt)) },
      { label: 'Type', render: (row) => badge(row.type, 'neutral') },
      { label: 'Class', render: (row) => badge(row.category, row.category === 'FINANCIAL' ? 'danger' : row.category === 'HUMAN_APPROVAL_REQUIRED' ? 'warn' : 'ok') },
      { label: 'Status', render: (row) => statusCell(row.status) },
      { label: 'Rationale', render: (row) => esc(row.rationale) },
      { label: 'Case', render: (row) => `<a class="underline" href="integrity-exceptions.html?exception=${esc(row.exceptionId)}">${esc(shortId(row.exceptionId))}</a>` },
      { label: 'Outcome', render: (row) => esc(row.executionOutcome || '—') }
    ], recommendations, { empty: 'No recommendations yet. Run a reconciliation to generate some.' }))}
    <div class="editorial-card p-4 text-sm text-ink-soft">
      Safety boundary: a money-moving recommendation (${badge('FINANCIAL', 'danger')}) is recorded and can be approved, but this prototype refuses to execute it, because the engine must never move money on its own initiative. Only ${badge('READ_ONLY', 'ok')} actions can run unattended, and they only re-read provider state.
    </div>
  </div>`;
}

// ------------------------------------------------------------------ audit

async function renderAudit(root) {
  const page = await api.audit(0, 100);
  const events = page.content || [];

  const rows = table([
    { label: 'When', render: (row) => esc(time(row.occurredAt)) },
    { label: 'Event', render: (row) => esc(row.eventType) },
    { label: 'Module', render: (row) => esc(row.module || row.category || '—') },
    { label: 'Actor', render: (row) => esc(row.actorLabel || row.actorId || row.actorType || '—') },
    { label: 'Entity', render: (row) => esc(row.entityLabel || shortId(row.entityId) || '—') },
    { label: 'Origin', render: (row) => (row.simulated ? SIMULATED_TAG : badge('REAL TEST-MODE', 'ok')) },
    { label: 'Detail', render: (row) => `<span title="${esc(row.detailJson || '')}">${esc(shortId(row.detailJson))}</span>` }
  ], events, { empty: 'No audit events recorded yet.' });

  root.innerHTML = `<div class="space-y-8">
    <div class="editorial-card p-4 text-sm text-ink-soft">
      The audit trail is append-only: every event, state transition, reconciliation run, human decision and provider interaction is recorded with the evidence available at the time. It records what happened; it is not a cryptographic proof that a provider transaction occurred.
    </div>
    ${section('Events', `${page.totalElements ?? events.length} record(s)`, rows)}
  </div>`;
}

// --------------------------------------------------------------- settings

async function renderSettings(root) {
  const workspace = state.workspace;
  const method = await api.benchmarkMethod().catch(() => null);

  root.innerHTML = `<div class="space-y-8">
    ${section('Workspace', 'Module A configuration', `
      <div class="editorial-card p-4 grid sm:grid-cols-2 gap-3 text-sm">
        <div><span class="editorial-eyebrow block">Name</span>${esc(workspace.name)}</div>
        <div><span class="editorial-eyebrow block">Environment</span>${statusCell(workspace.environment || 'TEST')}</div>
        <div><span class="editorial-eyebrow block">Provider mode</span>${esc(workspace.providerMode)}</div>
        <div><span class="editorial-eyebrow block">Default currency</span>${esc(workspace.defaultCurrency)}</div>
        <div><span class="editorial-eyebrow block">Staleness threshold</span>${esc(workspace.stalenessThresholdSeconds)} seconds</div>
        <div><span class="editorial-eyebrow block">Live payment execution</span>${badge('NOT IMPLEMENTED', 'neutral')}</div>
      </div>
      <div class="editorial-card p-4 flex flex-wrap items-end gap-3 mt-3">
        <label class="text-xs text-ink-soft">Staleness threshold (seconds)
          <input id="threshold" type="number" min="60" value="${esc(workspace.stalenessThresholdSeconds)}" class="mt-1 block border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm" /></label>
        <button id="save-threshold" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Save threshold</button>
      </div>
    `)}
    ${section('Provider credentials', 'secrets are never returned to the browser', `
      <div class="editorial-card p-4 space-y-3 text-sm">
        <p class="text-ink-soft">Credentials are read from environment variables on the server. A configuration check reports whether they are present without ever returning their values, and no Razorpay secret is ever embedded in front-end JavaScript.</p>
        <button id="connectivity" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Check connectivity (read-only)</button>
        <div id="connectivity-result" class="text-sm text-ink-soft"></div>
      </div>
    `)}
    ${section('Research benchmark', 'Module Phase 7', `
      <div class="editorial-card p-4 space-y-3 text-sm">
        <p class="text-ink-soft">${esc(method ? method.note : 'Runs the proposed method against both baselines on identical evidence.')}</p>
        <div class="flex flex-wrap items-end gap-3">
          <label class="text-xs text-ink-soft">Seed
            <input id="benchmark-seed" type="number" value="${esc(method?.seed ?? 20261010)}" class="mt-1 block border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm" /></label>
          <button id="run-benchmark" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Run benchmark</button>
        </div>
        <div id="benchmark-result"></div>
      </div>
    `)}
  </div>`;

  document.getElementById('save-threshold').addEventListener('click', async () => {
    try {
      const seconds = Number(document.getElementById('threshold').value);
      const updated = await api.setThreshold(seconds);
      state.workspace = updated;
      toast(`Staleness threshold set to ${updated.stalenessThresholdSeconds} seconds`, 'success');
    } catch (error) {
      toast(error.message, 'error');
    }
  });

  document.getElementById('connectivity').addEventListener('click', async () => {
    try {
      const result = await api.connectivity();
      document.getElementById('connectivity-result').innerHTML =
        `${statusCell(result.status || result.providerStatus || 'UNKNOWN')} ${esc(result.note || result.detail || '')}`;
    } catch (error) {
      toast(error.message, 'error');
    }
  });

  document.getElementById('run-benchmark').addEventListener('click', async () => {
    const host = document.getElementById('benchmark-result');
    host.innerHTML = '<p class="text-xs text-ink-soft">Running…</p>';
    try {
      const report = await api.runBenchmark(Number(document.getElementById('benchmark-seed').value));
      host.innerHTML = table([
        { label: 'Method', render: (row) => esc(row.displayName) },
        { label: 'Precision', render: (row) => esc(row.precision) },
        { label: 'Recall', render: (row) => esc(row.recall) },
        { label: 'F1', render: (row) => esc(row.f1) },
        { label: 'Unreported', render: (row) => esc(row.unreportedIssues) },
        { label: 'Unsafe auto actions', render: (row) => (row.unsafeAutomaticActions === 0 ? badge('0', 'ok') : badge(row.unsafeAutomaticActions, 'danger')) },
        { label: 'Median steps', render: (row) => esc(row.medianInvestigationSteps) }
      ], report.methods || [], { empty: 'No methods reported.' })
        + `<p class="text-xs text-ink-soft mt-3">${esc(report.methodologyNote || '')}</p>`
        + `<ul class="text-xs text-ink-soft list-disc pl-5 mt-2">${(report.limitations || []).map((item) => `<li>${esc(item)}</li>`).join('')}</ul>`;
    } catch (error) {
      host.innerHTML = `<p class="text-xs text-rust">${esc(error.message)}</p>`;
    }
  });
}

// ------------------------------------------------------------------ evidence

/** Canonical document types, offered for upload. The requirement list decides which are expected. */
const DOCUMENT_TYPES = [
  'COMMERCIAL_INVOICE', 'SHIPPING_BILL', 'PACKING_LIST', 'CERTIFICATE_OF_ORIGIN',
  'EXPORT_DECLARATION', 'BENEFICIARY_PROOF', 'SHIPPING_PROOF', 'OTHER'
];

const READINESS_TONES = {
  EVIDENCE_COMPLETE: 'ok', READY_FOR_REVIEW: 'warn', PENDING_EVIDENCE: 'warn',
  PROCESSING: 'neutral', ACTION_REQUIRED: 'danger', PROCESSING_FAILED: 'danger'
};

/**
 * Readiness and extraction rows store their list columns as JSON text, so the console parses them
 * rather than assuming the backend already sent an array. A malformed value degrades to an empty
 * list instead of breaking the page.
 */
function jsonArray(value) {
  if (Array.isArray(value)) return value;
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function statusCard(label, value, toneName, note) {
  return `<div class="editorial-card p-4 space-y-1">
    <div class="editorial-eyebrow">${esc(label)}</div>
    <div>${value ? badge(value, toneName) : badge('NOT OBSERVED', 'neutral')}</div>
    <div class="text-xs text-ink-soft">${esc(note || '')}</div>
  </div>`;
}

/**
 * The four answers, deliberately kept apart.
 *
 * "The provider confirmed the money" and "the paperwork is complete and consistent" are different
 * statements, and the console never collapses them into one status.
 */
function fourStatusGrid(summary) {
  const paymentNote = summary.providerPaymentStatus
    ? `${summary.paymentSimulated ? 'historical simulated data' : 'payment provider'}; internal record ${summary.internalPaymentStatus}`
    : 'no provider-confirmed payment observed for this order';
  const action = summary.recommendedAction
    ? `${summary.recommendedActionRequiresApproval ? 'needs a human decision' : 'no approval needed'}${summary.financialActionRecommended ? ' — money-moving' : ''}`
    : 'nothing outstanding';
  return `<div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
    ${statusCard('Payment status', summary.providerPaymentStatus, tone(summary.providerPaymentStatus), paymentNote)}
    ${statusCard('Evidence status', summary.evidenceStatus, READINESS_TONES[summary.evidenceStatus] || 'neutral', summary.evidenceReason)}
    ${statusCard('Exception status', summary.exceptionStatus, summary.exceptionStatus === 'NONE' || summary.exceptionStatus === 'RESOLVED' || summary.exceptionStatus === 'DISMISSED' ? 'ok' : 'warn', `${summary.openExceptionCount} open, ${summary.resolvedExceptionCount} resolved`)}
    ${statusCard('Recommended action', summary.recommendedAction ? 'ACTION' : 'NONE', summary.financialActionRecommended ? 'danger' : summary.recommendedActionRequiresApproval ? 'warn' : 'ok', action)}
  </div>`;
}

/**
 * Renders the evidence side of one order and wires its interactions.
 *
 * Shared by the evidence queue and the transaction detail so both show the same facts from the same
 * endpoints. Every action here is a document action: none of them can change a payment.
 */
async function mountEvidencePanel(host, orderId) {
  const [summary, requirements, documents] = await Promise.all([
    api.evidenceSummary(orderId),
    api.evidenceRequirements(orderId),
    api.evidenceDocuments(orderId, true)
  ]);

  const required = jsonArray(requirements.required);
  const requiredList = required.length
    ? `<ul class="text-sm text-ink list-disc pl-5">${required.map((type) => `<li>${esc(type)}</li>`).join('')}</ul>`
    : '<p class="text-sm text-ink-soft">No supporting document is required at this order value.</p>';

  const documentRows = table([
    { label: 'Type', render: (row) => esc(row.documentType) },
    { label: 'File', render: (row) => esc(row.originalFilename) },
    { label: 'State', render: (row) => statusCell(row.processingStatus) },
    { label: 'Current', render: (row) => (row.supersededByDocumentId ? badge('SUPERSEDED', 'neutral') : badge('CURRENT', 'ok')) },
    { label: 'Read values', render: (row) => esc([
      row.extractedInvoiceNumber ? `no. ${row.extractedInvoiceNumber}` : null,
      row.extractedAmountMinor !== null && row.extractedAmountMinor !== undefined
        ? money(row.extractedAmountMinor, row.extractedCurrency) : null,
      row.extractedOrderReference ? `order ${row.extractedOrderReference}` : null
    ].filter(Boolean).join(' · ') || '—') },
    { label: 'Confirmed', render: (row) => (row.fieldsConfirmed ? badge('BY A PERSON', 'ok') : badge('NOT CONFIRMED', 'warn')) },
    { label: 'Uploaded', render: (row) => `${esc(row.actorLabel)}<br /><span class="text-xs text-ink-soft">${esc(time(row.createdAt))}</span>` },
    { label: 'Actions', render: (row) => `<div class="flex flex-wrap gap-1"><button data-doc-download="${esc(row.id)}" class="px-2 py-1 rounded-full border border-ink/30 text-xs">Download</button><button data-doc-extract="${esc(row.id)}" class="px-2 py-1 rounded-full border border-ink/30 text-xs">Re-read</button><button data-doc-history="${esc(row.id)}" class="px-2 py-1 rounded-full border border-ink/30 text-xs">History</button></div>` }
  ], documents, { empty: 'No supporting document has been supplied for this order.' });

  const uploadForm = `<form id="evidence-upload" class="editorial-card p-4 grid sm:grid-cols-3 gap-3 items-end">
    <label class="text-xs text-ink-soft">Document type
      <select name="documentType" class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm">
        ${DOCUMENT_TYPES.map((type) => `<option value="${esc(type)}"${required.includes(type) ? ' selected' : ''}>${esc(type)}</option>`).join('')}
      </select></label>
    <label class="text-xs text-ink-soft">File
      <input name="file" type="file" required class="mt-1 w-full text-xs" /></label>
    <button type="submit" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Upload and read</button>
    <div class="sm:col-span-3 text-xs text-ink-soft">The file is identified from its bytes, stored under this order and read once. Re-uploading identical bytes is refused; uploading a corrected document of a type that already exists supersedes the earlier one instead of overwriting it.</div>
  </form>`;

  host.innerHTML = `<div class="space-y-6">
    ${fourStatusGrid(summary)}
    <div class="grid md:grid-cols-2 gap-4">
      <div class="editorial-card p-4 space-y-2">
        <div class="editorial-eyebrow">Documents required</div>
        ${requiredList}
        <p class="text-xs text-ink-soft">${esc(requirements.rationale || '')}</p>
      </div>
      <div class="editorial-card p-4 space-y-2">
        <div class="editorial-eyebrow">Outstanding</div>
        <div class="text-sm text-ink">Missing: ${jsonArray(summary.missingDocumentTypes).join(', ') || 'none'}</div>
        <div class="text-sm text-ink">Awaiting a human: ${jsonArray(summary.uncertainDocumentTypes).join(', ') || 'none'}</div>
        <div class="text-sm text-ink">Open findings: ${(summary.openFindingTypes || []).join(', ') || 'none'}</div>
      </div>
    </div>
    ${section('Supporting documents', `${documents.length} record(s), including superseded versions`, documentRows)}
    ${section('Add or replace a document', 'Module I', uploadForm)}
    <div id="evidence-extraction-history" class="text-sm text-ink-soft"></div>
  </div>`;

  const historyHost = host.querySelector('#evidence-extraction-history');

  host.querySelectorAll('[data-doc-download]').forEach((button) => button.addEventListener('click', async () => {
    try {
      // The stored bytes are fetched with the bearer token and then handed to the browser, so the
      // download goes through the same authorisation as every other read.
      const blob = await API.downloadBlob(`/integrity/workspaces/${state.workspaceId}/evidence/documents/${button.dataset.docDownload}/content`);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'evidence-document';
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      toast(error.message, 'error');
    }
  }));

  host.querySelectorAll('[data-doc-extract]').forEach((button) => button.addEventListener('click', async () => {
    try {
      const outcome = await api.evidenceExtract(button.dataset.docExtract);
      toast(outcome.extractionMessage || 'Extraction re-run', outcome.extractionFailed ? 'error' : 'success');
      await mountEvidencePanel(host, orderId);
    } catch (error) {
      toast(error.message, 'error');
    }
  }));

  host.querySelectorAll('[data-doc-history]').forEach((button) => button.addEventListener('click', async () => {
    try {
      const history = await api.evidenceExtractions(button.dataset.docHistory);
      historyHost.innerHTML = section('Extraction history', 'Oldest first; a correction is a new row, never an overwrite',
        table([
          { label: 'When', render: (row) => esc(time(row.createdAt)) },
          { label: 'Method', render: (row) => badge(row.extractionMethod, row.humanCorrection ? 'warn' : 'neutral') },
          { label: 'Uncertain fields', render: (row) => esc(jsonArray(row.uncertainFields).join(', ') || 'none') },
          { label: 'Not found', render: (row) => esc(jsonArray(row.absentFields).join(', ') || 'none') },
          { label: 'Actor', render: (row) => esc(row.actorLabel) },
          { label: 'Failure', render: (row) => esc(row.failureReason || '—') }
        ], history, { empty: 'This document has no extraction history.' })
        + (history.length ? `<form id="evidence-correct" class="editorial-card p-4 grid sm:grid-cols-3 gap-3 items-end mt-3">
            <label class="text-xs text-ink-soft">Field
              <input name="field" value="invoiceNumber" class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm" /></label>
            <label class="text-xs text-ink-soft">Corrected value
              <input name="value" required class="mt-1 w-full border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm" /></label>
            <button type="submit" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Record correction</button>
            <div class="sm:col-span-3 text-xs text-ink-soft">A correction is recorded as its own extraction row and re-assesses the order. It changes what the business says the document states; it does not make the document genuine.</div>
          </form>` : ''));
      const form = historyHost.querySelector('#evidence-correct');
      if (form) {
        form.addEventListener('submit', async (event) => {
          event.preventDefault();
          const data = new FormData(event.target);
          try {
            await api.evidenceCorrect(button.dataset.docHistory, String(data.get('field')), String(data.get('value')));
            toast('Correction recorded and the order re-assessed', 'success');
            await mountEvidencePanel(host, orderId);
          } catch (error) {
            toast(error.message, 'error');
          }
        });
      }
    } catch (error) {
      toast(error.message, 'error');
    }
  }));

  const upload = host.querySelector('#evidence-upload');
  upload.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(event.target);
    const file = data.get('file');
    if (!file || !file.size) {
      toast('Choose a file first', 'error');
      return;
    }
    try {
      const outcome = await api.evidenceUpload(orderId, String(data.get('documentType')), file);
      toast(outcome.extractionFailed
        ? `Document stored but not readable: ${outcome.extractionMessage}`
        : (outcome.supersededPrevious
          ? 'Document stored and read; it replaced an earlier document of the same type'
          : 'Document stored and read'),
      outcome.extractionFailed ? 'error' : 'success');
      await mountEvidencePanel(host, orderId);
    } catch (error) {
      toast(error.message, 'error');
    }
  });
}

async function renderEvidence(root) {
  const [readiness, model, orders] = await Promise.all([
    api.evidenceReadiness(), api.evidenceModel(), api.orders()
  ]);

  const byStatus = (status) => readiness.filter((row) => row.status === status).length;
  const orderById = new Map(orders.map((order) => [order.id, order]));

  const queue = table([
    { label: 'Order', render: (row) => `<a class="underline" href="integrity-evidence.html?order=${esc(row.orderId)}">${esc(orderById.get(row.orderId)?.internalReference || shortId(row.orderId))}</a>` },
    { label: 'Evidence status', render: (row) => badge(row.status, READINESS_TONES[row.status] || 'neutral') },
    { label: 'Documents', render: (row) => esc(`${row.documentsPresent} of ${row.documentsExpected}`) },
    { label: 'Missing', render: (row) => esc(jsonArray(row.missingDocumentTypes).join(', ') || '—') },
    { label: 'Awaiting confirmation', render: (row) => esc(jsonArray(row.uncertainDocumentTypes).join(', ') || '—') },
    { label: 'Blocking findings', render: (row) => esc(row.blockingFindings) },
    { label: 'Last assessed', render: (row) => esc(time(row.lastAssessedAt)) },
    { label: 'Why', render: (row) => esc(row.reason) }
  ], readiness, { empty: 'No order has been assessed yet. Create an order and upload a document, or run an assessment.' });

  const assessForm = `<div class="editorial-card p-4 flex flex-wrap items-end gap-3">
    <label class="text-xs text-ink-soft">Order
      <select id="assess-order" class="mt-1 block border border-ink/20 rounded px-2 py-1 bg-paper text-ink text-sm">
        <option value="">Every order in this workspace</option>
        ${orders.map((order) => `<option value="${esc(order.id)}"${order.id === state.orderId ? ' selected' : ''}>${esc(order.internalReference)}</option>`).join('')}
      </select></label>
    <button id="run-assessment" class="px-4 py-2 rounded-full bg-ink text-paper text-sm font-medium">Assess evidence</button>
    <div id="assess-result" class="text-xs text-ink-soft"></div>
  </div>`;

  const selected = state.orderId && orderById.has(state.orderId) ? state.orderId : null;

  root.innerHTML = `<div class="space-y-8">
    <div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
      ${metric('Action required', byStatus('ACTION_REQUIRED'), 'a required record is absent, duplicated or contradicts the order', byStatus('ACTION_REQUIRED') > 0 ? 'text-rust' : '')}
      ${metric('Awaiting evidence', byStatus('PENDING_EVIDENCE'), 'nothing has been supplied yet')}
      ${metric('Ready for review', byStatus('READY_FOR_REVIEW'), 'present and consistent, but a value needs a person')}
      ${metric('Complete', byStatus('EVIDENCE_COMPLETE'), 'present, readable, consistent and confirmed')}
    </div>
    <div class="editorial-card p-4 text-sm text-ink-soft">
      ${esc(model.limitation)}
      <div class="mt-2 text-xs">Readiness values: ${esc(model.readinessModel)}. Processing values: ${esc(model.processingModel || model.processingStatusModel)}.</div>
      <div class="mt-2 text-xs">Evidence status is separate from provider payment status (${esc((model.mutuallyExclusiveFrom || []).join(', '))}). A provider-reported captured payment can still be waiting on an invoice; Aegis-X does not capture funds.</div>
      <div class="mt-2"><button id="run-all-assess" class="px-3 py-1.5 rounded-full border border-ink/30 text-xs text-ink">Assess every order</button></div>
    </div>
    ${section('Evidence review queue', `${readiness.length} assessed order(s)`, queue)}
    ${section('Selected order', selected ? 'Evidence status, documents and outstanding items' : 'Choose an order from the queue', '<div id="evidence-panel"><p class="text-sm text-ink-soft">Select an order above to review its documents and statuses.</p></div>')}
    ${section('Run an assessment', 'Assessment recomputes the status from the documents on hand', assessForm)}
  </div>`;

  if (selected) {
    await mountEvidencePanel(document.getElementById('evidence-panel'), selected);
  }

  document.getElementById('run-all-assess').addEventListener('click', async () => {
    try {
      await api.evidenceAssess(null);
      toast('Every order was assessed', 'success');
      await renderEvidence(root);
    } catch (error) {
      toast(error.message, 'error');
    }
  });

  document.getElementById('run-assessment').addEventListener('click', async () => {
    const orderId = document.getElementById('assess-order').value;
    try {
      const report = await api.evidenceAssess(orderId || null);
      document.getElementById('assess-result').textContent = `Assessed ${report.assessedOrders ?? 1} order(s)`;
      toast('Assessment complete', 'success');
      await renderEvidence(root);
    } catch (error) {
      toast(error.message, 'error');
    }
  });
}

// -------------------------------------------------------------------- boot

const RENDERERS = {
  overview: renderOverview,
  transactions: renderTransactions,
  evidence: renderEvidence,
  webhooks: renderWebhooks,
  reconciliation: renderReconciliation,
  exceptions: renderExceptions,
  recovery: renderRecovery,
  audit: renderAudit,
  settings: renderSettings
};

export async function boot(page = state.page) {
  state.page = page;
  // The integrity console is back-office only. Without this guard a signed-out visitor got an
  // "HTTP error 401" card instead of the sign-in page, and a merchant token rendered an empty shell.
  const allowed = await requireUser(['ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR']);
  if (!allowed) return;

  const [title, subtitle, eyebrow] = PAGE_META[state.page] || PAGE_META.overview;
  const root = document.createElement('div');
  root.className = 'flex min-h-screen';
  root.append(createSidebar({ workspaceStatus: true }), document.createElement('div'));
  root.lastElementChild.className = 'flex-1 flex flex-col min-w-0';
  root.lastElementChild.id = 'integrity-main';
  document.body.prepend(root);
  document.getElementById('integrity-main').outerHTML = shell(title, subtitle, eyebrow);
  wireMobileNavigation(root.querySelector('aside'), root.querySelector('header'), root);

  try {
    await loadWorkspace();
  } catch (error) {
    document.getElementById('integrity-body').innerHTML =
      `<div class="editorial-card p-4 text-sm text-rust">Could not load the integrity workspace: ${esc(error.message)}. Sign in and reload.</div>`;
    return;
  }

  const body = document.getElementById('integrity-body');
  try {
    await RENDERERS[state.page](body);
  } catch (error) {
    body.innerHTML = `<div class="editorial-card p-4 text-sm text-rust">${esc(error.message)}</div>`;
  }
  enhanceResponsiveTables(root);
}

