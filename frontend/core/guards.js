import { Auth } from './auth.js';

export function requireUser(allowedRoles = []) {
  return Auth.requireUser(allowedRoles);
}
