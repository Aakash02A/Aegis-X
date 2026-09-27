/**
 * Aegis-X Toast Notification Controller
 */

export class ToastManager {
  constructor() {
    this.container = document.getElementById('toast-container');
    if (!this.container) {
      this.container = document.createElement('div');
      this.container.id = 'toast-container';
      document.body.appendChild(this.container);
    }
  }

  show(message, type = 'info', duration = 4000) {
    const toast = document.createElement('div');
    toast.className = 'aegis-toast';

    const borders = {
      success: 'border-emerald-500/30',
      error: 'border-rose-500/30',
      warning: 'border-amber-500/30',
      info: 'border-cyan-500/30'
    };

    toast.classList.add(borders[type] || 'border-cyan-500/30');

    const iconNames = { success: 'check', error: 'alert-circle', warning: 'alert-triangle', info: 'info' };
    const icon = document.createElement('i');
    icon.dataset.lucide = iconNames[type] || iconNames.info;
    icon.className = 'w-4 h-4';
    const iconWrap = document.createElement('div');
    iconWrap.className = 'p-1 rounded-full';
    iconWrap.append(icon);
    const text = document.createElement('div');
    text.className = 'flex-1 font-medium text-sm leading-snug';
    text.textContent = message;
    const close = document.createElement('button');
    close.className = 'text-slate-400 hover:text-white transition-colors text-xs p-1';
    close.type = 'button';
    close.setAttribute('aria-label', 'Dismiss notification');
    const closeIcon = document.createElement('i');
    closeIcon.dataset.lucide = 'x';
    closeIcon.className = 'w-3.5 h-3.5';
    close.append(closeIcon);
    close.addEventListener('click', () => toast.remove());
    toast.append(iconWrap, text, close);

    this.container.appendChild(toast);

    if (window.lucide && typeof window.lucide.createIcons === 'function') {
      window.lucide.createIcons();
    }

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(10px)';
      setTimeout(() => toast.remove(), 250);
    }, duration);
  }

  success(msg, duration) { this.show(msg, 'success', duration); }
  error(msg, duration) { this.show(msg, 'error', duration); }
  warning(msg, duration) { this.show(msg, 'warning', duration); }
  info(msg, duration) { this.show(msg, 'info', duration); }
}

export const Toast = new ToastManager();
window.Toast = Toast;
