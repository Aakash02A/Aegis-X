/**
 * Aegis-X Settlement Workspace Controller
 */
import { API } from './api.js';
import { Auth } from './auth.js';
import { Toast } from './notifications.js';
import { Utils } from './utils.js';

export const SettlementPage = {
  async init() {
    if (!await Auth.requireUser(['SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER'])) return;
    Auth.applyRoleNavigation();
    const canOperate = Auth.getUser()?.role === 'SETTLEMENT_OPERATOR';
    const reconciliationButton = document.getElementById('process-reconciliation');
    if (reconciliationButton) reconciliationButton.hidden = !canOperate;
    window.Utils = Utils;

    this.allSettlements = [];
    this.filteredSettlements = [];
    this.setupFilters();
    document.getElementById('process-reconciliation')?.addEventListener('click', () => this.processReconciliation());
    await this.loadSettlements();
  },

  async loadSettlements() {
    try {
      const statuses = [
        'INITIATED', 'PROCESSING', 'SUBMITTED', 'PENDING', 'COMPLETED',
        'FAILED', 'REJECTED', 'RECONCILING', 'RECONCILED',
        'RECONCILIATION_FAILED', 'MANUAL_REVIEW', 'CANCELLED'
      ];
      const pages = await Promise.all(statuses.map(status =>
        API.request(`/v1/settlements/status/${status}?page=0&size=100&sortBy=initiatedAt&sortDir=desc`)
      ));
      const records = pages.flatMap(page => Array.isArray(page) ? page : page?.content || []);
      this.allSettlements = [...new Map(records.map(settlement => [settlement.id, settlement])).values()];
      this.filteredSettlements = [...this.allSettlements];
      this.renderTable(this.filteredSettlements);
    } catch (err) {
      Toast.error('Failed to load settlement records: ' + err.message);
      this.renderTable([], true);
    }
  },

  setupFilters() {
    const searchInput = document.getElementById('settlement-search');
    const statusFilter = document.getElementById('filter-status');

    const applyFilters = () => {
      const query = (searchInput?.value || '').toLowerCase().trim();
      const selectedStatus = statusFilter?.value || 'ALL';

      this.filteredSettlements = this.allSettlements.filter(st => {
        const matchesQuery = !query || 
          (st.settlementReference || st.id || '').toLowerCase().includes(query) ||
          (st.transactionReference || st.transactionId || '').toLowerCase().includes(query) ||
          (st.processorDisplayName || st.paymentProcessor || '').toLowerCase().includes(query);

        const matchesStatus = selectedStatus === 'ALL' || st.status === selectedStatus;

        return matchesQuery && matchesStatus;
      });

      this.renderTable(this.filteredSettlements);
    };

    if (searchInput) searchInput.addEventListener('input', applyFilters);
    if (statusFilter) statusFilter.addEventListener('change', applyFilters);
  },

  renderTable(settlements, failed = false) {
    const tbody = document.getElementById('settlement-tbody');
    const countBadge = document.getElementById('results-count');
    if (countBadge) countBadge.textContent = `${settlements.length} Records Found`;

    if (!tbody) return;

    if (settlements.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" class="text-center py-12 text-ink-soft">
            <i data-lucide="filter-x" class="w-8 h-8 mx-auto mb-2 text-ink-soft"></i>
            <p class="font-sans text-xs">${failed ? 'Settlement records could not be loaded.' : 'No settlement records match the active criteria.'}</p>
          </td>
        </tr>
      `;
      Utils.initIcons();
      return;
    }

    tbody.innerHTML = settlements.map(st => `
      <tr class="hover:bg-paper-dim/60 transition-colors">
        <td class="font-mono-data font-semibold text-ink py-3.5 px-6">
          ${Utils.escapeHtml(st.settlementReference || st.id)}
          <div class="text-[10px] text-ink-soft font-mono-data mt-0.5">TX: ${Utils.escapeHtml(st.transactionReference || st.transactionId)}</div>
        </td>
        <td class="font-serif text-sm text-ink font-medium py-3.5 px-2">${Utils.escapeHtml(st.processorDisplayName || st.paymentProcessor || '—')}</td>
        <td class="font-mono-data text-ink font-medium py-3.5 px-2">${Utils.formatCurrency(st.settlementAmount, st.settlementCurrency)}</td>
        <td class="font-mono-data text-rust font-medium py-3.5 px-2">${st.processingFees == null ? '—' : Utils.formatCurrency(st.processingFees, st.settlementCurrency)}</td>
        <td class="py-3.5 px-2">${this.renderSettlementBadge(st.status)}</td>
        <td class="text-xs text-ink-soft font-mono-data py-3.5 px-2">${st.retryCount ?? 0} · ${st.reconciledAt ? 'Reconciled' : 'Not reconciled'}</td>
        <td class="text-right py-3.5 px-6">
          ${st.canRetry && Auth.getUser()?.role === 'SETTLEMENT_OPERATOR' ? `
          <button type="button" data-retry-id="${Utils.escapeHtml(st.id)}" class="aegis-btn-ink py-1 px-3 text-xs inline-flex items-center gap-1">
            <i data-lucide="refresh-cw" class="w-3 h-3"></i> Retry
          </button>
          ` : `
          <span class="text-xs text-ink-soft">—</span>
          `}
        </td>
      </tr>
    `).join('');

    Utils.initIcons();
    tbody.querySelectorAll('[data-retry-id]').forEach(button => {
      button.addEventListener('click', () => this.retrySettlement(button.dataset.retryId));
    });
  },

  renderSettlementBadge(status) {
    switch (status) {
      case 'SETTLED':
      case 'COMPLETED':
      case 'RECONCILED':
        return '<span class="editorial-badge badge-approved">Settled</span>';
      case 'FAILED':
        return '<span class="editorial-badge badge-hold">Failed</span>';
      case 'PROCESSING':
        return '<span class="editorial-badge badge-brass">Processing</span>';
      default:
        return `<span class="editorial-badge badge-neutral">${Utils.escapeHtml(status || 'UNKNOWN')}</span>`;
    }
  },

  async retrySettlement(id) {
    try {
      await API.request(`/v1/settlements/${encodeURIComponent(id)}/retry`, { method: 'POST' });
      Toast.success('Settlement retry triggered.');
      await this.loadSettlements();
    } catch (e) {
      Toast.error('Retry failed: ' + e.message);
    }
  },

  async processReconciliation() {
    const button = document.getElementById('process-reconciliation');
    if (button) button.disabled = true;
    try {
      await API.request('/v1/settlements/reconciliation/process', { method: 'POST' });
      Toast.success('Settlement reconciliation completed.');
      await this.loadSettlements();
    } catch (error) {
      Toast.error('Reconciliation failed: ' + error.message);
    } finally {
      if (button) button.disabled = false;
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  SettlementPage.init();
});
