// Stub: node:path — not used in browser build
export const join = (...parts: string[]) => parts.join("/");
export const resolve = (...parts: string[]) => parts.join("/");
export const dirname = (p: string) => p.split("/").slice(0, -1).join("/");
export const basename = (p: string) => p.split("/").pop() ?? "";
export const relative = (_from: string, to: string) => to;
export default { join, resolve, dirname, basename, relative };
