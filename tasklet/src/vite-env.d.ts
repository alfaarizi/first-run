/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_FIRSTRUN_KEY: string
  readonly VITE_FIRSTRUN_HMAC_KEY: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
