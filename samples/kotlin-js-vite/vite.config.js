import { defineConfig } from 'vite'

export default defineConfig({
  // Serve from project root
  root: '.',

  // Dev server config
  server: {
    port: 3000,
    open: true,
    // Watch the build/js directory for Kotlin compilation output
    watch: {
      include: ['build/js/**']
    }
  },

  // Build config
  build: {
    outDir: 'build/dist',
    emptyOutDir: true
  },

  // Optimize deps - don't try to pre-bundle our Kotlin output
  optimizeDeps: {
    exclude: ['./build/js/app.mjs']
  }
})
