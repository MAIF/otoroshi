import { test as setup, expect } from '@playwright/test';
import path from 'path';

const userAuthFile = path.join(__dirname, '../playwright/.auth/user.json');
const adminAuthFile = path.join(__dirname, '../playwright/.auth/admin.json');

setup('authenticate', async ({ page }) => {
    await page.goto('/');
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
});