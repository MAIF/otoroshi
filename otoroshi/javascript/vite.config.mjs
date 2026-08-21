import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { visualizer } from 'rollup-plugin-visualizer';

const ENTRIES = ['backoffice', 'genericlogin', 'multilogin', 'simplelogin', 'faker', 'elk'];
const entry = process.env.OTOROSHI_ENTRY || 'backoffice';
const DEV_PORT = Number(process.env.DEV_SERVER_PORT || 3040);

const GLOBALS = { faker: 'OtoroshiFaker', elk: 'OtoroshiElk' };
const globalName = (name) => GLOBALS[name] || 'Otoroshi';

function gzipSiblings() {
  return {
    name: 'otoroshi:gzip-siblings',
    apply: 'build',
    enforce: 'post',
    generateBundle(_options, bundle) {
      for (const [fileName, output] of Object.entries(bundle)) {
        if (!/\.(js|css)$/.test(fileName)) continue;
        const source = output.type === 'chunk' ? output.code : output.source;
        this.emitFile({
          type: 'asset',
          fileName: `${fileName}.gz`,
          source: gzipSync(source, { level: 9 }),
        });
      }
    },
  };
}

function devBundleUrls() {
  return {
    name: 'otoroshi:dev-bundle-urls',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const match = /^\/assets\/javascripts\/bundle\/([a-z]+)\.(js|css)(\?|$)/.exec(req.url || '');
        if (!match || !ENTRIES.includes(match[1])) return next();
        if (match[2] === 'css') {
          res.setHeader('Content-Type', 'text/css');
          return res.end('');
        }
        const origin = `http://${req.headers.host}`;
        res.setHeader('Content-Type', 'text/javascript');
        res.end(
          [
            '(function () {',
            '  var queued = [];',
            `  window.${globalName(match[1])} = new Proxy({}, {`,
            '    get: function (_, name) {',
            '      return function () { queued.push([name, arguments]); };',
            '    },',
            '  });',
            `  import('${origin}/@vite/client');`,
            `  import('${origin}/@react-refresh')`,
            '    .then(function (refresh) {',
            '      refresh.injectIntoGlobalHook(window);',
            '      window.$RefreshReg$ = function () {};',
            '      window.$RefreshSig$ = function () { return function (type) { return type; }; };',
            '    })',
            `    .then(function () { return import('${origin}/src/${match[1]}.js'); })`,
            '    .then(function (mod) {',
            '      var api = Object.assign({}, mod);',
            `      window.${globalName(match[1])} = api;`,
            '      queued.forEach(function (c) { api[c[0]].apply(api, c[1]); });',
            '    });',
            '})();',
          ].join('\n')
        );
      });
    },
  };
}

export default defineConfig(({ command }) => ({
  plugins: [
    react(),
    devBundleUrls(),
    gzipSiblings(),
    process.env.ANALYZE &&
      visualizer({
        filename: `bundle-stats-${entry}.html`,
        gzipSize: true,
        open: true,
      }),
  ].filter(Boolean),
  esbuild: {
    include: /\.js$/,
    exclude: /node_modules/,
    loader: 'jsx',
  },
  optimizeDeps: {
    esbuildOptions: {
      loader: { '.js': 'jsx' },
    },
  },
  base: command === 'build' ? '/assets/javascripts/bundle/' : '/',
  server: {
    host: '0.0.0.0',
    port: DEV_PORT,
    origin: `http://localhost:${DEV_PORT}`,
    cors: { origin: true, credentials: true },
  },
  build: {
    outDir: resolve(import.meta.dirname, '../public/javascripts/bundle'),
    emptyOutDir: false,
    assetsInlineLimit: 0,
    cssCodeSplit: false,
    modulePreload: false,
    target: 'es2020',
    chunkSizeWarningLimit: 10_000,
    rollupOptions: {
      input: resolve(import.meta.dirname, `src/${entry}.js`),
      preserveEntrySignatures: 'strict',
      output: {
        format: 'umd',
        name: globalName(entry),
        entryFileNames: `${entry}.js`,
        assetFileNames: (info) => {
          const name = info.names?.[0] ?? info.name ?? '';
          return name.endsWith('.css') ? `${entry}.css` : '[name]-[hash][extname]';
        },
      },
    },
  },
}));
