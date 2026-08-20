import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';
import { build } from 'vite';
import { ENTRIES, BUNDLE_DIR } from './vite.config.mjs';

const args = process.argv.slice(2);
const only = args.filter((a) => !a.startsWith('-'));
const entries = only.length ? only : ENTRIES;
const shouldClean = only.length === 0 && !args.includes('--no-clean');

if (shouldClean) {
  fs.rmSync(BUNDLE_DIR, { recursive: true, force: true });
}
fs.mkdirSync(BUNDLE_DIR, { recursive: true });

for (const entry of entries) {
  if (!ENTRIES.includes(entry)) throw new Error(`unknown entrypoint: ${entry}`);
  process.env.OTOROSHI_ENTRY = entry;
  await build({ configFile: path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'vite.config.mjs'), mode: 'production' });
}

// same as webpack's CompressionPlugin: play's Assets controller serves the .gz sibling
// when the client accepts gzip
let compressed = 0;
for (const file of fs.readdirSync(BUNDLE_DIR)) {
  if (file.endsWith('.gz')) continue;
  const full = path.join(BUNDLE_DIR, file);
  if (!fs.statSync(full).isFile()) continue;
  const source = fs.readFileSync(full);
  const gzipped = zlib.gzipSync(source, { level: 9 });
  if (gzipped.length / source.length < 0.8) {
    fs.writeFileSync(`${full}.gz`, gzipped);
    compressed += 1;
  }
}
console.log(`\ngzipped ${compressed} file(s) in ${path.relative(process.cwd(), BUNDLE_DIR)}`);
