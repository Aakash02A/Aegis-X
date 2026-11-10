/**
 * Aegis-X Evidence Upload Controller (Amble & Tonsor Editorial Style)
 */
import { API } from '../core/api.js';
import { Toast } from './toast.js';
import { Utils } from '../core/formatters.js';

export const UploadManager = {
  init(transactionId, onUploadComplete) {
    this.transactionId = transactionId;
    this.onUploadComplete = onUploadComplete;
    this.dropzone = document.getElementById('evidence-dropzone');
    this.fileInput = document.getElementById('evidence-file-input');
    this.documentTypeInput = document.getElementById('evidence-document-type');
    this.previewContainer = document.getElementById('upload-preview');
    this.selectedFile = null;

    if (!this.dropzone || !this.fileInput) return;

    this.bindEvents();
  },

  bindEvents() {
    this.dropzone.addEventListener('click', (e) => {
      if (e.target.closest('#upload-action-btn') || e.target.closest('#remove-file-btn')) return;
      this.fileInput.click();
    });

    this.fileInput.addEventListener('change', (e) => {
      if (e.target.files && e.target.files[0]) {
        this.handleFileSelected(e.target.files[0]);
      }
    });

    ['dragenter', 'dragover'].forEach(eventName => {
      this.dropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.dropzone.classList.add('dragover');
      }, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
      this.dropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.dropzone.classList.remove('dragover');
      }, false);
    });

    this.dropzone.addEventListener('drop', (e) => {
      const dt = e.dataTransfer;
      if (dt.files && dt.files[0]) {
        this.handleFileSelected(dt.files[0]);
      }
    });
  },

  handleFileSelected(file) {
    const validTypes = ['application/pdf', 'image/png', 'image/jpeg', 'image/jpg'];
    if (!validTypes.includes(file.type)) {
      Toast.error('Invalid format. Please upload PDF, PNG, or JPG documents.');
      return;
    }

    if (file.size > 20 * 1024 * 1024) {
      Toast.error('File size exceeds 20MB limit.');
      return;
    }

    this.selectedFile = file;
    this.renderPreview(file);
  },

  renderPreview(file) {
    const sizeMb = (file.size / (1024 * 1024)).toFixed(2);
    const isPdf = file.type === 'application/pdf';

    this.previewContainer.innerHTML = `
      <div class="editorial-card p-4 bg-paper-dim flex items-center justify-between gap-4 animate-blur-reveal">
        <div class="flex items-center gap-3 overflow-hidden">
          <div class="p-2.5 rounded-full bg-paper text-rust border border-ink/10 shrink-0">
            <i class="${isPdf ? 'fa-solid fa-file-lines' : 'fa-solid fa-image'} w-5 h-5"></i>
          </div>
          <div class="truncate">
            <p class="text-xs font-semibold text-ink truncate">${Utils.escapeHtml(file.name)}</p>
            <p class="text-[11px] text-ink-soft font-mono-data">${sizeMb} MB · ${file.type}</p>
          </div>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <button id="upload-action-btn" class="aegis-btn-rust py-1.5 px-4 text-xs font-medium">
            <i class="fa-solid fa-cloud-arrow-up w-3.5 h-3.5"></i>
            Upload &amp; Extract
          </button>
          <button id="remove-file-btn" class="p-1.5 text-ink-soft hover:text-rust transition-colors" title="Remove">
            <i class="fa-solid fa-trash-can w-4 h-4"></i>
          </button>
        </div>
      </div>
      <!-- Progress Bar Container -->
      <div id="upload-progress-container" class="hidden mt-3 editorial-card p-4 bg-paper-dim">
        <div class="flex justify-between text-xs mb-1.5">
          <span class="text-rust font-medium" id="upload-status-label">Uploading Evidence...</span>
          <span class="text-ink-soft font-mono-data" id="upload-pct-label">0%</span>
        </div>
        <div class="w-full bg-paper rounded-full h-1.5 overflow-hidden border border-ink/10">
          <div id="upload-progress-bar" class="bg-rust h-1.5 rounded-full transition-all duration-200" style="width: 0%"></div>
        </div>
      </div>
    `;


    document.getElementById('upload-action-btn').addEventListener('click', () => this.executeUpload());
    document.getElementById('remove-file-btn').addEventListener('click', () => {
      this.selectedFile = null;
      this.previewContainer.innerHTML = '';
      this.fileInput.value = '';
    });
  },

  async executeUpload() {
    if (!this.selectedFile || !this.transactionId) return;

    const progressContainer = document.getElementById('upload-progress-container');
    const progressBar = document.getElementById('upload-progress-bar');
    const pctLabel = document.getElementById('upload-pct-label');
    const statusLabel = document.getElementById('upload-status-label');
    const uploadBtn = document.getElementById('upload-action-btn');

    if (uploadBtn) uploadBtn.disabled = true;
    if (progressContainer) progressContainer.classList.remove('hidden');

    try {
      const response = await API.uploadDocument(
        this.transactionId,
        this.selectedFile,
        this.documentTypeInput?.value || 'COMMERCIAL_INVOICE',
        (progress) => {
        if (progressBar) progressBar.style.width = `${progress}%`;
        if (pctLabel) pctLabel.textContent = `${progress}%`;
        }
      );
      this.lastUploadedDocument = response;

      if (progressBar) progressBar.style.width = '100%';
      if (pctLabel) pctLabel.textContent = '100%';
      if (statusLabel) {
        statusLabel.textContent = 'Evidence Stored · Apache Tika OCR Processing';
        statusLabel.classList.remove('text-rust');
        statusLabel.classList.add('text-olive-deep');
      }

      Toast.success('Evidence uploaded. OCR processing is starting.');
      await this.onUploadComplete?.(response);
    } catch (err) {
      Toast.error('Upload failed: ' + err.message);
      if (statusLabel) {
        statusLabel.textContent = 'Upload Failed. Click retry.';
        statusLabel.classList.add('text-rust');
      }
      if (uploadBtn) {
        uploadBtn.disabled = false;
        uploadBtn.innerHTML = '<i class="fa-solid fa-rotate-left w-3.5 h-3.5"></i> Retry Upload';
      }
    }
  }
};
