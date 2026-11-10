/**
 * Aegis-X OCR Visualizer Module (Amble & Tonsor Editorial Style)
 */
import { Utils } from '../core/formatters.js';

export const OCRViewer = {
  render(containerId, ocrData) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.replaceChildren();
    const documents = ocrData?.documents || [];
    const extractions = ocrData?.extractions || [];
    if (!documents.length) {
      container.append(Utils.textElement('p', 'p-8 text-sm text-ink-soft', 'Upload evidence to start OCR processing.'));
      return;
    }

    const heading = Utils.textElement('h3', 'font-serif text-xl text-ink', 'OCR extraction results');
    container.append(heading);
    documents.forEach(evidence => {
      const extraction = extractions.find(item => item.documentId === evidence.id);
      const panel = document.createElement('section');
      panel.className = 'editorial-card bg-white p-5 mt-4';
      panel.append(
        Utils.textElement('h4', 'font-semibold text-sm text-ink', evidence.originalFilename),
        Utils.textElement('p', 'text-xs text-ink-soft mt-1', `${evidence.documentType || '—'} · ${extraction?.status || evidence.documentStatus || 'Processing'}`),
        Utils.textElement('p', 'text-xs text-ink-soft mt-2', `Confidence: ${extraction?.confidence == null ? 'Not reported' : `${extraction.confidence}%`}`),
        Utils.textElement('p', 'text-xs text-ink-soft', `Processing duration: Not provided by the extraction API`)
      );

      const fields = extraction?.extractedData;
      if (fields && typeof fields === 'object') {
        const table = document.createElement('dl');
        table.className = 'grid grid-cols-1 sm:grid-cols-2 gap-3 mt-4';
        Object.entries(fields).forEach(([key, value]) => {
          const field = document.createElement('div');
          field.className = 'bg-paper-dim p-3 rounded-sm';
          field.append(
            Utils.textElement('dt', 'text-[10px] uppercase text-ink-soft font-semibold', key),
            Utils.textElement('dd', 'text-xs text-ink font-mono-data mt-1 break-all', typeof value === 'object' ? JSON.stringify(value) : value)
          );
          table.append(field);
        });
        panel.append(table);
      } else {
        panel.append(Utils.textElement('p', 'text-xs text-ink-soft mt-3', 'Extraction fields are not available yet.'));
      }
      panel.append(Utils.textElement('p', 'text-xs text-ink-soft mt-4', 'Missing-field details are not provided by the extraction response.'));
      container.append(panel);
    });
  }
};
