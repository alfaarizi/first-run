/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_FIRSTRUN_KEY: string
  readonly VITE_FIRSTRUN_HMAC_KEY: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// The widget snippet in index.html defines this global (widget/src/index.ts).
interface Window {
  fr?: {
    identify(endUserHash: string): void
    track(event: string, properties?: Record<string, string | number | boolean>): void
  }
}
