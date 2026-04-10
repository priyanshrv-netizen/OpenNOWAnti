/**
 * platform/api.ts
 *
 * Platform API bridge — the renderer calls functions from this file
 * instead of touching window.openNow (Electron) or Capacitor plugins directly.
 *
 * On Electron everything forwards to the existing window.openNow API.
 * On Android / Capacitor we call the GfnPlugin that lives in the Android project,
 * and use browser-native WebSocket signaling via BrowserSignalingClient.
 */

import { getPlatform } from "./detect";
import type { OpenNowApi, Settings } from "@shared/gfn";
import { BrowserSignalingClient } from "./browserSignaling";

// ---------------------------------------------------------------------------
// Capacitor bridge helpers
// ---------------------------------------------------------------------------

/** Call a Capacitor native plugin method directly via the low-level bridge. */
function callCapacitor(plugin: string, method: string, args: object = {}): Promise<any> {
  return new Promise((resolve, reject) => {
    const cap = (window as any).Capacitor;
    if (!cap) {
      reject(new Error("Capacitor bridge not available"));
      return;
    }
    // Capacitor 6+ exposes nativePromise directly
    if (cap.nativePromise) {
      cap.nativePromise(plugin, method, args).then(resolve).catch(reject);
      return;
    }
    // Fallback: Capacitor 4/5 style
    if (cap.Plugins?.[plugin]?.[method]) {
      cap.Plugins[plugin][method](args).then(resolve).catch(reject);
      return;
    }
    reject(new Error(`Plugin ${plugin}.${method} not found on bridge`));
  });
}

/** Wraps a plugin call with a timeout so a hung Kotlin coroutine can't freeze the UI. */
function withTimeout<T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((resolve) => setTimeout(() => resolve(fallback), ms)),
  ]);
}

// ---------------------------------------------------------------------------
// Electron path
// ---------------------------------------------------------------------------

function getElectronApi(): OpenNowApi {
  const api = (window as any).openNow as OpenNowApi | undefined;
  if (!api) {
    throw new Error("window.openNow is not available — are you running outside of Electron?");
  }
  return api;
}

// ---------------------------------------------------------------------------
// Capacitor / Android path
// ---------------------------------------------------------------------------

async function callNativePlugin<T>(method: string, args?: Record<string, unknown>): Promise<T> {
  return callCapacitor("GfnPlugin", method, args ?? {}) as Promise<T>;
}

const DEFAULT_SETTINGS: Settings = {
  resolution: "1920x1080",
  aspectRatio: "16:9",
  fps: 60,
  maxBitrateMbps: 75,
  codec: "H264",
  decoderPreference: "auto",
  encoderPreference: "auto",
  colorQuality: "10bit_420",
  region: "",
  clipboardPaste: false,
  mouseSensitivity: 1,
  mouseAcceleration: 0,
  shortcutToggleStats: "F3",
  shortcutTogglePointerLock: "F8",
  shortcutStopStream: "Ctrl+Shift+Q",
  shortcutToggleAntiAfk: "Ctrl+Shift+K",
  shortcutToggleMicrophone: "Ctrl+Shift+M",
  shortcutScreenshot: "F12",
  shortcutToggleRecording: "Ctrl+Shift+R",
  microphoneMode: "disabled",
  microphoneDeviceId: "",
  hideStreamButtons: false,
  controllerMode: false,
  controllerUiSounds: false,
  autoLoadControllerLibrary: false,
  controllerBackgroundAnimations: false,
  autoFullScreen: false,
  favoriteGameIds: [],
  sessionCounterEnabled: false,
  sessionClockShowEveryMinutes: 60,
  sessionClockShowDurationSeconds: 30,
  windowWidth: 1400,
  windowHeight: 900,
  keyboardLayout: "en-US",
  gameLanguage: "en_US",
  enableL4S: false,
} as any;

function buildCapacitorApi(): OpenNowApi {
  let browserSignaling: BrowserSignalingClient | null = null;
  const signalingListeners = new Set<Function>();
  const fullscreenListeners = new Set<Function>();

  const noop = () => Promise.resolve() as any;
  const noopUnsub = () => () => {};

  return {
    // ----- Auth -----
    getAuthSession: (input?) =>
      withTimeout(
        callNativePlugin("getAuthSession", input as any),
        8000,
        { session: null, refresh: { attempted: false, forced: false, outcome: "not_attempted", message: "Plugin timeout" } }
      ).catch(() => ({ session: null, refresh: { attempted: false, forced: false, outcome: "failed", message: "Native bridge error" } }) as any),
    getLoginProviders: () =>
      callNativePlugin<{ providers: any[] }>("getLoginProviders").then((r) => {
        const list = r?.providers ?? [];
        list.push({
          idpId: "debug", code: "DEBUG", displayName: `Debug (${list.length})`,
          streamingServiceUrl: "https://example.com", priority: 999
        });
        return list;
      }).catch((e) => [
        { idpId: "nvidia", code: "NVIDIA", displayName: `Err: ${String(e).slice(0, 15)}`, streamingServiceUrl: "https://prod.cloudmatchbeta.nvidiagrid.net/", priority: 0 }
      ]),
    getRegions: (input?) => {
      const token = (input as any)?.token;
      const baseUrl = (input as any)?.providerStreamingBaseUrl ?? (input as any)?.streamingBaseUrl ?? "";
      return callNativePlugin<{ regions: any[] }>("getRegions", { token, streamingBaseUrl: baseUrl })
        .then((r) => r?.regions ?? [])
        .catch(() => []);
    },
    login: (input) => callNativePlugin("login", input as any),
    logout: () => callNativePlugin("logout"),

    // ----- Games & Subscription -----
    fetchSubscription: () => Promise.resolve(null as any),
    fetchMainGames: (input) => callNativePlugin("fetchMainGames", input as any),
    fetchLibraryGames: (input) => callNativePlugin("fetchLibraryGames", input as any),
    fetchPublicGames: () => callNativePlugin("fetchPublicGames"),
    resolveLaunchAppId: (input) => callNativePlugin("resolveLaunchAppId", input as any),

    // ----- Session -----
    createSession: (input) => callNativePlugin("createSession", input as any),
    pollSession: (input) => callNativePlugin("pollSession", input as any),
    reportSessionAd: (input) => callNativePlugin("reportSessionAd", input as any),
    stopSession: (input) => callNativePlugin("stopSession", input as any),
    getActiveSessions: (token?, streamingBaseUrl?) =>
      callNativePlugin<any[]>("getActiveSessions", { token: token ?? "", streamingBaseUrl: streamingBaseUrl ?? "" }).catch(() => []),
    claimSession: (input) => callNativePlugin("claimSession", input as any),
    showSessionConflictDialog: () => callNativePlugin("showSessionConflictDialog"),

    // ----- Signaling (browser-native WebSocket) -----
    connectSignaling: async (input) => {
      browserSignaling?.disconnect();
      browserSignaling = new BrowserSignalingClient(
        (input as any).signalingServer,
        (input as any).sessionId,
        (input as any).signalingUrl,
      );
      browserSignaling.onEvent((event) => {
        for (const cb of signalingListeners) cb(event);
      });
      await browserSignaling.connect();
    },
    disconnectSignaling: async () => {
      browserSignaling?.disconnect();
      browserSignaling = null;
    },
    sendAnswer: async (input) => {
      browserSignaling?.sendAnswer(input as any);
    },
    sendIceCandidate: async (input) => {
      browserSignaling?.sendIceCandidate(input as any);
    },
    requestKeyframe: noop,
    onSignalingEvent: (listener) => {
      signalingListeners.add(listener);
      return () => signalingListeners.delete(listener);
    },

    // ----- Fullscreen / UI -----
    onToggleFullscreen: (listener) => {
      fullscreenListeners.add(listener);
      return () => fullscreenListeners.delete(listener);
    },
    quitApp: noop,
    setFullscreen: noop,
    toggleFullscreen: () => callNativePlugin("toggleFullscreen"),
    togglePointerLock: () => Promise.resolve(), // no pointer lock on touch screens
    setOrientation: (mode: string) => callNativePlugin("setOrientation", { mode }),

    // ----- Settings -----
    getSettings: () =>
      withTimeout(
        callNativePlugin<Settings>("getSettings"),
        8000,
        DEFAULT_SETTINGS,
      ).catch(() => DEFAULT_SETTINGS as Settings),
    setSetting: (key, value) => callNativePlugin("setSetting", { key, value: value as any }),
    resetSettings: () => callNativePlugin("resetSettings"),

    // ----- Misc -----
    exportLogs: () => Promise.resolve(""),
    pingRegions: (regions) =>
      callNativePlugin<any>("pingRegions", { urls: regions.map((r) => r.url) })
        .then((r: any) => {
          const results = r?.results ?? {};
          return regions.map((region) => ({
            url: region.url,
            pingMs: typeof results[region.url] === "number" ? results[region.url] : null,
          }));
        })
        .catch(() => regions.map((r) => ({ url: r.url, pingMs: null }))),
    deleteCache: noop,

    // ----- Screenshots / Recordings — no-op on Android -----
    saveScreenshot: noop,
    listScreenshots: () => Promise.resolve([]),
    deleteScreenshot: noop,
    saveScreenshotAs: () => Promise.resolve({ saved: false }),
    onTriggerScreenshot: noopUnsub as any,
    beginRecording: () => Promise.resolve({ recordingId: "" }),
    sendRecordingChunk: noop,
    finishRecording: noop,
    abortRecording: noop,
    listRecordings: () => Promise.resolve([]),
    deleteRecording: noop,
    showRecordingInFolder: noop,
    listMediaByGame: () => Promise.resolve({ screenshots: [], videos: [] }),
    getMediaThumbnail: () => Promise.resolve(null),
    showMediaInFolder: noop,
  } as any;
}

// ---------------------------------------------------------------------------
// Singleton export
// ---------------------------------------------------------------------------

let _api: OpenNowApi | null = null;

export function getPlatformApi(): OpenNowApi {
  if (_api) return _api;

  const platform = getPlatform();
  if (platform === "electron") {
    _api = getElectronApi();
  } else if (platform === "capacitor") {
    _api = buildCapacitorApi();
  } else {
    // Plain web / dev server
    try {
      _api = getElectronApi();
    } catch {
      throw new Error(
        "No platform API found. Run the app inside Electron or a Capacitor shell.",
      );
    }
  }

  return _api;
}
