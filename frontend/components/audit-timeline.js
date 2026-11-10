/**
 * Aegis-X Cryptographic Audit Timeline Module (Amble & Tonsor Editorial Style)
 */
import { Utils } from '../core/formatters.js';

export const AuditViewer = {
  render(containerId, auditEvents) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.replaceChildren();
    const events = Array.isArray(auditEvents) ? auditEvents : auditEvents?.events || [];
    container.append(Utils.textElement('h3', 'font-serif text-2xl text-ink mb-4', 'Audit timeline'));
    if (!events.length) {
      container.append(Utils.textElement('p', 'p-6 editorial-card bg-white text-sm text-ink-soft', 'No audit events were returned for this transaction.'));
      return;
    }

    const timeline = document.createElement('ol');
    timeline.className = 'space-y-3';
    [...events].sort((left, right) => new Date(left.createdAt) - new Date(right.createdAt)).forEach(event => {
      const item = document.createElement('li');
      item.className = 'editorial-card bg-white p-4';
      item.append(
        Utils.textElement('p', 'font-serif text-base font-semibold text-ink', event.eventType),
        Utils.textElement('p', 'text-xs text-ink-soft mt-1', `${Utils.formatDateTime(event.createdAt)} · ${event.actorType || 'Actor not reported'}${event.actorId ? ` (${event.actorId})` : ''}`),
        Utils.textElement('p', 'text-xs text-ink-soft mt-3', `State: ${event.previousState || '—'} → ${event.newState || '—'}`),
        Utils.textElement('p', 'text-[11px] text-ink-soft font-mono-data mt-2 break-all', `Correlation ID: ${event.correlationId || 'Not reported'}`)
      );
      timeline.append(item);
    });
    container.append(timeline);
  }
};
