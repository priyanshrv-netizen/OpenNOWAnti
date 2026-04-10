import React from "react";
import ReactDOM from "react-dom/client";

// Install the platform API into window.openNow so that all existing
// window.openNow.xxx calls work on both Electron and Android/Capacitor.
import { getPlatformApi, isAndroid } from "./platform/index";

const installPlatformApi = () => {
  // If window.openNow is undefined, we are not in Electron (which mounts it via preload).
  // We unconditionally inject the Capacitor-backed API here. Even if window.Capacitor
  // hasn't fully attached yet, the bridge methods will work when called later during React mount.
  if (!(window as any).openNow) {
    (window as any).openNow = getPlatformApi();
  }
};
installPlatformApi();

import { App } from "./App";
import "./styles.css";

// react-scan and shared logger are Electron / Node-side features.
// Guard them so the Vite web build (capacitor) doesn't fail.
if (typeof (window as any).openNow?.exportLogs === "function" && import.meta.env.DEV) {
  // Dynamically import only if available (Electron path)
  import("@shared/logger").then(({ initLogCapture }) => initLogCapture("renderer")).catch(() => {});
}


ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
