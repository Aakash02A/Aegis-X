import { Auth } from '../core/auth.js';
import { Toast } from '../components/toast.js';

document.getElementById('register-form')?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const fullName = document.getElementById('reg-company').value.trim();
  const email = document.getElementById('reg-email').value.trim();
  const phone = document.getElementById('reg-phone').value.trim();
  const password = document.getElementById('reg-password').value;
  const confirm = document.getElementById('reg-confirm').value;
  if (password !== confirm) {
    Toast.error('Passwords do not match.');
    return;
  }

  const button = document.getElementById('register-submit-btn');
  button.disabled = true;
  const label = button.querySelector('span');
  if (label) label.textContent = 'Creating Account...';
  await Auth.register({ fullName, email, password, ...(phone ? { phone } : {}) });
  button.disabled = false;
  if (label) label.textContent = 'Register & Proceed to Login';
});
