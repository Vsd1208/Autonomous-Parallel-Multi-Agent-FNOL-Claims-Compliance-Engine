import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The policyholder portal: a separate app with its own build and port. It shares only token-free modules with the
// adjuster workspace (photo angles, sample sets, photo resizing) and is built without VITE_ADJUSTER_TOKEN.
export default defineConfig({
  root: 'portal',
  plugins: [react()],
  publicDir: '../../demo-scenarios/sample-images',
  server: { port: 5174 },
  preview: { port: 5174 },
  build: { outDir: '../dist-portal', emptyOutDir: true },
});
