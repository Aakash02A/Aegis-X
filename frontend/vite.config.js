import { defineConfig } from 'vite';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = fileURLToPath(new URL('.', import.meta.url));
const apiBaseUrl = process.env.AEGIS_API_BASE_URL;

export default defineConfig({
  root: frontendRoot,
  plugins: apiBaseUrl
    ? [{
        name: 'aegis-api-base-url',
        transformIndexHtml() {
          return [{
            tag: 'script',
            children: `window.AEGIS_API_BASE_URL = ${JSON.stringify(apiBaseUrl)};`,
            injectTo: 'head-prepend'
          }];
        }
      }]
    : [],
  server: {
    host: '127.0.0.1',
    port: 3000,
    strictPort: true
  },
  build: {
    rollupOptions: {
      input: ['index', 'login', 'register', 'dashboard', 'transaction', 'compliance', 'settlement', 'operations', 'not-permitted', '404', 'error',
        'integrity', 'integrity-transactions', 'integrity-evidence', 'integrity-webhooks',
        'integrity-reconciliation',
        'integrity-exceptions', 'integrity-recovery', 'integrity-audit', 'integrity-settings']
        .map(page => resolve(frontendRoot, `${page}.html`))
    }
  }
});
