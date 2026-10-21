import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the dashboard calls /api on its own origin and Vite forwards it to the backend,
// so no CORS round-trips are needed locally. Override the target with VITE_API_TARGET.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
