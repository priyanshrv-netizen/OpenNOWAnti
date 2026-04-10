/**
 * cryptoShim.ts
 *
 * Shim for node:crypto — re-exports the Web Crypto API so that
 * any code importing from "node:crypto" still works in a browser context.
 * Only the randomUUID function is actually needed by the renderer.
 */
export function randomUUID(): string {
  return crypto.randomUUID();
}

export function createHash(_algorithm: string) {
  // Stub — not used in browser path
  return {
    update: (_data: string) => ({ digest: (_enc: string) => "" }),
  };
}

export default { randomUUID, createHash };
