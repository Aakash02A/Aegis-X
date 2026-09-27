/**
 * Aegis-X Deterministic Policy Engine Viewer (Amble & Tonsor Editorial Style)
 */
import { Utils } from './utils.js';

export const PolicyViewer = {
  render(containerId, policyData) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.replaceChildren();
    if (!policyData) {
      container.append(Utils.textElement('p', 'p-8 text-sm text-ink-soft', 'Policy evaluation is pending AI analysis.'));
      return;
    }

    const rules = policyData.rules || [];
    const passed = rules.filter(rule => rule.passed === true).length;
    const summary = document.createElement('section');
    summary.className = 'editorial-card bg-white p-5';
    summary.append(
      Utils.textElement('h3', 'font-serif text-2xl text-ink', 'Deterministic policy evaluation'),
      Utils.textElement('p', 'text-sm font-semibold text-ink mt-3', `Decision: ${policyData.decision || 'Not reported'}`),
      Utils.textElement('p', 'text-xs text-ink-soft mt-1', `Overall score: ${policyData.overallScore == null ? 'Not reported' : `${policyData.overallScore}%`}`),
      Utils.textElement('p', 'text-xs text-ink-soft', `Rules passed: ${passed} of ${rules.length}`)
    );
    container.append(summary);

    const table = document.createElement('table');
    table.className = 'w-full text-left text-xs mt-5 bg-white';
    const head = document.createElement('thead');
    const headerRow = document.createElement('tr');
    ['Rule', 'Result', 'Reason', 'Evidence'].forEach(label => {
      const cell = Utils.textElement('th', 'p-3 bg-paper-dim text-ink-soft font-semibold', label);
      headerRow.append(cell);
    });
    head.append(headerRow);
    table.append(head);
    const body = document.createElement('tbody');
    rules.forEach(rule => {
      const row = document.createElement('tr');
      row.className = 'border-t border-ink/10';
      row.append(
        Utils.textElement('td', 'p-3 font-semibold text-ink', rule.rule),
        Utils.textElement('td', `p-3 font-semibold ${rule.passed ? 'text-olive-deep' : 'text-rust'}`, rule.passed === true ? 'PASS' : rule.passed === false ? 'FAIL' : 'NOT REPORTED'),
        Utils.textElement('td', 'p-3 text-ink-soft', rule.reason),
        Utils.textElement('td', 'p-3 text-ink-soft', rule.evidence)
      );
      body.append(row);
    });
    table.append(body);
    if (rules.length) {
      const wrapper = document.createElement('div');
      wrapper.className = 'overflow-x-auto';
      wrapper.append(table);
      container.append(wrapper);
    } else {
      container.append(Utils.textElement('p', 'p-5 text-sm text-ink-soft', 'No rule results were returned.'));
    }
  }
};
