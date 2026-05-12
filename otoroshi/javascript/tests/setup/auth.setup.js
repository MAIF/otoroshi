import { test as setup, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { validAnonymousModal } from '../utils';

const userAuthFile = path.join(__dirname, '../playwright/.auth/tester.json');
const adminAuthFile = path.join(__dirname, '../playwright/.auth/admin.json');

// Admin API key shipped with default Otoroshi config. Used to seed the
// `tester` org / team / user the entity-location specs depend on.
const ADMIN_API = 'http://otoroshi-api.oto.tools:9999';
const ADMIN_API_HEADERS = {
    'Content-Type': 'application/json',
    'Otoroshi-Client-Id': 'admin-api-apikey-id',
    'Otoroshi-Client-Secret': 'admin-api-apikey-secret',
};

// Idempotent upsert. Otoroshi's PUT /api/<entity>/:id requires the entity to
// already exist (404 otherwise — see CrudControllerHelper.updateEntity), so we
// GET first and route to POST or PUT accordingly. Tolerates 409 on POST in
// case of a concurrent setup run.
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
            // Otoroshi returns 400 with "Entity already exists" on race conditions.
            if (!(create.status === 400 && txt.includes('already exists'))) {
                throw new Error(`setup POST ${collectionUrl} failed: ${create.status} ${txt}`);
            }
        }
        return;
    }
    if (get.status >= 400) {
        const txt = await get.text().catch(() => '');
        throw new Error(`setup GET ${itemUrl} failed: ${get.status} ${txt}`);
    }
    const update = await fetch(itemUrl, {
        method: 'PUT',
        headers: ADMIN_API_HEADERS,
        body: JSON.stringify(body),
    });
    if (update.status >= 400) {
        const txt = await update.text().catch(() => '');
        throw new Error(`setup PUT ${itemUrl} failed: ${update.status} ${txt}`);
    }
}

// Seed the prerequisites for the entity-location specs:
//   - Tester Organization (id: tester)
//   - Tester Team (id: tester-team, in tester tenant)
//   - tester@otoroshi.io admin with rights tester:rw / tester-team:rw
async function ensureTesterTenancySetup() {
    await ensureEntity(`${ADMIN_API}/api/tenants`, {
        id: 'tester',
        name: 'Tester Organization',
        description: 'Auto-seeded by Playwright auth.setup.js',
        metadata: {},
        tags: [],
    });

    await ensureEntity(`${ADMIN_API}/api/teams`, {
        id: 'tester-team',
        tenant: 'tester',
        name: 'Tester Team',
        description: 'Auto-seeded by Playwright auth.setup.js',
        metadata: {},
        tags: [],
    });

    // Simple admin: register if missing (forced super-admin rights by API),
    // then PUT to constrain rights to tester:rw / tester-team:rw — the
    // Location component only auto-fills when the user has a single
    // tenant/team scope. SimpleOtoroshiAdmin.reads requires `password` and
    // `createdAt`, so we re-GET the entity and merge our overrides on top
    // (preserving the bcrypt-hashed password registered above).
    const adminUrl = `${ADMIN_API}/api/admins/simple/tester@otoroshi.io`;
    const existing = await fetch(adminUrl, { headers: ADMIN_API_HEADERS });
    let currentBody;
    if (existing.status === 404) {
        const create = await fetch(`${ADMIN_API}/api/admins/simple`, {
            method: 'POST',
            headers: ADMIN_API_HEADERS,
            body: JSON.stringify({
                username: 'tester@otoroshi.io',
                password: 'password',
                label: 'Tester',
            }),
        });
        if (create.status >= 400) {
            throw new Error(`setup: cannot create tester admin: ${create.status} ${await create.text()}`);
        }
        const refetch = await fetch(adminUrl, { headers: ADMIN_API_HEADERS });
        if (refetch.status >= 400) {
            throw new Error(`setup: cannot read tester admin after create: ${refetch.status} ${await refetch.text()}`);
        }
        currentBody = await refetch.json();
    } else if (existing.status >= 400) {
        throw new Error(`setup: cannot read tester admin: ${existing.status} ${await existing.text()}`);
    } else {
        currentBody = await existing.json();
    }

    const update = await fetch(adminUrl, {
        method: 'PUT',
        headers: ADMIN_API_HEADERS,
        body: JSON.stringify({
            ...currentBody,
            username: 'tester@otoroshi.io',
            label: 'Tester',
            metadata: {},
            type: 'SIMPLE',
            rights: [
                { tenant: 'tester:rw', teams: ['tester-team:rw'] },
            ],
            adminEntityValidators: {},
            _loc: { tenant: 'tester', teams: ['tester-team'] },
        }),
    });
    if (update.status >= 400) {
        throw new Error(`setup: cannot update tester admin rights: ${update.status} ${await update.text()}`);
    }
}

// Auth state lifetime. Otoroshi BO sessions live a lot longer than this, so
// re-logging-in every 30 min is a safe default — set PW_AUTH_TTL_MIN to tune
// (e.g. 0 to disable caching, 240 for a 4h window). PW_FORCE_AUTH=1 wipes
// the cache regardless.
const AUTH_TTL_MIN = Number(process.env.PW_AUTH_TTL_MIN ?? 30);
const FORCE_REAUTH = process.env.PW_FORCE_AUTH === '1';

function isFresh(file) {
    if (FORCE_REAUTH) return false;
    if (!fs.existsSync(file)) return false;
    if (AUTH_TTL_MIN <= 0) return false;
    const ageMs = Date.now() - fs.statSync(file).mtimeMs;
    return ageMs < AUTH_TTL_MIN * 60 * 1000;
}

setup('authenticate', async ({ page }) => {
    if (isFresh(userAuthFile) && isFresh(adminAuthFile)) {
        const userAgeMin = ((Date.now() - fs.statSync(userAuthFile).mtimeMs) / 60_000).toFixed(1);
        const adminAgeMin = ((Date.now() - fs.statSync(adminAuthFile).mtimeMs) / 60_000).toFixed(1);
        console.log(
            `[auth.setup] reusing cached auth state (tester: ${userAgeMin}min, admin: ${adminAgeMin}min, TTL: ${AUTH_TTL_MIN}min). Set PW_FORCE_AUTH=1 to refresh.`
        );
        return;
    }

    console.log(`[auth.setup] refreshing auth state (TTL: ${AUTH_TTL_MIN}min)`);

    // Seed the tester org / team / admin (idempotent). Done before logging
    // in so the tester actually exists with the right scope-narrowing rights.
    await ensureTesterTenancySetup();

    await page.goto('/');
    await validAnonymousModal(page);
    await page.getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[name="email"]').click();
    await page.locator('input[name="email"]').fill('tester@otoroshi.io');
    await page.locator('input[name="email"]').press('Tab');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', { name: 'Login' }).click();

    await page.waitForURL('/bo/dashboard');

    await expect(page.locator('#content-scroll-container img')).toBeVisible();

    await page.context().storageState({ path: userAuthFile });

    // await page.goto('/backoffice/auth0/logout');
    await page.goto('/bo/simple/login');
    await page.getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[name="email"]').click();
    await page.locator('input[name="email"]').fill('admin@otoroshi.io');
    await page.locator('input[name="email"]').press('Tab');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', { name: 'Login' }).click();

    await page.waitForURL('/bo/dashboard');

    await expect(page.locator('#content-scroll-container img')).toBeVisible();

    await page.context().storageState({ path: adminAuthFile });

    const closeButton = await page.$('text=Close');
    if (closeButton) {
        await closeButton.click();
    }
});
