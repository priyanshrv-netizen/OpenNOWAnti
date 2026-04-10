import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.zortos.opennow",
  appName: "OpenNOW",
  webDir: "dist",
  android: {
    // Allow loading the GFN streaming URLs (wss:// and TURN/STUN servers)
    allowMixedContent: true,
    // Required for proper WebRTC behaviour inside the WebView
    captureInput: true,
  },
  server: {
    // Use https scheme so WebRTC APIs are available (secure context requirement)
    androidScheme: "https",
    // Disable cleartext restriction warnings for WebRTC signalling
    cleartext: true,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 1000,
      backgroundColor: "#0f172a",
      androidSplashResourceName: "splash",
      androidScaleType: "CENTER_CROP",
      showSpinner: false,
    },
  },
};

export default config;
