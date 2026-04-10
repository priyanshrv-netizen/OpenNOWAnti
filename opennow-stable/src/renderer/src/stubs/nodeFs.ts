// Stub: node:fs and node:fs/promises — not used in browser build
export const readFile = async (_path: string): Promise<Buffer> => { throw new Error("fs.readFile not available in browser"); };
export const writeFile = async (_path: string, _data: unknown): Promise<void> => { throw new Error("fs.writeFile not available in browser"); };
export const mkdir = async (_path: string): Promise<void> => {};
export const readdir = async (_path: string): Promise<string[]> => [];
export const stat = async (_path: string): Promise<unknown> => { throw new Error("fs.stat not available in browser"); };
export const unlink = async (_path: string): Promise<void> => {};
export const copyFile = async (_src: string, _dest: string): Promise<void> => {};
export const rename = async (_src: string, _dest: string): Promise<void> => {};
export const realpath = async (p: string): Promise<string> => p;
export const existsSync = (_path: string): boolean => false;
export const createWriteStream = (_path: string): unknown => null;
export default {};
