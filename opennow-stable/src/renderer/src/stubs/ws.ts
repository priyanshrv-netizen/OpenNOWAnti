/**
 * ws.ts — stub for the "ws" npm package in browser context.
 *
 * The Electron main process uses "ws" for signaling WebSockets.
 * On Android/Capacitor, we use BrowserSignalingClient (native browser WebSocket).
 * This stub prevents Vite from failing when "ws" is imported in main-process
 * code that gets transitively included in the renderer bundle.
 */

class WsStub {
  constructor(_url: string, _protocols?: string | string[]) {}
  on(_event: string, _listener: unknown): this { return this; }
  send(_data: unknown): void {}
  close(): void {}
  terminate(): void {}
}

export default WsStub;
export { WsStub as WebSocket };
