/**
 * Aegis-X Authentication Controller
 */
import { API } from './api.js';
import { Toast } from '../components/toast.js';
import { ROLE_HOME } from './constants.js';

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
      window.location.href = 'login.html';
      return false;
    }

    try {
      const user = await API.request('/auth/me');
      this.setUser({ id: user.id, email: user.email, name: user.fullName, role: user.role });
    } catch {
      API.setAuthToken(null);
      this.setUser(null);
      window.location.href = 'login.html';
      return false;
    }

    const user = this.getUser();
    if (allowedRoles.length && !allowedRoles.includes(user?.role)) {
      Toast.warning('Your account does not have access to this workspace.');
      window.location.href = this.homeForRole(user?.role);
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
          const role = result.role;
          if (role === 'COMPLIANCE_OFFICER') {
            window.location.href = 'compliance.html';
          } else if (role === 'SETTLEMENT_OPERATOR') {
            window.location.href = 'settlement.html';
          } else if (role === 'ADMIN') {
            window.location.href = 'operations.html';
          } else {
            window.location.href = 'dashboard.html';
          }
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
        window.location.href = 'login.html?registered=true';
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
      window.location.href = 'login.html';
      return false;
    }
    const user = this.getUser();
    if (allowedRoles.length > 0 && (!user || !allowedRoles.includes(user.role))) {
      Toast.warning('Unauthorized for this workspace. Redirecting...');
      setTimeout(() => {
        window.location.href = this.homeForRole(user?.role);
      }, 1000);
      return false;
    }
    return true;
  },

  homeForRole(role) {
    return ROLE_HOME[role] || ROLE_HOME.MERCHANT;
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

