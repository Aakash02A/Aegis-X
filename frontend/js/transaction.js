/**
 * Aegis-X Transaction Workspace Controller (Stage 9)
 * Orchestrates Summary, Evidence Upload, OCR, AI, Policy, Audit, and Review Notes tabs
 */
import { API } from './api.js';
import { Auth } from './auth.js';
import { Toast } from './notifications.js';
import { Utils } from './utils.js';
import { UploadManager } from './upload.js';
import { OCRViewer } from './ocr.js';
import { AIViewer } from './ai.js';
import { PolicyViewer } from './policy.js';
import { AuditViewer } from './audit.js';

export const TransactionPage = {
  async init() {
    // Guard page
    if (!await Auth.requireUser(['MERCHANT', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR', 'ADMIN'])) return;
    Auth.applyRoleNavigation();

    window.Utils = Utils;

    const urlParams = new URLSearchParams(window.location.search);
    this.transactionId = urlParams.get('id');
    if (!this.transactionId) {
      const main = document.querySelector('main');
      const message = document.createElement('p');
      message.className = 'p-8 text-sm text-ink-soft';
      message.textContent = 'A transaction reference is required to open this workspace.';
      main?.replaceChildren(message);
      return;
    }

    this.setupTabs();
    this.setupNotes();
    this.setupExport();
    this.setupNotifications();
    this.setupWorkflowActions();
    await this.loadPaymentConfiguration();
    await this.loadTransaction();
  },

  setupTabs() {
    const tabButtons = document.querySelectorAll('.aegis-tab-btn');
    tabButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const targetTab = btn.getAttribute('data-tab');
        
        tabButtons.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        document.querySelectorAll('.tab-content-panel').forEach(panel => {
          panel.classList.add('hidden');
        });

        const activePanel = document.getElementById(`tab-${targetTab}`);
        if (activePanel) {
          activePanel.classList.remove('hidden');
        }
      });
    });
  },

  async loadTransaction() {
    try {
      const tx = await API.request(`/transactions/${this.transactionId}`);
      this.currentTransaction = tx;
      this.renderHeader(tx);
      this.renderSummary(tx);

      // Initialize Upload Manager
      UploadManager.init(this.transactionId, async () => {
        const uploadedDocument = UploadManager.lastUploadedDocument;
        if (uploadedDocument?.id) {
          try {
            await API.request(`/documents/${encodeURIComponent(uploadedDocument.id)}/process`, { method: 'POST' });
          } catch (error) {
            Toast.warning(`Evidence was stored, but OCR could not start: ${error.message}`);
          }
        }
        await this.loadAllSubsystems();
      });

      await this.loadAllSubsystems();
    } catch (err) {
      Toast.error('Failed to load transaction: ' + err.message);
    }
  },

  renderHeader(tx) {
    document.getElementById('tx-id-display').textContent = tx.referenceNumber || tx.id;
    document.getElementById('tx-status-badge').innerHTML = Utils.renderStatusBadge(tx.status);
    document.getElementById('tx-amount-display').textContent = Utils.formatCurrency(tx.amount, tx.currency);
    document.getElementById('tx-buyer-display').textContent = tx.beneficiaryName || '—';
    document.getElementById('tx-date-display').textContent = Utils.formatDateTime(tx.createdAt);
  },

  renderSummary(tx) {
    document.getElementById('summary-merchant-id').textContent = tx.merchantId || '—';
    document.getElementById('summary-purpose-code').textContent = tx.purposeCode || '—';
    document.getElementById('summary-currency').textContent = tx.currency || '—';
    document.getElementById('summary-current-state').textContent = tx.status || '—';
    document.getElementById('summary-reference').textContent = tx.referenceNumber || tx.id || '—';
    const canAcceptPayment = tx.status === 'CREATED';
    document.getElementById('local-payment-actions')?.classList.toggle(
      'hidden', !canAcceptPayment || !this.paymentConfiguration?.localTestEnabled
    );
    document.getElementById('razorpay-payment-actions')?.classList.toggle(
      'hidden', !canAcceptPayment || !this.paymentConfiguration?.checkoutEnabled
    );
    document.getElementById('document-request-form')?.classList.toggle('hidden', tx.status !== 'EVIDENCE_PENDING');
  },

  setupWorkflowActions() {
    document.getElementById('start-razorpay-payment')?.addEventListener('click', () => this.startRazorpayPayment());

    document.getElementById('confirm-local-payment')?.addEventListener('click', async () => {
      try {
        await API.request(`/transactions/${encodeURIComponent(this.transactionId)}/payment/confirm`, { method: 'POST' });
        Toast.success('Local test payment confirmed. Evidence collection is ready.');
        await this.loadAllSubsystems();
      } catch (error) {
        Toast.error(`Local payment confirmation failed: ${error.message}`);
      }
    });

    document.getElementById('document-request-form')?.addEventListener('submit', async event => {
      event.preventDefault();
      const phone = document.getElementById('document-request-phone').value.trim();
      const result = document.getElementById('document-request-result');
      result.textContent = '';
      try {
        const notification = await API.request(`/transactions/${encodeURIComponent(this.transactionId)}/notifications/document-request`, {
          method: 'POST',
          body: JSON.stringify({ recipientPhone: phone })
        });
        result.textContent = notification?.status === 'SENT'
          ? 'WhatsApp request sent.'
          : `Request recorded with status ${notification?.status || 'unknown'}; check notification history for details.`;
        await this.loadNotifications();
      } catch (error) {
        result.textContent = `Could not request documents: ${error.message}`;
      }
    });
  },

  async loadPaymentConfiguration() {
    if (Auth.getUser()?.role !== 'MERCHANT') {
      this.paymentConfiguration = { checkoutEnabled: false, localTestEnabled: false };
      return;
    }
    try {
      this.paymentConfiguration = await API.request('/v1/payments/configuration');
    } catch (error) {
      this.paymentConfiguration = { checkoutEnabled: false, localTestEnabled: false };
      Toast.warning(`Payment options could not be loaded: ${error.message}`);
    }
  },

  async startRazorpayPayment() {
    const button = document.getElementById('start-razorpay-payment');
    if (button) button.disabled = true;
    try {
      const order = await API.request('/v1/payments/orders', {
        method: 'POST',
        body: JSON.stringify({ transactionId: this.transactionId })
      });
      await this.loadRazorpayCheckout();
      const checkout = new window.Razorpay({
        key: order.keyId,
        amount: order.amount,
        currency: order.currency,
        name: 'Aegis-X',
        order_id: order.orderId,
        handler: () => {
          Toast.success('Payment submitted. The transaction will update after server verification.');
          if (button) button.disabled = false;
        },
        modal: {
          ondismiss: () => {
            if (button) button.disabled = false;
          }
        }
      });
      checkout.on('payment.failed', response => {
        const description = response?.error?.description || 'The payment was not completed.';
        Toast.error(description);
        if (button) button.disabled = false;
      });
      checkout.open();
    } catch (error) {
      Toast.error(`Could not start payment: ${error.message}`);
      if (button) button.disabled = false;
    }
  },

  loadRazorpayCheckout() {
    if (window.Razorpay) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://checkout.razorpay.com/v1/checkout.js';
      script.onload = resolve;
      script.onerror = () => reject(new Error('Razorpay Checkout could not be loaded.'));
      document.head.appendChild(script);
    });
  },

  async loadAllSubsystems() {
    try {
      this.currentTransaction = await API.request(`/transactions/${this.transactionId}`);
      this.renderHeader(this.currentTransaction);
      this.renderSummary(this.currentTransaction);
    } catch (error) {
      Toast.error('Could not refresh transaction state: ' + error.message);
    }

    let documents = [];
    try {
      const result = await API.request(`/transactions/${this.transactionId}/documents`);
      documents = Array.isArray(result) ? result : [];
    } catch {
      this.renderEvidence([], true);
    }
    this.documents = documents;
    this.renderEvidence(documents);

    const extractions = (await Promise.all(documents.map(async document => {
      try {
        return await API.request(`/documents/${encodeURIComponent(document.id)}/extraction`);
      } catch {
        return null;
      }
    }))).filter(Boolean);
    this.extractions = extractions;
    OCRViewer.render('tab-ocr', { documents, extractions });

    let aiData = await this.getSubsystemResult(`/transactions/${this.transactionId}/ai-analysis`);
    if (!aiData && extractions.length) {
      try {
        await API.request(`/transactions/${this.transactionId}/ai-analysis`, { method: 'POST' });
        aiData = await this.getSubsystemResult(`/transactions/${this.transactionId}/ai-analysis`);
      } catch (error) {
        Toast.error('AI analysis could not be started: ' + error.message);
      }
    }
    AIViewer.render('tab-ai', aiData);

    let policyData = await this.getSubsystemResult(`/transactions/${this.transactionId}/policy-evaluation`);
    if (!policyData && aiData) {
      try {
        await API.request(`/transactions/${this.transactionId}/policy-evaluation`, { method: 'POST' });
        policyData = await this.getSubsystemResult(`/transactions/${this.transactionId}/policy-evaluation`);
      } catch (error) {
        Toast.error('Policy evaluation could not be started: ' + error.message);
      }
    }
    PolicyViewer.render('tab-policy', policyData);

    try {
      const auditData = await API.request(`/transactions/${this.transactionId}/audit`);
      AuditViewer.render('tab-audit', auditData);
    } catch {
      AuditViewer.render('tab-audit', null);
    }

    await this.loadNotes();
  },

  async getSubsystemResult(endpoint) {
    try {
      return await API.request(endpoint);
    } catch {
      return null;
    }
  },

  renderEvidence(documents, failed = false) {
    const container = document.getElementById('evidence-list');
    if (!container) return;
    container.replaceChildren();
    if (!documents.length) {
      const empty = document.createElement('p');
      empty.className = 'text-xs text-ink-soft py-4';
      empty.textContent = failed ? 'Evidence records could not be loaded.' : 'No evidence has been uploaded.';
      container.append(empty);
      return;
    }
    documents.forEach(item => {
      const row = document.createElement('div');
      row.className = 'flex justify-between gap-4 border-t border-ink/10 py-3 text-xs';
      const name = document.createElement('span');
      name.className = 'font-medium text-ink break-all';
      name.textContent = item.originalFilename || 'Unnamed document';
      const status = document.createElement('span');
      status.className = 'text-ink-soft shrink-0';
      status.textContent = `${item.documentType || '—'} · ${item.documentStatus || '—'}`;
      row.append(name, status);
      container.append(row);
    });
  },

  async loadNotes() {
    const container = document.getElementById('notes-list');
    if (!container) return;

    try {
      const result = await API.request(`/transactions/${this.transactionId}/notes?page=0&size=50`);
      const notes = Array.isArray(result) ? result : result?.content || [];
      container.replaceChildren();
      if (!notes.length) container.append(Utils.textElement('p', 'p-6 text-center text-xs text-ink-soft bg-paper-dim', 'No review notes have been recorded.'));
      notes.forEach(note => {
        const entry = document.createElement('article');
        entry.className = 'p-4 bg-paper-dim border border-ink/10 space-y-1.5';
        entry.append(
          Utils.textElement('p', 'text-xs font-semibold text-ink font-serif', note.authorName),
          Utils.textElement('p', 'text-[11px] text-ink-soft font-mono-data', Utils.formatDateTime(note.createdAt)),
          Utils.textElement('p', 'text-xs text-ink leading-relaxed', note.noteText)
        );
        container.append(entry);
      });
    } catch (e) {
      container.textContent = 'Review notes could not be loaded.';
    }
  },

  setupNotes() {
    const form = document.getElementById('new-note-form');
    if (!form) return;
    const user = Auth.getUser();
    if (!['COMPLIANCE_OFFICER', 'ADMIN'].includes(user?.role)) {
      form.hidden = true;
      return;
    }

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = document.getElementById('note-input');
      const text = input.value.trim();
      if (!text) return;

      try {
        await API.request(`/transactions/${this.transactionId}/notes`, {
          method: 'POST',
          body: JSON.stringify({ noteText: text })
        });
        input.value = '';
        Toast.success('Review note added.');
        await this.loadNotes();
      } catch (err) {
        Toast.error('Failed to post note: ' + err.message);
      }
    });
  },

  setupExport() {
    document.getElementById('export-audit')?.addEventListener('click', async () => {
      try {
        const audit = await API.request(`/transactions/${this.transactionId}/audit`);
        const blob = new Blob([JSON.stringify(audit, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `aegis-audit-${this.transactionId}.json`;
        link.click();
        URL.revokeObjectURL(url);
      } catch (error) {
        Toast.error('Audit export failed: ' + error.message);
      }
    });
  },

  setupNotifications() {
    const button = document.getElementById('notifications-toggle');
    const panel = document.getElementById('transaction-notifications-panel');
    button?.addEventListener('click', async () => {
      const open = panel.classList.toggle('hidden') === false;
      button.setAttribute('aria-expanded', String(open));
      if (open) await this.loadNotifications();
    });
  },

  async loadNotifications() {
    const container = document.getElementById('transaction-notifications');
    if (!container) return;
    container.replaceChildren();
    try {
      const notifications = await API.request(`/transactions/${this.transactionId}/notifications`);
      if (!Array.isArray(notifications) || !notifications.length) {
        container.append(Utils.textElement('p', 'text-xs text-ink-soft py-3', 'No notifications have been recorded.'));
        return;
      }
      notifications.forEach(notification => {
        const entry = document.createElement('article');
        entry.className = 'border-t border-ink/10 py-3';
        entry.append(
          Utils.textElement('p', 'text-xs font-semibold text-ink', `${notification.channel || 'Channel not reported'} · ${notification.direction || 'Direction not reported'}`),
          Utils.textElement('p', 'text-xs text-ink-soft mt-1', `Status: ${notification.status || 'Not reported'}`),
          Utils.textElement('p', 'text-[11px] text-ink-soft mt-1', Utils.formatDateTime(notification.createdAt)),
          Utils.textElement('p', 'text-[11px] text-ink-soft mt-1', notification.failureReason)
        );
        container.append(entry);
      });
    } catch (error) {
      container.append(Utils.textElement('p', 'text-xs text-rust py-3', `Notification history unavailable: ${error.message}`));
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  TransactionPage.init();
});
