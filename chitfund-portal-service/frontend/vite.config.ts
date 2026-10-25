import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the UI runs on :5173 and proxies API calls to chitfund-portal-service on :8087.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8087' },
  },
  build: { outDir: 'dist', sourcemap: false },
});
