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
