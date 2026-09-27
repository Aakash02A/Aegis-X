import { defineConfig } from 'vite';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
  root: frontendRoot,
  build: {
    rollupOptions: {
      input: ['index', 'login', 'register', 'dashboard', 'transaction', 'compliance', 'settlement', 'operations']
        .map(page => resolve(frontendRoot, `${page}.html`))
    }
  }
});