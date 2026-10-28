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
      APPROVED: { class: 'badge-approved', label: 'Approved', icon: 'fa-solid fa-circle-check' },
      COMPLIANCE_HOLD: { class: 'badge-hold', label: 'Compliance Hold', icon: 'fa-solid fa-shield-halved' },
      EVIDENCE_PENDING: { class: 'badge-neutral', label: 'Pending Evidence', icon: 'fa-solid fa-file-lines' },
      OCR_PROCESSING: { class: 'badge-neutral', label: 'OCR Extracting', icon: 'fa-solid fa-microchip' },
      AI_ANALYSIS: { class: 'badge-neutral', label: 'AI Reasoning', icon: 'fa-solid fa-wand-magic-sparkles' },
      POLICY_EVALUATION: { class: 'badge-neutral', label: 'Policy Check', icon: 'fa-solid fa-scale-balanced' },
      FAILED: { class: 'badge-hold', label: 'Failed', icon: 'fa-solid fa-circle-xmark' },
      OCR_FAILED: { class: 'badge-hold', label: 'OCR Failed', icon: 'fa-solid fa-circle-xmark' },
      AI_ANALYSIS_FAILED: { class: 'badge-hold', label: 'AI Analysis Failed', icon: 'fa-solid fa-circle-xmark' },
      PAYMENT_RECEIVED: { class: 'badge-neutral', label: 'Payment Received', icon: 'fa-solid fa-circle-check' },
      PAYMENT_FAILED: { class: 'badge-hold', label: 'Payment Failed', icon: 'fa-solid fa-circle-xmark' },
      REJECTED: { class: 'badge-hold', label: 'Rejected', icon: 'fa-solid fa-circle-xmark' },
      CREATED: { class: 'badge-neutral', label: 'Created', icon: 'fa-solid fa-clock' }
    };

    const c = config[s] || { class: 'badge-neutral', label: this.escapeHtml(s), icon: 'fa-solid fa-circle-info' };
    return `<span class="editorial-badge ${c.class}">
      <i class="${c.icon} w-3 h-3 inline"></i>
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
  }
};
