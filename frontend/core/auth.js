/**
 * Aegis-X Authentication Controller
 */
import { API } from './api.js';
import { Toast } from '../components/toast.js';
import { ROLE_HOME, currentProtectedPagePath, safeReturnPath } from './constants.js';

function loginUrl(returnPath = currentProtectedPagePath()) {
  const params = new URLSearchParams();
  if (returnPath) params.set('return', returnPath);
  return `login.html${params.size ? `?${params}` : ''}`;
}

export const Auth = {
  getUser() {
    try {
      const data = localStorage.getItem('aegis_user');
      return data ? JSON.parse(data) : null;
    } catch {
      return null;
    }
  },

  setUser(user) {
    if (user) {
      localStorage.setItem('aegis_user', JSON.stringify(user));
    } else {
      localStorage.removeItem('aegis_user');
    }
  },

  isAuthenticated() {
    return Boolean(API.getAuthToken());
  },

  async requireUser(allowedRoles = []) {
    if (!this.isAuthenticated()) {
      window.location.assign(loginUrl());
      return false;
    }

    try {
      const user = await API.request('/auth/me');
      this.setUser({ id: user.id, email: user.email, name: user.fullName, role: user.role });
    } catch (error) {
      API.setAuthToken(null);
      this.setUser(null);
      if (error?.status !== 401) window.location.assign('error.html?type=authentication');
      return false;
    }

    const user = this.getUser();
    if (allowedRoles.length && !allowedRoles.includes(user?.role)) {
      Toast.warning('Your account does not have access to this workspace.');
      window.location.assign('not-permitted.html');
      return false;
    }
    document.documentElement.classList.remove('auth-checking');
    return true;
  },

  async login(email, password) {
    try {
      const result = await API.request('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password })
      });

      if (result.token) {
        API.setAuthToken(result.token);
        this.setUser(result.user || {
          id: result.userId,
          email: result.email || email,
          role: result.role,
          name: result.fullName || result.email || email
        });

        Toast.success('Welcome back.');

        setTimeout(() => {
          const requested = new URLSearchParams(window.location.search).get('return');
          window.location.assign(safeReturnPath(requested, result.role) || this.homeForRole(result.role));
        }, 600);
        return true;
      } else {
        throw new Error('No authentication token received');
      }
    } catch (err) {
      Toast.error(err.message || 'Login failed. Please check your credentials.');
      return false;
    }
  },

  async register(userData) {
    try {
      await API.request('/auth/register', {
        method: 'POST',
        body: JSON.stringify(userData)
      });

      Toast.success('Account created successfully! Redirecting to login...');
      
      // Mandatory Stage 5 rule: Redirect to Login upon registration (do not auto-login)
      setTimeout(() => {
        const returnPath = new URLSearchParams(window.location.search).get('return');
        const destination = returnPath
          ? `login.html?registered=true&return=${encodeURIComponent(returnPath)}`
          : 'login.html?registered=true';
        window.location.assign(destination);
      }, 1200);
      return true;
    } catch (err) {
      Toast.error(err.message || 'Registration failed. Please try again.');
      return false;
    }
  },

  logout() {
    API.setAuthToken(null);
    this.setUser(null);
    Toast.info('Logged out successfully.');
    setTimeout(() => {
      window.location.href = 'login.html';
    }, 400);
  },

  guard(allowedRoles = []) {
    if (!this.isAuthenticated()) {
      window.location.assign(loginUrl());
      return false;
    }
    const user = this.getUser();
    if (allowedRoles.length > 0 && (!user || !allowedRoles.includes(user.role))) {
      Toast.warning('Unauthorized for this workspace. Redirecting...');
      window.location.assign('not-permitted.html');
      return false;
    }
    return true;
  },

  homeForRole(role) {
    return ROLE_HOME[role] || 'index.html';
  },

  applyRoleNavigation() {
    const role = this.getUser()?.role;
    document.querySelectorAll('[data-roles]').forEach(element => {
      const allowed = element.dataset.roles.split(',').map(value => value.trim());
      const hidden = !role || !allowed.includes(role);
      element.hidden = hidden;
      element.classList.toggle('hidden', hidden);
    });
  }
};

