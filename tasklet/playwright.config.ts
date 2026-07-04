import { defineConfig } from '@playwright/test'

// Runs against the compose stack (`make up && make seed`, then `make e2e`).
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5174',
  },
})
