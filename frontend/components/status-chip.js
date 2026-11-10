const STATUS_PRESENTATION = Object.freeze({
  APPROVED: ['badge-approved', 'Approved', 'fa-solid fa-circle-check'],
  SETTLED: ['badge-approved', 'Settled', 'fa-solid fa-circle-check'],
  COMPLETED: ['badge-approved', 'Settled', 'fa-solid fa-circle-check'],
  RECONCILED: ['badge-approved', 'Settled', 'fa-solid fa-circle-check'],
  COMPLIANCE_HOLD: ['badge-hold', 'Compliance Hold', 'fa-solid fa-shield-halved'],
  FAILED: ['badge-hold', 'Failed', 'fa-solid fa-circle-xmark'],
  OCR_FAILED: ['badge-hold', 'OCR Failed', 'fa-solid fa-circle-xmark'],
  AI_ANALYSIS_FAILED: ['badge-hold', 'AI Analysis Failed', 'fa-solid fa-circle-xmark'],
  PAYMENT_FAILED: ['badge-hold', 'Payment Failed', 'fa-solid fa-circle-xmark'],
  REJECTED: ['badge-hold', 'Rejected', 'fa-solid fa-circle-xmark'],
  PROCESSING: ['badge-brass', 'Processing', 'fa-solid fa-arrows-rotate'],
  EVIDENCE_PENDING: ['badge-neutral', 'Pending Evidence', 'fa-solid fa-file-lines'],
  OCR_PROCESSING: ['badge-neutral', 'OCR Extracting', 'fa-solid fa-microchip'],
  AI_ANALYSIS: ['badge-neutral', 'AI Reasoning', 'fa-solid fa-wand-magic-sparkles'],
  POLICY_EVALUATION: ['badge-neutral', 'Policy Check', 'fa-solid fa-scale-balanced'],
  PAYMENT_RECEIVED: ['badge-neutral', 'Payment Received', 'fa-solid fa-circle-check'],
  CREATED: ['badge-neutral', 'Created', 'fa-solid fa-clock']
});

function escape(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
}

export function renderStatusChip(status) {
  const key = String(status || 'UNKNOWN').toUpperCase();
  const [tone, label, icon] = STATUS_PRESENTATION[key] || ['badge-neutral', key, 'fa-solid fa-circle-info'];
  return `<span class="editorial-badge ${tone}"><i class="${icon} w-3 h-3 inline" aria-hidden="true"></i>${escape(label)}</span>`;
}
