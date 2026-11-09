import { Auth } from '../core/auth.js';
import { Toast } from '../components/toast.js';

const params = new URLSearchParams(window.location.search);
if (params.get('registered') === 'true') Toast.success('Account created! Please sign in with your credentials.');
if (params.get('session') === 'expired' || sessionStorage.getItem('aegis_auth_expired') === 'true') {
  sessionStorage.removeItem('aegis_auth_expired');
  Toast.info('Your session ended. Please sign in again.');
}

document.getElementById('login-form')?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const email = document.getElementById('login-email').value.trim();
  const password = document.getElementById('login-password').value;
  const button = document.getElementById('login-submit-btn');
  button.disabled = true;
  const label = button.querySelector('span');
  if (label) label.textContent = 'Authenticating...';
  await Auth.login(email, password);
  button.disabled = false;
  if (label) label.textContent = 'Sign In to Console';
});
