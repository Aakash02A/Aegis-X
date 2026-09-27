/**
 * Aegis-X AI Regulatory Reasoner Module (Amble & Tonsor Editorial Style)
 */
import { Utils } from './utils.js';

export const AIViewer = {
  render(containerId, aiData) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.replaceChildren();
    if (!aiData) {
      container.append(Utils.textElement('p', 'p-8 text-sm text-ink-soft', 'AI analysis is pending an OCR extraction.'));
      return;
    }

    container.append(
      Utils.textElement('h3', 'font-serif text-2xl text-ink', 'AI regulatory analysis'),
      Utils.textElement('p', 'text-xs text-ink-soft mt-2', aiData.summary),
      Utils.textElement('p', 'text-xs text-ink-soft mt-2', `Confidence: ${aiData.overallConfidence == null ? 'Not reported' : `${aiData.overallConfidence}%`}`),
      Utils.textElement('p', 'text-xs text-ink-soft', `Processing duration: ${aiData.processingDurationMs == null ? 'Not reported' : `${aiData.processingDurationMs} ms`}`),
      Utils.textElement('p', 'text-xs text-ink-soft', `Model: ${aiData.modelName || 'Not reported'} · Prompt: ${aiData.promptVersion || 'Not reported'}`)
    );

    const findings = document.createElement('section');
    findings.className = 'editorial-card bg-white p-5 mt-5';
    findings.append(Utils.textElement('h4', 'font-serif text-lg text-ink', 'Findings and risk indicators'));
    const findingList = document.createElement('ul');
    findingList.className = 'mt-3 space-y-3';
    (aiData.findings || []).forEach(finding => {
      const item = document.createElement('li');
      item.className = 'border-t border-ink/10 pt-3';
      item.append(
        Utils.textElement('p', 'text-sm font-semibold text-ink', `${finding.type || 'Finding'} · ${finding.severity || 'Severity not reported'}`),
        Utils.textElement('p', 'text-xs text-ink-soft mt-1', finding.message),
        Utils.textElement('p', 'text-xs text-ink-soft mt-1', `Confidence: ${finding.confidence == null ? 'Not reported' : `${finding.confidence}%`}`),
        Utils.textElement('p', 'text-xs text-ink-soft mt-1', finding.evidence)
      );
      findingList.append(item);
    });
    if (!findingList.childElementCount) findingList.append(Utils.textElement('li', 'text-xs text-ink-soft mt-3', 'No findings were returned.'));
    findings.append(findingList);
    container.append(findings);

    const advisory = document.createElement('p');
    advisory.className = 'p-4 mt-5 bg-paper-dim border border-ink/15 text-xs text-ink';
    advisory.textContent = 'AI findings are advisory only. Final decisions are made by the deterministic policy engine.';
    container.append(advisory);
  }
};
