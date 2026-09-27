/**
 * Aegis-X Frontend Utility Library (Amble & Tonsor Editorial Style)
 */

export const Utils = {
  textElement(tagName, className, value) {
    const element = document.createElement(tagName);
    if (className) element.className = className;
    element.textContent = value == null || value === '' ? '—' : String(value);
    return element;
  },

  escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, character => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    })[character]);
  },

  formatCurrency(amount, currency = 'INR') {
    if (amount === undefined || amount === null || !Number.isFinite(Number(amount))) return '—';
    try {
      const symbols = { INR: '₹', USD: '$', EUR: '€', GBP: '£' };
      const symbol = symbols[currency] || currency + ' ';
      return `${symbol}${Number(amount).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
      })}`;
    } catch {
      return '—';
    }
  },

  formatDateTime(isoString) {
    if (!isoString) return '—';
    try {
      const date = new Date(isoString);
      return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: true
      });
    } catch {
      return isoString;
    }
  },

  timeAgo(isoString) {
    if (!isoString) return '—';
    const seconds = Math.floor((new Date() - new Date(isoString)) / 1000);
    if (seconds < 60) return 'Just now';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  },

  renderStatusBadge(status) {
    const s = (status || 'UNKNOWN').toUpperCase();
    const config = {
      APPROVED: { class: 'badge-approved', label: 'Approved', icon: 'check-circle' },
      COMPLIANCE_HOLD: { class: 'badge-hold', label: 'Compliance Hold', icon: 'shield-alert' },
      EVIDENCE_PENDING: { class: 'badge-neutral', label: 'Pending Evidence', icon: 'file-text' },
      OCR_PROCESSING: { class: 'badge-neutral', label: 'OCR Extracting', icon: 'cpu' },
      AI_ANALYSIS: { class: 'badge-neutral', label: 'AI Reasoning', icon: 'sparkles' },
      POLICY_EVALUATION: { class: 'badge-neutral', label: 'Policy Check', icon: 'scale' },
      FAILED: { class: 'badge-hold', label: 'Failed', icon: 'x-circle' },
      OCR_FAILED: { class: 'badge-hold', label: 'OCR Failed', icon: 'x-circle' },
      AI_ANALYSIS_FAILED: { class: 'badge-hold', label: 'AI Analysis Failed', icon: 'x-circle' },
      PAYMENT_RECEIVED: { class: 'badge-neutral', label: 'Payment Received', icon: 'circle-check' },
      PAYMENT_FAILED: { class: 'badge-hold', label: 'Payment Failed', icon: 'x-circle' },
      REJECTED: { class: 'badge-hold', label: 'Rejected', icon: 'x-circle' },
      CREATED: { class: 'badge-neutral', label: 'Created', icon: 'clock' }
    };

    const c = config[s] || { class: 'badge-neutral', label: this.escapeHtml(s), icon: 'info' };
    return `<span class="editorial-badge ${c.class}">
      <i data-lucide="${c.icon}" class="w-3 h-3 inline"></i>
      ${c.label}
    </span>`;
  },

  getRiskScoreBadge(score) {
    const num = Number(score) || 0;
    let badgeClass = 'text-olive-deep bg-olive-deep/10 border-olive-deep/30';
    let label = 'Low Risk';
    if (num > 70) {
      badgeClass = 'text-rust bg-rust/10 border-rust/30';
      label = 'High Risk';
    } else if (num > 35) {
      badgeClass = 'text-brass bg-brass/10 border-brass/30';
      label = 'Moderate Risk';
    }
    return `<span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border ${badgeClass} font-mono-data">
      ${num}% · ${label}
    </span>`;
  },

  copyToClipboard(text, message = 'Copied to clipboard!') {
    navigator.clipboard.writeText(text).then(() => {
      if (window.Toast) window.Toast.success(message);
    }).catch(() => {
      const el = document.createElement('textarea');
      el.value = text;
      document.body.appendChild(el);
      el.select();
      document.execCommand('copy');
      document.body.removeChild(el);
      if (window.Toast) window.Toast.success(message);
    });
  },

  initIcons() {
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
      window.lucide.createIcons();
    }
  }
};
