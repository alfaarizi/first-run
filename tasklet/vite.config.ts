import { defineConfig } from 'vite'

// The proxy keeps the demo same-origin. compose.yaml points FIRSTRUN_SERVER_URL
// at the server container, and outside compose the gateway is localhost:8080.
export default defineConfig({
  // The repo root .env is the single env source, shared with compose.
  envDir: '../',
  server: {
    port: 5174,
    proxy: {
      '/v1/e': {
        target: process.env.FIRSTRUN_SERVER_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
