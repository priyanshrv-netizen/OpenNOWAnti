/**
 * vite.web.config.ts
 *
 * Standalone Vite config for the Capacitor/Android web bundle.
 * This does NOT use electron-vite -- it builds only the renderer
 * portion into dist/ which Capacitor then copies into the Android project.
 *
 * The Electron desktop build is unaffected (still uses electron.vite.config.ts).
 */
import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  root: resolve("src/renderer"),
  build: {
    outDir: resolve("dist"),
    emptyOutDir: true,
    rollupOptions: {
      // Entry point is the renderer's index.html
      input: resolve("src/renderer/index.html"),
    },
  },
  plugins: [react()],
  resolve: {
    alias: {
      // @shared resolves to src/shared (types used in both main and renderer)
      "@shared": resolve("src/shared"),
      // @main resolves to src/main so the Capacitor api.ts can import
      // game/session fetch helpers (they use browser-native fetch, no Node APIs)
      "@main": resolve("src/main"),
      // Stub Node built-ins that may be imported transitively but aren't
      // needed in browser context
      "node:crypto": resolve("src/renderer/src/platform/cryptoShim.ts"),
      "node:path": resolve("src/renderer/src/stubs/nodePath.ts"),
      "node:fs": resolve("src/renderer/src/stubs/nodeFs.ts"),
      "node:fs/promises": resolve("src/renderer/src/stubs/nodeFs.ts"),
      "node:net": resolve("src/renderer/src/stubs/nodeNet.ts"),
      "node:child_process": resolve("src/renderer/src/stubs/nodeChildProcess.ts"),
      // The ws package is used by the Electron main process signaling client.
      // On Android we use BrowserSignalingClient instead, so we stub ws.
      "ws": resolve("src/renderer/src/stubs/ws.ts"),
      // react-scan is Electron dev-mode only
      "react-scan": resolve("src/renderer/src/stubs/reactScan.ts"),
    },
  },
  define: {
    // Prevents "process is not defined" errors in any deps that check it
    "process.env.NODE_ENV": JSON.stringify("production"),
  },
});
