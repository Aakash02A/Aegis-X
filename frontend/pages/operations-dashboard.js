/** Live operations and health data from configured backend endpoints. */
import { API } from '../core/api.js';
import { requireUser } from '../core/guards.js';
import { AppShell } from '../components/app-shell.js';
import { Toast } from '../components/toast.js';
import { Utils } from '../core/formatters.js';
import { AuditViewer } from '../components/audit-timeline.js';

export const OperationsPage = {
  async init() {
    if (!await requireUser(['ADMIN'])) return;
    AppShell.mount();
    this.setupAuditSearch();
    await this.loadMetrics();
    await this.loadBusinessMetrics();
    this.refreshTimer = setInterval(() => this.loadMetrics(), 30000);
  },

  async loadMetrics() {
    const [health, meters, prometheus, performance] = await Promise.allSettled([
      API.request('/actuator/health'),
      API.request('/actuator/metrics'),
      API.request('/actuator/prometheus'),
      API.request('/performance/metrics')
    ]);
    this.renderHealth(health.status === 'fulfilled' ? health.value : null);
    this.renderPerformance(
      meters.status === 'fulfilled' ? meters.value : null,
      prometheus.status === 'fulfilled' ? prometheus.value : null,
      performance.status === 'fulfilled' ? performance.value : null
    );
  },

  renderHealth(data) {
    const components = data?.components || {};
    const statuses = [
      ['API', data?.status],
      ['Database', this.findComponentStatus(components, /db|database|datasource/)],
      ['OCR', this.findComponentStatus(components, /ocr|tika/)],
      ['AI', this.findComponentStatus(components, /ai|spring.?ai/i)],
      ['WhatsApp', this.findComponentStatus(components, /whatsapp/)]
    ];
    const cards = document.getElementById('health-cards');
    cards.replaceChildren();
    statuses.forEach(([name, status]) => {
      const card = document.createElement('section');
      card.className = 'editorial-card p-4 bg-white';
      card.append(
        Utils.textElement('h3', 'text-[10px] uppercase font-semibold text-ink-soft', name),
        Utils.textElement('p', `font-mono-data text-lg mt-2 ${status === 'UP' ? 'text-olive-deep' : status === 'DOWN' ? 'text-rust' : 'text-ink'}`, status || 'Not reported')
      );
      cards.append(card);
    });
    const statusEl = document.getElementById('platform-status-badge');
    if (statusEl) statusEl.textContent = data?.status || 'Health endpoint unavailable';
  },

  findComponentStatus(components, pattern) {
    const match = Object.entries(components).find(([name]) => pattern.test(name));
    return match?.[1]?.status || 'Not reported';
  },

  renderPerformance(meters, prometheus, performance) {
    const samples = typeof prometheus === 'string' ? prometheus.split(/\r?\n/) : [];
    const values = { ocr: 'Not exposed', ai: 'Not exposed', api: 'Not exposed', queue: 'Not exposed' };
    samples.forEach(line => {
      const match = line.match(/^([\w:.]+)(?:\{([^}]*)\})?\s+([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)/);
      if (!match) return;
      const [, metricName, labels = '', rawValue] = match;
      const name = metricName.toLowerCase();
      const value = Number(rawValue);
      const isP95 = labels.includes('quantile="0.95"') || /(?:^|_)(?:p95|95th)(?:_|$)/.test(name);
      if (isP95 && /ocr|tika/.test(name)) values.ocr = this.formatMetricValue(metricName, value);
      else if (isP95 && /ai/.test(name)) values.ai = this.formatMetricValue(metricName, value);
      else if (isP95 && /http_server_requests|http\.server\.requests/.test(name)) values.api = this.formatMetricValue(metricName, value);
      if (/queue.*(?:depth|pending)|(?:depth|pending).*queue/.test(name)) values.queue = String(value);
    });
    Object.entries({
      'metric-ocr-p95': values.ocr,
      'metric-ai-p95': values.ai,
      'metric-api-p95': values.api,
      'metric-queue-depth': values.queue
    }).forEach(([id, value]) => {
      const element = document.getElementById(id);
      if (element) element.textContent = value;
    });

    const names = meters?.names || [];
    const observedOperations = Object.entries(performance?.operations || {})
      .filter(([, metric]) => metric.count > 0)
      .map(([name, metric]) => `${name}: avg ${metric.avg_duration_ms} ms across ${metric.count} observations`);
    const details = document.getElementById('performance-details');
    details.textContent = [
      names.length ? `${names.length} actuator meters available.` : 'Actuator meter names unavailable.',
      ...observedOperations
    ].join(' ');
  },

  formatMetricValue(metricName, value) {
    if (metricName.endsWith('_seconds')) return `${(value * 1000).toFixed(2)} ms`;
    if (metricName.endsWith('_milliseconds')) return `${value.toFixed(2)} ms`;
    return `${value} exported units`;
  },

  async loadBusinessMetrics() {
    try {
      let page = await API.request('/transactions?page=0&size=100');
      const transactions = [...(page.content || [])];
      let pageNumber = 0;
      while (page.last === false && pageNumber + 1 < page.totalPages) {
        pageNumber += 1;
        page = await API.request(`/transactions?page=${pageNumber}&size=100`);
        transactions.push(...(page.content || []));
      }
      this.setBusinessMetric('business-transactions', page.totalElements ?? transactions.length);
      this.setBusinessMetric('business-approvals', transactions.filter(tx => tx.status === 'APPROVED').length);
      this.setBusinessMetric('business-holds', transactions.filter(tx => tx.status === 'COMPLIANCE_HOLD').length);
    } catch {
      this.setBusinessMetric('business-transactions', 'Unavailable');
      this.setBusinessMetric('business-approvals', 'Unavailable');
      this.setBusinessMetric('business-holds', 'Unavailable');
    }

    try {
      const queue = await API.request('/v1/compliance/queue?page=0&size=1');
      this.setBusinessMetric('business-reviews', queue.totalElements ?? queue.content?.length ?? 0);
    } catch {
      this.setBusinessMetric('business-reviews', 'Unavailable');
    }
  },

  setBusinessMetric(id, value) {
    const element = document.getElementById(id);
    if (element) element.textContent = String(value);
  },

  setupAuditSearch() {
    document.getElementById('audit-search-form')?.addEventListener('submit', async event => {
      event.preventDefault();
      const transactionId = document.getElementById('audit-transaction-id').value.trim();
      try {
        const timeline = await API.request(`/transactions/${encodeURIComponent(transactionId)}/audit`);
        AuditViewer.render('operations-audit-results', timeline);
      } catch (error) {
        Toast.error('Audit lookup failed: ' + error.message);
      }
    });
  }
};

document.addEventListener('DOMContentLoaded', () => {
  OperationsPage.init();
});
