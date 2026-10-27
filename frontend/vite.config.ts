import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The sample photos (demo-scenarios/sample-images) are served as static files, so the claim form can
// load a real photo set in one click without keeping a second copy of the images.
export default defineConfig({
  plugins: [react()],
  publicDir: '../demo-scenarios/sample-images',
  server: { port: 5173 },
});
