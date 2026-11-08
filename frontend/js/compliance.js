/** Aegis-X Compliance Workspace Controller. */
import { API } from './api.js';
import { Auth } from './auth.js';
import { Toast } from './notifications.js';
import { Utils } from './utils.js';

export const CompliancePage = {
  pageSize: 20,
  currentPage: 0,
  totalPages: 0,
  totalElements: 0,
  searchQuery: '',
  requestSequence: 0,
  loading: false,
  loadError: null,
  submitting: false,

  async init() {
    if (!await Auth.requireUser(['COMPLIANCE_OFFICER', 'ADMIN'])) return;
    Auth.applyRoleNavigation();
    window.Utils = Utils;
    this.setupSearch();
    this.setupReviewForm();
    await this.loadTransactions(0);
  },

  setupSearch() {
    const searchInput = document.getElementById('compliance-search');
    searchInput?.addEventListener('input', () => {
      this.searchQuery = searchInput.value.trim();
      clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => this.loadTransactions(0), 250);
    });
  },

  async loadTransactions(page = this.currentPage) {
    const requestSequence = ++this.requestSequence;
    this.loading = true;
    this.loadError = null;
    this.renderTable();

    const params = new URLSearchParams({ page: String(Math.max(0, page)), size: String(this.pageSize) });
    if (this.searchQuery) params.set('q', this.searchQuery);

    try {
      const result = await API.request(`/v1/compliance/queue?${params}`);
      if (requestSequence !== this.requestSequence) return;
      if (!result || !Array.isArray(result.content)) {
        throw new Error('The compliance API returned an invalid page.');
      }
      this.currentPage = Number.isInteger(result.number) ? result.number : Math.max(0, page);
      this.totalPages = Number.isInteger(result.totalPages) ? result.totalPages : 0;
      this.totalElements = Number.isInteger(result.totalElements) ? result.totalElements : result.content.length;
      this.currentItems = result.content;
    } catch (err) {
      if (requestSequence !== this.requestSequence) return;
      this.loadError = err.message || 'The review queue could not be loaded.';
      this.currentItems = [];
      Toast.error('Could not load the escalated review queue.');
    } finally {
      if (requestSequence === this.requestSequence) {
        this.loading = false;
        this.renderTable();
      }
    }
  },

  renderTable() {
    const tbody = document.getElementById('compliance-tbody');
    const countBadge = document.getElementById('results-count');
    const pagination = document.getElementById('compliance-pagination');
    if (!tbody) return;

    if (countBadge) {
      countBadge.textContent = this.loading ? 'Loading escalated cases…' :
        this.loadError ? 'Queue unavailable' : `${this.totalElements} Records Found`;
    }

    if (this.loading) {
      tbody.innerHTML = '<tr><td colspan="7" class="text-center py-12 text-ink-soft" role="status">Loading escalated cases…</td></tr>';
      pagination?.replaceChildren();
      return;
    }

    if (this.loadError) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 7;
      cell.className = 'text-center py-10 text-ink-soft';
      cell.setAttribute('role', 'alert');
      const message = document.createElement('p');
      message.className = 'text-xs mb-3';
      message.textContent = `The review queue could not be loaded: ${this.loadError}`;
      const retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
      retry.textContent = 'Retry';
      retry.addEventListener('click', () => this.loadTransactions(this.currentPage));
      cell.append(message, retry);
      row.append(cell);
      tbody.replaceChildren(row);
      pagination?.replaceChildren();
      return;
    }

    const transactions = this.currentItems || [];
    if (transactions.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 7;
      cell.className = 'text-center py-12 text-ink-soft';
      cell.textContent = this.searchQuery
        ? 'No escalated cases match this search.'
        : 'No escalated cases are awaiting a decision.';
      row.append(cell);
      tbody.replaceChildren(row);
      pagination?.replaceChildren();
      return;
    }

    tbody.innerHTML = transactions.map(tx => `
      <tr class="hover:bg-paper-dim/60 transition-colors">
        <td class="font-mono-data font-semibold text-ink py-3.5 px-2">${Utils.escapeHtml(tx.referenceNumber || tx.transactionId)}</td>
        <td class="text-xs text-ink-soft py-3.5 px-2">${Utils.escapeHtml(tx.merchantName || tx.merchantId || '—')}</td>
        <td class="font-serif text-sm text-ink font-medium py-3.5 px-2">${Utils.escapeHtml(tx.beneficiaryName || '—')}</td>
        <td class="font-mono-data text-ink font-medium py-3.5 px-2">${Utils.formatCurrency(tx.amount, tx.currency)}</td>
        <td class="text-xs text-ink-soft py-3.5 px-2">${Utils.escapeHtml(tx.purposeCode || '—')}</td>
        <td class="py-3.5 px-2">${Utils.renderStatusBadge(tx.status)}</td>
        <td class="text-right py-3.5 px-2">
          <button type="button" data-review-id="${Utils.escapeHtml(tx.transactionId)}" class="aegis-btn-ink py-1 px-3 text-xs">Review</button>
        </td>
      </tr>
    `).join('');

    tbody.querySelectorAll('[data-review-id]').forEach(button => {
      button.addEventListener('click', () => this.openReview(button.dataset.reviewId));
    });

    if (!pagination) return;
    pagination.replaceChildren();
    const pageCount = this.totalPages || 1;
    const summary = Utils.textElement('span', 'text-xs text-ink-soft',
      `${this.currentPage + 1} / ${pageCount} · ${this.totalElements} records`);
    const previous = document.createElement('button');
    previous.type = 'button';
    previous.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
    previous.textContent = 'Previous';
    previous.disabled = this.currentPage <= 0;
    previous.addEventListener('click', () => this.loadTransactions(this.currentPage - 1));
    const next = document.createElement('button');
    next.type = 'button';
    next.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
    next.textContent = 'Next';
    next.disabled = this.currentPage >= this.totalPages - 1;
    next.addEventListener('click', () => this.loadTransactions(this.currentPage + 1));
    pagination.append(summary, previous, next);
  },

  setupReviewForm() {
    const form = document.getElementById('review-decision-form');
    document.getElementById('close-review-modal')?.addEventListener('click', () => {
      document.getElementById('review-modal')?.classList.remove('active');
    });
    form?.addEventListener('submit', event => event.preventDefault());
    form?.querySelectorAll('[data-review-decision]').forEach(button => {
      button.addEventListener('click', () => this.submitDecision(button.dataset.reviewDecision));
    });
  },

  async submitDecision(decision) {
    const form = document.getElementById('review-decision-form');
    const justificationField = document.getElementById('review-justification');
    const justification = justificationField?.value.trim() || '';
    if (justification.length < 10) {
      Toast.error('Provide a justification of at least 10 characters.');
      justificationField?.focus();
      return;
    }
    if (this.submitting || !this.currentReviewId || !['APPROVED', 'REJECTED'].includes(decision)) return;

    this.submitting = true;
    const buttons = [...(form?.querySelectorAll('[data-review-decision]') || [])];
    buttons.forEach(button => { button.disabled = true; });
    try {
      const result = await API.request('/v1/compliance/review', {
        method: 'POST',
        body: JSON.stringify({ transactionId: this.currentReviewId, decision, justification })
      });
      const resultingState = result?.resultingState || (decision === 'APPROVED' ? 'APPROVED' : 'REJECTED');
      Toast.success(`Decision recorded. Current status: ${resultingState}.`);
      document.getElementById('review-modal')?.classList.remove('active');
      form?.reset();
      await this.loadTransactions(this.currentPage);
    } catch (err) {
      Toast.error(`Review decision failed: ${err.message}`);
    } finally {
      this.submitting = false;
      buttons.forEach(button => { button.disabled = false; });
    }
  },

  async openReview(transactionId) {
    try {
      const item = await API.request(`/v1/compliance/review/${encodeURIComponent(transactionId)}`);
      if (!item) throw new Error('Review details were not returned');
      this.currentReviewId = transactionId;
      const values = {
        'review-reference': item.referenceNumber || item.transactionId,
        'review-status': item.status,
        'review-merchant': item.merchantName || item.merchantId,
        'review-beneficiary': item.beneficiaryName,
        'review-amount': Utils.formatCurrency(item.amount, item.currency),
        'review-purpose': item.purposeCode,
        'review-evidence': item.evidenceComplete === true ? 'Complete' : item.evidenceComplete === false ? 'Incomplete' : 'Not reported'
      };
      Object.entries(values).forEach(([id, value]) => {
        document.getElementById(id).textContent = value || '—';
      });
      const violations = document.getElementById('review-violations');
      violations.replaceChildren();
      (item.violations || []).forEach(violation => {
        const row = document.createElement('li');
        row.textContent = [violation.ruleName, violation.severityDisplayName || violation.severity, violation.description]
          .filter(Boolean).join(' · ');
        violations.append(row);
      });
      if (!violations.childElementCount) {
        const empty = document.createElement('li');
        empty.textContent = 'No policy violation details were returned.';
        violations.append(empty);
      }
      const workspaceLink = document.getElementById('review-workspace-link');
      workspaceLink.href = `transaction.html?id=${encodeURIComponent(transactionId)}`;
      document.getElementById('review-modal').classList.add('active');
    } catch (err) {
      Toast.error(`Could not load review details: ${err.message}`);
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  CompliancePage.init();
});
