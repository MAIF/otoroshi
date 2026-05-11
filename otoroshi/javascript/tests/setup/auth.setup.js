import { test as setup, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { validAnonymousModal } from '../utils';

const userAuthFile = path.join(__dirname, '../playwright/.auth/tester.json');
const adminAuthFile = path.join(__dirname, '../playwright/.auth/admin.json');

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
