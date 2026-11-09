/**
 * Aegis-X Merchant Dashboard Controller (Stage 7)
 * Handles metrics, quick actions, new transaction modal, and live transaction stream
 */
import { API } from '../core/api.js';
import { requireUser } from '../core/guards.js';
import { AppShell } from '../components/app-shell.js';
import { Toast } from '../components/toast.js';
import { Utils } from '../core/formatters.js';
import { renderStatusChip } from '../components/status-chip.js';

export const DashboardPage = {
  async init() {
    if (!await requireUser(['MERCHANT'])) return;
    AppShell.mount();
    this.setupNewTransactionModal();
    this.setupSearch();
    await this.loadDashboardData();
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
      const tbody = document.getElementById('recent-tx-tbody');
      if (tbody) {
        const cell = Utils.textElement('td', 'text-center py-12 text-ink-soft', 'Transactions could not be loaded. Check that the backend and database are running, then retry.');
        cell.colSpan = 6;
        const row = document.createElement('tr');
        row.append(cell);
        tbody.replaceChildren(row);
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'aegis-btn-outline py-1.5 px-3 text-xs';
        retry.textContent = 'Retry';
        retry.addEventListener('click', () => this.loadDashboardData());
        document.getElementById('transaction-pagination')?.replaceChildren(retry);
      }
      this.renderTransactionCharts([]);
      this.renderProcessingChart(null);
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
    if (!transactions.length) {
      volumeContainer.replaceChildren(Utils.textElement('p', 'text-sm text-ink-soft', 'Transaction volume will appear after your first remittance.'));
      approvalContainer.replaceChildren(Utils.textElement('p', 'text-sm text-ink-soft', 'Approval metrics will appear after a policy decision.'));
      return;
    }
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
    if (tbody.dataset.navigationBound !== 'true') {
      tbody.addEventListener('click', (event) => {
        if (event.target.closest('a, button')) return;
        const row = event.target.closest('tr[data-transaction-id]');
        if (row) window.location.href = `transaction.html?id=${encodeURIComponent(row.dataset.transactionId)}`;
      });
      tbody.dataset.navigationBound = 'true';
    }
    this.transactionPage = this.transactionPage || 0;
    const pageSize = 20;
    const query = (this.searchQuery || '').trim().toLocaleLowerCase();
    const matchingTransactions = query ? transactions.filter(transaction => [
      transaction.referenceNumber, transaction.id, transaction.beneficiaryName, transaction.purposeCode, transaction.status
    ].some(value => String(value || '').toLocaleLowerCase().includes(query))) : transactions;
    const pageCount = Math.max(1, Math.ceil(matchingTransactions.length / pageSize));
    this.transactionPage = Math.min(this.transactionPage, pageCount - 1);
    const visibleTransactions = matchingTransactions.slice(this.transactionPage * pageSize, (this.transactionPage + 1) * pageSize);

    if (matchingTransactions.length === 0) {
      const message = query ? 'No transactions match your search. Try a different name, reference, or purpose code.' : 'No transactions yet. Create a remittance to begin the review workflow.';
      const cell = Utils.textElement('td', 'text-center py-12 text-ink-soft', message);
      cell.colSpan = 6;
      const row = document.createElement('tr');
      row.append(cell);
      tbody.replaceChildren(row);
      this.renderTransactionPagination(pageCount, matchingTransactions.length);
      return;
    }

    tbody.innerHTML = visibleTransactions.map(tx => `
      <tr data-transaction-id="${Utils.escapeHtml(tx.id)}" class="cursor-pointer hover:bg-paper-dim/60 transition-colors">
        <td class="font-mono-data font-semibold text-ink py-3.5 px-2">${Utils.escapeHtml(tx.referenceNumber || tx.id)}</td>
        <td class="font-serif text-sm text-ink font-medium py-3.5 px-2">${Utils.escapeHtml(tx.beneficiaryName || '—')}</td>
        <td class="font-mono-data text-ink font-medium py-3.5 px-2">${Utils.formatCurrency(tx.amount, tx.currency)}</td>
        <td class="font-mono-data text-ink-soft py-3.5 px-2">${Utils.escapeHtml(tx.purposeCode || '—')}</td>
        <td class="py-3.5 px-2">${renderStatusChip(tx.status)}</td>
        <td class="text-right py-3.5 px-2">
          <a href="transaction.html?id=${encodeURIComponent(tx.id)}" class="editorial-link text-xs">
            Inspect →
          </a>
        </td>
      </tr>
    `).join('');

    this.renderTransactionPagination(pageCount, matchingTransactions.length);
  },

  renderTransactionPagination(pageCount, total) {
    const pagination = document.getElementById('transaction-pagination');
    if (pagination) {
      pagination.replaceChildren();
      pagination.append(
        Utils.textElement('span', 'text-xs text-ink-soft', `${this.transactionPage + 1} / ${pageCount} · ${total} ${total === 1 ? 'transaction' : 'transactions'}`)
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

  setupSearch() {
    document.getElementById('dashboard-search')?.addEventListener('input', event => {
      this.searchQuery = event.target.value;
      this.transactionPage = 0;
      this.renderTransactionsTable(this.transactions || []);
    });
  },

  setupNewTransactionModal() {
    const openBtn = document.getElementById('open-new-tx-modal');
    const closeBtn = document.getElementById('close-new-tx-modal');
    const cancelBtn = document.getElementById('cancel-new-tx-modal');
    const backdrop = document.getElementById('new-tx-modal-backdrop');
    const form = document.getElementById('new-tx-form');

    if (!backdrop) return;

    const openModal = () => {
      backdrop.classList.add('active');
      document.body.classList.add('modal-open');
      document.getElementById('tx-input-buyer')?.focus();
    };
    const closeModal = () => {
      backdrop.classList.remove('active');
      document.body.classList.remove('modal-open');
      openBtn?.focus();
    };

    if (openBtn) openBtn.addEventListener('click', openModal);
    if (closeBtn) closeBtn.addEventListener('click', closeModal);
    if (cancelBtn) cancelBtn.addEventListener('click', closeModal);

    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) closeModal();
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && backdrop.classList.contains('active')) closeModal();
    });

    if (form) {
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const submitButton = form.querySelector('button[type="submit"]');
        const payload = {
          beneficiaryName: document.getElementById('tx-input-buyer').value.trim(),
          amount: parseFloat(document.getElementById('tx-input-amount').value),
          currency: document.getElementById('tx-input-currency').value,
          purposeCode: document.getElementById('tx-input-purpose').value.trim()
        };

        if (!payload.beneficiaryName || !payload.purposeCode || !Number.isFinite(payload.amount) || payload.amount <= 0) {
          Toast.error('Enter a beneficiary, positive amount, and purpose code.');
          return;
        }

        if (submitButton) {
          submitButton.disabled = true;
          submitButton.textContent = 'Creating transaction…';
        }
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
        } finally {
          if (submitButton) {
            submitButton.disabled = false;
            submitButton.innerHTML = '<span>Ingest &amp; Queue Compliance</span><i class="fa-solid fa-arrow-right w-3.5 h-3.5"></i>';
          }
        }
      });
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  DashboardPage.init();
});
