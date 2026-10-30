/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ADJUSTER_TOKEN?: string;
  /** The policyholder portal (separate app); old upload links on the workspace are sent there. */
  readonly VITE_PORTAL_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
