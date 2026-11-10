import { Auth } from '../core/auth.js';

const user = Auth.getUser();
const primary = document.getElementById('route-primary');
const login = document.getElementById('route-login');
const identity = document.getElementById('route-identity');

if (user) {
  primary.href = Auth.homeForRole(user.role);
  primary.textContent = 'Return to your workspace';
  identity.textContent = `${user.name || user.email || 'Signed-in user'} · ${String(user.role || '').replaceAll('_', ' ')}`;
} else {
  primary.href = 'index.html';
  primary.textContent = 'Return home';
  login.classList.remove('hidden');
}

if (document.body.dataset.routeOutcome === 'error' && new URLSearchParams(location.search).get('type') === 'authentication') {
  document.getElementById('route-description').textContent = 'Aegis-X could not verify your session. Sign in and try again.';
  if (!user) primary.href = 'login.html';
}
