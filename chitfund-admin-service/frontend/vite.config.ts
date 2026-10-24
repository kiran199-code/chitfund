import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the UI runs on :5173 and proxies API calls to the chitfund-admin-service on :8088.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8088' },
  },
  build: { outDir: 'dist', sourcemap: false },
});
