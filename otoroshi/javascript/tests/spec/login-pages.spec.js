import { test, expect } from '@playwright/test';

// Smoke tests for the login pages backed by the non-backoffice bundles.
// The backoffice bundle is exercised by the whole admin suite, but
// simplelogin / multilogin (and genericlogin, see below) had no coverage:
// these specs guarantee each page renders through its bundle after the
// webpack -> vite migration.
//
//   - /bo/simple/login               -> backoffice.js  (Otoroshi.login)
//   - /privateapps/generic/simple-login?route=<id>   -> simplelogin.js (Otoroshi.simpleLogin)
//   - /privateapps/generic/choose-provider?route=<id> -> multilogin.js (Otoroshi.multiLogin)
//
// genericlogin.js (oto/login + selfUpdate templates) is only rendered by the
// LDAP backoffice flow and the private-apps profile page, both needing infra
// we do not want in a smoke test; its JS contract is covered by
// bundles.spec.js instead.
//
// Both private-apps pages require a route carrying the MultiAuthModule
// plugin (see Auth0Controller.multiLoginPage), so the setup seeds an
// in-memory basic auth module and a dedicated route via the admin API,
// mirroring the approach of tests/setup/auth.setup.js.

const ADMIN_API = 'http://otoroshi-api.oto.tools:9999';
const ADMIN_API_HEADERS = {
    'Content-Type': 'application/json',
    'Otoroshi-Client-Id': 'admin-api-apikey-id',
    'Otoroshi-Client-Secret': 'admin-api-apikey-secret',
};

const PRIVATE_APPS = 'http://privateapps.oto.tools:9999';

const AUTH_MODULE_ID = 'pw-login-smoke-auth';
const AUTH_MODULE_NAME = 'PW Login Smoke Auth';
const ROUTE_ID = 'pw-login-smoke';

// Idempotent upsert (same contract as auth.setup.js: PUT requires existence).
async function ensureEntity(collectionUrl, body) {
    const itemUrl = `${collectionUrl}/${encodeURIComponent(body.id)}`;
    const get = await fetch(itemUrl, { headers: ADMIN_API_HEADERS });
    if (get.status === 404) {
        const create = await fetch(collectionUrl, {
            method: 'POST',
            headers: ADMIN_API_HEADERS,
            body: JSON.stringify(body),
        });
        if (create.status >= 400) {
            const txt = await create.text().catch(() => '');
            if (!(create.status === 400 && txt.includes('already exists'))) {
                throw new Error(`setup POST ${collectionUrl} failed: ${create.status} ${txt}`);
            }
        }
        return;
    }
    if (get.status >= 400) {
        throw new Error(`setup GET ${itemUrl} failed: ${get.status} ${await get.text().catch(() => '')}`);
    }
    const update = await fetch(itemUrl, {
        method: 'PUT',
        headers: ADMIN_API_HEADERS,
        body: JSON.stringify(body),
    });
    if (update.status >= 400) {
        throw new Error(`setup PUT ${itemUrl} failed: ${update.status} ${await update.text().catch(() => '')}`);
    }
}

test.beforeAll(async () => {
    // Basic auth module built from the server-side template so the payload
    // always matches the current BasicAuthModuleConfig shape.
    const tpl = await fetch(`${ADMIN_API}/api/auths/_template?mod=basic`, {
        headers: ADMIN_API_HEADERS,
    });
    if (tpl.status >= 400) {
        throw new Error(`setup: cannot fetch basic auth template: ${tpl.status} ${await tpl.text()}`);
    }
    const authModule = {
        ...(await tpl.json()),
        id: AUTH_MODULE_ID,
        name: AUTH_MODULE_NAME,
        desc: 'Auto-seeded by Playwright login-pages.spec.js',
    };
    await ensureEntity(`${ADMIN_API}/api/auths`, authModule);

    await ensureEntity(`${ADMIN_API}/api/routes`, {
        id: ROUTE_ID,
        name: ROUTE_ID,
        description: 'Auto-seeded by Playwright login-pages.spec.js',
        enabled: true,
        frontend: { domains: [`${ROUTE_ID}.oto.tools`] },
        backend: { targets: [{ hostname: 'mirror.otoroshi.io', port: 443, tls: true }] },
        plugins: [
            {
                enabled: true,
                plugin: 'cp:otoroshi.next.plugins.MultiAuthModule',
                config: { auth_modules: [AUTH_MODULE_ID] },
            },
        ],
    });

    await waitForProxyState(
        `${PRIVATE_APPS}/privateapps/generic/choose-provider?route=${ROUTE_ID}`,
        (status, body) => status === 200 && body.includes(AUTH_MODULE_NAME)
    );
});

async function waitForProxyState(url, isReady, timeoutMs = 30_000) {
    const deadline = Date.now() + timeoutMs;
    let last = '';
    while (Date.now() < deadline) {
        const res = await fetch(url);
        const body = await res.text();
        if (isReady(res.status, body)) return;
        last = `${res.status} ${body.replace(/\s+/g, ' ').slice(0, 200)}`;
        await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`setup: ${url} not ready after ${timeoutMs}ms (last response: ${last})`);
}

function collectPageErrors(page) {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));
    return errors;
}

test('backoffice simple login page renders through backoffice.js', async ({ page }) => {
    const errors = collectPageErrors(page);

    await page.goto('/bo/simple/login');

    await expect(page.locator('input[name="email"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Login', exact: true })).toBeVisible();

    expect(await page.evaluate(() => typeof window.Otoroshi.login)).toBe('function');
    expect(errors, 'no uncaught error while rendering /bo/simple/login').toEqual([]);
});

test('private apps simple login page renders through simplelogin.js', async ({ page }) => {
    const errors = collectPageErrors(page);

    await page.goto(`${PRIVATE_APPS}/privateapps/generic/simple-login?route=${ROUTE_ID}`);

    await expect(page.locator('input[placeholder="Email"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();

    expect(await page.evaluate(() => typeof window.Otoroshi.simpleLogin)).toBe('function');
    expect(errors, 'no uncaught error while rendering the simple-login page').toEqual([]);
});

test('private apps provider chooser renders through multilogin.js', async ({ page }) => {
    const errors = collectPageErrors(page);

    await page.goto(`${PRIVATE_APPS}/privateapps/generic/choose-provider?route=${ROUTE_ID}`);

    // The page lists each configured auth module as a "Continue with" entry.
    await expect(page.getByText(AUTH_MODULE_NAME)).toBeVisible();

    expect(await page.evaluate(() => typeof window.Otoroshi.multiLogin)).toBe('function');
    expect(errors, 'no uncaught error while rendering the choose-provider page').toEqual([]);
});
