/**
 * Aegis-X Compliance Workspace Controller (Stage 8)
 * Search, multi-filtering, risk thresholds, and compliance investigation queues
 */
import { API } from './api.js';
import { Auth } from './auth.js';
import { Toast } from './notifications.js';
import { Utils } from './utils.js';

export const CompliancePage = {
  async init() {
    if (!await Auth.requireUser(['COMPLIANCE_OFFICER', 'ADMIN'])) return;
    Auth.applyRoleNavigation();
    window.Utils = Utils;

    this.allTransactions = [];
    this.filteredTransactions = [];
    this.setupFilters();
    this.setupReviewForm();
    await this.loadTransactions();
  },

  async loadTransactions() {
    try {
      const result = await API.request('/v1/compliance/queue?page=0&size=100');
      this.allTransactions = Array.isArray(result) ? result : result?.content || [];
      this.filteredTransactions = [...this.allTransactions];
      this.renderTable(this.filteredTransactions);
    } catch (err) {
      Toast.error('Failed to load compliance records: ' + err.message);
    }
  },

  setupFilters() {
    const searchInput = document.getElementById('compliance-search');
    const statusFilter = document.getElementById('filter-status');
    const applyFilters = () => {
      const query = (searchInput?.value || '').toLowerCase().trim();
      const selectedStatus = statusFilter?.value || 'ALL';

      this.filteredTransactions = this.allTransactions.filter(tx => {
        const searchText = [tx.referenceNumber, tx.beneficiaryName, tx.merchantName, tx.merchantId]
          .filter(Boolean).join(' ').toLowerCase();
        const status = (tx.status || '').toUpperCase();
        const severity = (tx.highestSeverity || '').toUpperCase();
        const matchesFilter = selectedStatus === 'ALL' ||
          (selectedStatus === 'COMPLIANCE_HOLD' && status === 'COMPLIANCE_HOLD') ||
          (selectedStatus === 'HIGH_RISK' && ((tx.priorityScore || 0) >= 80 || ['HIGH', 'CRITICAL'].includes(severity))) ||
          (selectedStatus === 'OCR_FAILURE' && status.includes('OCR') && status.includes('FAIL')) ||
          (selectedStatus === 'AI_FAILURE' && status.includes('AI') && status.includes('FAIL')) ||
          (selectedStatus === 'SETTLEMENT_FAILURE' && status.includes('SETTLEMENT') && status.includes('FAIL'));
        return (!query || searchText.includes(query)) && matchesFilter;
      });

      this.transactionPage = 0;
      this.renderTable(this.filteredTransactions);
    };

    if (searchInput) searchInput.addEventListener('input', applyFilters);
    if (statusFilter) statusFilter.addEventListener('change', applyFilters);
  },

  renderTable(transactions) {
    const tbody = document.getElementById('compliance-tbody');
    const countBadge = document.getElementById('results-count');
    if (countBadge) countBadge.textContent = `${transactions.length} Records Found`;

    if (!tbody) return;
    this.transactionPage = this.transactionPage || 0;
    const pageSize = 20;
    const pageCount = Math.max(1, Math.ceil(transactions.length / pageSize));
    this.transactionPage = Math.min(this.transactionPage, pageCount - 1);
    const visibleTransactions = transactions.slice(this.transactionPage * pageSize, (this.transactionPage + 1) * pageSize);

    if (transactions.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" class="text-center py-12 text-ink-soft">
            <i class="fa-solid fa-filter-circle-xmark w-8 h-8 mx-auto mb-2 text-ink-soft"></i>
            <p class="font-sans text-xs">No compliance records match the active criteria.</p>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = visibleTransactions.map(tx => `
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
    const pagination = document.getElementById('compliance-pagination');
    if (pagination) {
      pagination.replaceChildren(
        Utils.textElement('span', 'text-xs text-ink-soft', `${this.transactionPage + 1} / ${pageCount} · ${transactions.length} records`)
      );
      const previous = document.createElement('button');
      previous.type = 'button';
      previous.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
      previous.textContent = 'Previous';
      previous.disabled = this.transactionPage === 0;
      previous.addEventListener('click', () => {
        this.transactionPage -= 1;
        this.renderTable(this.filteredTransactions);
      });
      const next = document.createElement('button');
      next.type = 'button';
      next.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
      next.textContent = 'Next';
      next.disabled = this.transactionPage >= pageCount - 1;
      next.addEventListener('click', () => {
        this.transactionPage += 1;
        this.renderTable(this.filteredTransactions);
      });
      pagination.append(previous, next);
    }
  },

  setupReviewForm() {
    const form = document.getElementById('review-decision-form');
    document.getElementById('close-review-modal')?.addEventListener('click', () => {
      document.getElementById('review-modal')?.classList.remove('active');
    });
    form?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const justification = document.getElementById('review-justification').value.trim();
      const decision = event.submitter?.value;
      if (justification.length < 10) {
        Toast.error('Provide a justification of at least 10 characters.');
        return;
      }
      if (!this.currentReviewId || !['APPROVED', 'REJECTED'].includes(decision)) return;

      try {
        await API.request('/v1/compliance/review', {
          method: 'POST',
          body: JSON.stringify({ transactionId: this.currentReviewId, decision, justification })
        });
        Toast.success(`Review ${decision.toLowerCase()} submitted.`);
        document.getElementById('review-modal')?.classList.remove('active');
        form.reset();
        await this.loadTransactions();
      } catch (err) {
        Toast.error('Review decision failed: ' + err.message);
      }
    });
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
      Toast.error('Could not load review details: ' + err.message);
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  CompliancePage.init();
});
