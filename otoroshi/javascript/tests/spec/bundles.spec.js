import { test, expect } from '@playwright/test';

// Contract of the frontend build artifacts, as consumed by the Twirl templates
// and the admin-extension mechanism. Guards the webpack -> vite migration:
//   - each entry produces <entry>.js and <entry>.css under the fixed,
//     non-hashed names the templates reference (cache busting is server-side
//     via ?v=<boot timestamp>),
//   - a .gz sibling exists for each (Play's Assets controller serves it when
//     the client accepts gzip),
//   - each bundle publishes the same functions on the window.Otoroshi global
//     that templates and extensions call inline.
//
// Only meaningful against prod-built assets: when the server runs with
// liveJs (dev mode), bundles come from the frontend dev server and there is
// no static artifact to check, so the whole suite self-skips.

const BUNDLE_API = {
    backoffice: [
        'extensions',
        'genericLogin',
        'getExtension',
        'getExtensions',
        'init',
        'login',
        'registerExtension',
        'registerPlugins',
        'selfUpdate',
    ],
    genericlogin: ['genericLogin', 'selfUpdate'],
    multilogin: ['multiLogin'],
    simplelogin: ['auth0PasswordlessLogin', 'simpleLogin'],
};

// Module-interop markers the bundler may add to the exports object; ignored
// when comparing the API surface.
const INTEROP_KEYS = ['__esModule', 'default'];

let liveJs = false;

test.beforeAll(async ({ request }) => {
    // /bo/simple/login is reachable unauthenticated and embeds the bundle
    // script tags: a dev-server URL in the markup means liveJs mode.
    const res = await request.get('/bo/simple/login');
    const html = await res.text();
    liveJs = html.includes('localhost:3040') || html.includes('@vite/client');
});

for (const [entry, expectedApi] of Object.entries(BUNDLE_API)) {
    test.describe(`${entry} bundle`, () => {
        test(`serves ${entry}.js and ${entry}.css with a gzip variant`, async ({ request }) => {
            test.skip(liveJs, 'bundle artifact contract only applies to prod-built assets');

            for (const ext of ['js', 'css']) {
                const url = `/assets/javascripts/bundle/${entry}.${ext}`;

                const plain = await request.get(url, {
                    headers: { 'accept-encoding': 'identity' },
                });
                expect(plain.status(), `${url} should be served`).toBe(200);

                const gzipped = await request.get(url, {
                    headers: { 'accept-encoding': 'gzip' },
                });
                expect(gzipped.status(), `${url} (gzip) should be served`).toBe(200);
                expect(
                    gzipped.headers()['content-encoding'],
                    `${url} should have a precompressed .gz sibling served by Play`
                ).toBe('gzip');
            }
        });

        test(`exposes the expected window.Otoroshi API`, async ({ page, baseURL }) => {
            test.skip(liveJs, 'bundle artifact contract only applies to prod-built assets');

            const errors = [];
            page.on('pageerror', (err) => errors.push(err.message));

            // The bundle is exercised in isolation on an empty page, exactly
            // like a template <script> tag would. The page must live on the
            // real server origin (not about:blank, whose opaque origin makes
            // localStorage &co throw and aborts the UMD factory mid-eval).
            const blankUrl = `${baseURL}/__pw_blank_page`;
            await page.route('**/__pw_blank_page', (route) =>
                route.fulfill({
                    contentType: 'text/html',
                    body: '<!doctype html><html><head></head><body></body></html>',
                })
            );
            await page.goto(blankUrl);
            await page.addScriptTag({ url: `${baseURL}/assets/javascripts/bundle/${entry}.js` });

            expect(errors, `loading ${entry}.js should not throw`).toEqual([]);

            const api = await page.evaluate(
                (interop) =>
                    Object.keys(window.Otoroshi || {})
                        .filter((k) => !interop.includes(k))
                        .sort(),
                INTEROP_KEYS
            );
            expect(api, `window.Otoroshi surface of ${entry}.js`).toEqual(expectedApi);

            const nonFunctions = await page.evaluate(
                (keys) => keys.filter((k) => typeof window.Otoroshi[k] !== 'function'),
                expectedApi
            );
            expect(nonFunctions, 'every exposed API entry should be a function').toEqual([]);
        });
    });
}
