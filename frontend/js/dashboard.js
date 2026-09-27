/**
 * Aegis-X Merchant Dashboard Controller (Stage 7)
 * Handles metrics, quick actions, new transaction modal, and live transaction stream
 */
import { API } from './api.js';
import { Auth } from './auth.js';
import { Toast } from './notifications.js';
import { Utils } from './utils.js';

export const DashboardPage = {
  async init() {
    if (!await Auth.requireUser(['MERCHANT'])) return;
    Auth.applyRoleNavigation();
    window.Utils = Utils;

    this.renderUserProfile();
    this.setupNewTransactionModal();
    await this.loadDashboardData();
  },

  renderUserProfile() {
    const user = Auth.getUser();
    if (user) {
      const nameEl = document.getElementById('user-profile-name');
      const roleEl = document.getElementById('user-profile-role');
      if (nameEl) nameEl.textContent = user.name || user.email;
      if (roleEl) roleEl.textContent = user.role;
    }
  },

  async loadDashboardData() {
    try {
      let result = await API.request('/transactions?page=0&size=100');
      const transactions = [...(Array.isArray(result) ? result : result?.content || [])];
      let pageNumber = 0;
      while (result?.last === false && pageNumber + 1 < result.totalPages) {
        pageNumber += 1;
        result = await API.request(`/transactions?page=${pageNumber}&size=100`);
        transactions.push(...(result.content || []));
      }
      this.transactions = transactions;
      await this.renderMetrics(transactions, result?.totalElements ?? transactions.length);
      this.renderTransactionsTable(transactions);
      this.renderTransactionCharts(transactions);
      await this.loadRecentActivity(transactions);
      try {
        this.renderProcessingChart(await API.request('/performance/metrics'));
      } catch {
        this.renderProcessingChart(null);
      }
    } catch (err) {
      Toast.error('Failed to load dashboard data: ' + err.message);
    }
  },

  async renderMetrics(transactions, total = transactions.length) {
    const pendingEvidence = transactions.filter(t => t.status === 'EVIDENCE_PENDING').length;
    const ocrProcessing = transactions.filter(t => t.status === 'OCR_PROCESSING').length;
    const holds = transactions.filter(t => t.status === 'COMPLIANCE_HOLD').length;
    const approved = transactions.filter(t => t.status === 'APPROVED').length;
    let settlementPending = 'Unavailable';

    try {
      const approvedTransactions = transactions.filter(transaction => transaction.status === 'APPROVED');
      let pending = 0;
      const settledStates = new Set(['COMPLETED', 'RECONCILED', 'FAILED', 'REJECTED', 'CANCELLED', 'RECONCILIATION_FAILED']);
      for (let start = 0; start < approvedTransactions.length; start += 10) {
        const batch = approvedTransactions.slice(start, start + 10);
        const results = await Promise.all(batch.map(transaction =>
          API.request(`/v1/settlements/transaction/${encodeURIComponent(transaction.id)}`)
        ));
        results.forEach(result => {
          const settlements = Array.isArray(result) ? result : [];
          if (!settlements.length || settlements.some(settlement => !settledStates.has(settlement.status))) pending += 1;
        });
      }
      settlementPending = pending;
    } catch {
      settlementPending = 'Unavailable';
    }

    const setText = (id, val) => {
      const el = document.getElementById(id);
      if (el) el.textContent = val;
    };

    setText('metric-active-tx', total);
    setText('metric-pending-evidence', pendingEvidence);
    setText('metric-ocr-processing', ocrProcessing);
    setText('metric-compliance-holds', holds);
    setText('metric-approved-tx', approved);
    setText('metric-settlement-pending', settlementPending);
  },

  renderTransactionCharts(transactions) {
    const volumeContainer = document.getElementById('chart-transaction-volume');
    const approvalContainer = document.getElementById('chart-approval-rate');
    if (!volumeContainer || !approvalContainer) return;

    const dates = Array.from({ length: 7 }, (_, index) => {
      const date = new Date();
      date.setDate(date.getDate() - (6 - index));
      return date.toISOString().slice(0, 10);
    });
    const counts = dates.map(date => transactions.filter(transaction =>
      transaction.createdAt && new Date(transaction.createdAt).toISOString().slice(0, 10) === date
    ).length);
    const maxCount = Math.max(...counts, 1);
    const volumeChart = document.createElement('div');
    volumeChart.className = 'flex items-end gap-2 h-32';
    dates.forEach((date, index) => {
      const column = document.createElement('div');
      column.className = 'flex-1 h-full flex flex-col justify-end items-center gap-1';
      const bar = document.createElement('div');
      bar.className = 'w-full max-w-7 bg-rust/80 rounded-t-sm';
      bar.style.height = `${counts[index] ? Math.max(5, counts[index] / maxCount * 100) : 0}%`;
      bar.title = `${counts[index]} transactions`;
      column.append(bar, Utils.textElement('span', 'text-[9px] text-ink-soft', date.slice(5)));
      volumeChart.append(column);
    });
    volumeContainer.replaceChildren(volumeChart);

    const approved = transactions.filter(transaction => transaction.status === 'APPROVED').length;
    if (!transactions.length) {
      approvalContainer.replaceChildren(Utils.textElement('p', 'text-sm text-ink-soft', 'No transaction data available.'));
      return;
    }
    const rate = approved / transactions.length * 100;
    const meter = document.createElement('div');
    meter.className = 'h-2 bg-paper-dim rounded-full overflow-hidden mt-5';
    const fill = document.createElement('div');
    fill.className = 'h-full bg-olive-deep';
    fill.style.width = `${rate}%`;
    meter.append(fill);
    approvalContainer.replaceChildren(
      Utils.textElement('p', 'font-serif text-3xl text-ink', `${rate.toFixed(1)}%`),
      Utils.textElement('p', 'text-xs text-ink-soft mt-1', `${approved} approved of ${transactions.length} loaded transactions`),
      meter
    );
  },

  renderProcessingChart(data) {
    const container = document.getElementById('chart-processing-time');
    if (!container) return;
    container.replaceChildren();
    const operations = data?.operations || {};
    const measured = [
      ['OCR', operations.ocr_processing],
      ['AI', operations.ai_analysis],
      ['Policy', operations.policy_evaluation]
    ].filter(([, metric]) => metric && metric.count > 0);
    if (!measured.length) {
      container.append(Utils.textElement('p', 'text-sm text-ink-soft', 'No processing observations reported.'));
      return;
    }
    const maxDuration = Math.max(...measured.map(([, metric]) => metric.avg_duration_ms), 1);
    measured.forEach(([label, metric]) => {
      const row = document.createElement('div');
      row.className = 'mb-3';
      row.append(Utils.textElement('p', 'flex justify-between text-xs text-ink-soft', `${label}: ${metric.avg_duration_ms} ms average · ${metric.count} observations`));
      const track = document.createElement('div');
      track.className = 'h-2 bg-paper-dim rounded-full overflow-hidden mt-1';
      const bar = document.createElement('div');
      bar.className = 'h-full bg-rust';
      bar.style.width = `${Math.max(2, metric.avg_duration_ms / maxDuration * 100)}%`;
      track.append(bar);
      row.append(track);
      container.append(row);
    });
  },

  async loadRecentActivity(transactions) {
    const container = document.getElementById('recent-audit-feed');
    if (!container) return;
    container.replaceChildren();
    const recentTransactions = [...transactions]
      .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
      .slice(0, 10);
    const timelines = await Promise.all(recentTransactions.map(async transaction => {
      try {
        return await API.request(`/transactions/${encodeURIComponent(transaction.id)}/audit`);
      } catch {
        return null;
      }
    }));
    const events = timelines.flatMap(timeline => timeline?.events || [])
      .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
      .slice(0, 10);
    if (!events.length) {
      container.append(Utils.textElement('li', 'py-4 text-xs text-ink-soft', 'No audit events were returned for recent transactions.'));
      return;
    }
    events.forEach(event => {
      const item = document.createElement('li');
      item.className = 'grid grid-cols-1 sm:grid-cols-[10rem_1fr_auto] gap-1 sm:gap-4 border-t border-ink/10 py-3 text-xs';
      item.append(
        Utils.textElement('time', 'font-mono-data text-ink-soft', Utils.formatDateTime(event.createdAt)),
        Utils.textElement('span', 'font-semibold text-ink', `${event.eventType || 'Event'} · ${event.newState || 'State unchanged'}`),
        Utils.textElement('span', 'text-ink-soft', event.actorType || 'Actor not reported')
      );
      container.append(item);
    });
  },

  renderTransactionsTable(transactions) {
    const tbody = document.getElementById('recent-tx-tbody');
    if (!tbody) return;
    this.transactionPage = this.transactionPage || 0;
    const pageSize = 20;
    const pageCount = Math.max(1, Math.ceil(transactions.length / pageSize));
    this.transactionPage = Math.min(this.transactionPage, pageCount - 1);
    const visibleTransactions = transactions.slice(this.transactionPage * pageSize, (this.transactionPage + 1) * pageSize);

    if (transactions.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="6" class="text-center py-12 text-ink-soft">
            <i data-lucide="inbox" class="w-8 h-8 mx-auto mb-2 text-slate-500"></i>
            <p>No transactions found. Click "Create Transaction" to initiate cross-border compliance.</p>
          </td>
        </tr>
      `;
      Utils.initIcons();
      return;
    }

    tbody.innerHTML = visibleTransactions.map(tx => `
      <tr class="cursor-pointer hover:bg-paper-dim/60 transition-colors" onclick="window.location.href='transaction.html?id=${encodeURIComponent(tx.id)}'">
        <td class="font-mono-data font-semibold text-ink py-3.5 px-2">${Utils.escapeHtml(tx.referenceNumber || tx.id)}</td>
        <td class="font-serif text-sm text-ink font-medium py-3.5 px-2">${Utils.escapeHtml(tx.beneficiaryName || '—')}</td>
        <td class="font-mono-data text-ink font-medium py-3.5 px-2">${Utils.formatCurrency(tx.amount, tx.currency)}</td>
        <td class="font-mono-data text-ink-soft py-3.5 px-2">${Utils.escapeHtml(tx.purposeCode || '—')}</td>
        <td class="py-3.5 px-2">${Utils.renderStatusBadge(tx.status)}</td>
        <td class="text-right py-3.5 px-2">
          <a href="transaction.html?id=${encodeURIComponent(tx.id)}" class="editorial-link text-xs">
            Inspect →
          </a>
        </td>
      </tr>
    `).join('');

    Utils.initIcons();
    const pagination = document.getElementById('transaction-pagination');
    if (pagination) {
      pagination.replaceChildren();
      pagination.append(
        Utils.textElement('span', 'text-xs text-ink-soft', `${this.transactionPage + 1} / ${pageCount} · ${transactions.length} transactions`)
      );
      const previous = document.createElement('button');
      previous.type = 'button';
      previous.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
      previous.textContent = 'Previous';
      previous.disabled = this.transactionPage === 0;
      previous.addEventListener('click', () => {
        this.transactionPage -= 1;
        this.renderTransactionsTable(this.transactions);
      });
      const next = document.createElement('button');
      next.type = 'button';
      next.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
      next.textContent = 'Next';
      next.disabled = this.transactionPage >= pageCount - 1;
      next.addEventListener('click', () => {
        this.transactionPage += 1;
        this.renderTransactionsTable(this.transactions);
      });
      pagination.append(previous, next);
    }
  },

  setupNewTransactionModal() {
    const openBtn = document.getElementById('open-new-tx-modal');
    const closeBtn = document.getElementById('close-new-tx-modal');
    const backdrop = document.getElementById('new-tx-modal-backdrop');
    const form = document.getElementById('new-tx-form');

    if (!backdrop) return;

    const openModal = () => backdrop.classList.add('active');
    const closeModal = () => backdrop.classList.remove('active');

    if (openBtn) openBtn.addEventListener('click', openModal);
    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) closeModal();
    });

    if (form) {
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const payload = {
          beneficiaryName: document.getElementById('tx-input-buyer').value,
          amount: parseFloat(document.getElementById('tx-input-amount').value),
          currency: document.getElementById('tx-input-currency').value,
          purposeCode: document.getElementById('tx-input-purpose').value.trim()
        };

        try {
          const newTx = await API.request('/transactions', {
            method: 'POST',
            body: JSON.stringify(payload)
          });

          Toast.success(`Transaction ${Utils.escapeHtml(newTx.referenceNumber || newTx.id)} created. Opening workspace.`);
          closeModal();
          setTimeout(() => {
            window.location.href = `transaction.html?id=${encodeURIComponent(newTx.id)}`;
          }, 700);
        } catch (err) {
          Toast.error('Failed to create transaction: ' + err.message);
        }
      });
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  DashboardPage.init();
});
