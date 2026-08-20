import fs from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, transformWithEsbuild } from 'vite';
import react from '@vitejs/plugin-react';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// same entrypoints as the previous webpack build
export const ENTRIES = ['backoffice', 'genericlogin', 'multilogin', 'simplelogin'];

const SRC_DIR = path.resolve(__dirname, 'src') + path.sep;

// byte-converter@0.2.0 uses `interface` as a variable name, which webpack tolerated (sloppy
// mode cjs) but both rollup and esbuild reject once the module is wrapped as esm
const BYTE_CONVERTER_RE = /byte-converter[\\/]lib[\\/]byte-converter\.js$/;
const fixByteConverter = (code) => code.replace(/\binterface\b/g, '_interface');

// play serves ../public as /assets and /__otoroshi_assets
export const BUNDLE_DIR = path.resolve(__dirname, '../public/javascripts/bundle');
export const PUBLIC_PATH = '/assets/javascripts/bundle/';

export const DEV_PORT = Number(process.env.DEV_SERVER_PORT || 3040);
const DEV_HOST = process.env.DEV_SERVER_HOSTNAME || 'localhost';
// vite does not self sign like webpack-dev-server did, so serving the dev server over https
// means pointing DEV_SERVER_KEY/DEV_SERVER_CERT at a key pair
const DEV_HTTPS =
  !!process.env.DEV_SERVER_HTTPS && !!process.env.DEV_SERVER_KEY && !!process.env.DEV_SERVER_CERT;

// the page is served by otoroshi (http://otoroshi.oto.tools:9999) and only pulls the
// modules from here, so the dev server has to accept that origin explicitly
const ALLOWED_ORIGINS = process.env.DEV_SERVER_ALLOWED_ORIGINS
  ? process.env.DEV_SERVER_ALLOWED_ORIGINS.split(',').map((v) => v.trim())
  : [/^https?:\/\/(?:[a-z0-9-]+\.)*(?:oto\.tools|localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/i];

export default defineConfig(({ command }) => {
  // umd output cannot be code split, so prod does one build per entrypoint (see build.mjs)
  const entry = process.env.OTOROSHI_ENTRY;

  return {
    root: __dirname,
    base: command === 'build' ? PUBLIC_PATH : '/',
    clearScreen: false,
    plugins: [
      // jsx lives in .js files here, not in .jsx, and neither esbuild nor
      // @vitejs/plugin-react compiles jsx out of a .js file on its own. this runs before
      // the react plugin so that the react-refresh transform still sees the result and
      // wires up hot reload
      {
        name: 'otoroshi:jsx-in-js',
        enforce: 'pre',
        async transform(code, id) {
          const [filepath] = id.split('?');
          if (!filepath.startsWith(SRC_DIR) || !filepath.endsWith('.js')) return null;
          return transformWithEsbuild(code, id, { loader: 'jsx', jsx: 'automatic' });
        },
      },
      react({ include: '**/*.{js,jsx}' }),
      {
        name: 'otoroshi:byte-converter-strict-mode',
        enforce: 'pre',
        transform(code, id) {
          if (!BYTE_CONVERTER_RE.test(id.split('?')[0])) return null;
          return { code: fixByteConverter(code), map: null };
        },
      },
    ],
    resolve: {
      alias: {
        // same shims as webpack's resolve.fallback
        crypto: 'crypto-browserify',
        stream: 'stream-browserify',
      },
    },
    css: {
      preprocessorOptions: {
        scss: {
          silenceDeprecations: [
            'import',
            'legacy-js-api',
            'global-builtin',
            'color-functions',
            'mixed-decls',
            'slash-div',
          ],
        },
      },
    },
    optimizeDeps: {
      entries: ENTRIES.map((e) => `src/${e}.js`),
      // the dependency scanner has to parse jsx in .js files too
      esbuildOptions: {
        loader: { '.js': 'jsx' },
        plugins: [
          {
            name: 'otoroshi:byte-converter-strict-mode',
            setup(build) {
              build.onLoad({ filter: BYTE_CONVERTER_RE }, async (args) => ({
                contents: fixByteConverter(await fs.readFile(args.path, 'utf8')),
                loader: 'js',
              }));
            },
          },
        ],
      },
    },
    server: {
      host: '0.0.0.0',
      port: DEV_PORT,
      strictPort: true,
      https: DEV_HTTPS
        ? {
            key: readFileSync(process.env.DEV_SERVER_KEY),
            cert: readFileSync(process.env.DEV_SERVER_CERT),
          }
        : undefined,
      // absolute urls for everything the dev server emits (assets, hmr, chunks),
      // since the html comes from otoroshi and not from vite
      origin: `${DEV_HTTPS ? 'https' : 'http'}://${DEV_HOST}:${DEV_PORT}`,
      cors: { origin: ALLOWED_ORIGINS, credentials: true },
      allowedHosts: true,
      hmr: { host: DEV_HOST, protocol: DEV_HTTPS ? 'wss' : 'ws', clientPort: DEV_PORT },
      warmup: { clientFiles: ENTRIES.map((e) => `./src/${e}.js`) },
    },
    build: {
      outDir: BUNDLE_DIR,
      emptyOutDir: false,
      target: 'es2018',
      sourcemap: false,
      cssCodeSplit: false,
      modulePreload: false,
      assetsInlineLimit: 0,
      reportCompressedSize: false,
      chunkSizeWarningLimit: 8000,
      rollupOptions: !entry
        ? {}
        : {
            input: path.resolve(__dirname, `src/${entry}.js`),
            preserveEntrySignatures: 'exports-only',
            output: {
              format: 'umd',
              name: 'Otoroshi',
              entryFileNames: `${entry}.js`,
              // no chunks: keep one self contained file per entrypoint, like webpack did
              inlineDynamicImports: true,
              assetFileNames: (info) => {
                const name = info.names?.[0] || info.name || '';
                return name.endsWith('.css') ? `${entry}.css` : '[name]-[hash][extname]';
              },
            },
          },
    },
  };
});
