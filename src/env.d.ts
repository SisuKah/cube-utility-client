/// <reference types="vite/client" />

declare global {
  /** The launcher version from package.json, injected by Vite. */
  const __APP_VERSION__: string
}

export {}
