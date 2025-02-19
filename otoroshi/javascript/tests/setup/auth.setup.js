import { test as setup, expect } from '@playwright/test';
import path from 'path';

const authFile = path.join(__dirname, '../playwright/.auth/user.json');

setup('authenticate', async ({ page }) => {
    // Perform authentication steps. Replace these actions with your own.
    await page.goto('/');
    await page.getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[name="email"]').click();
    await page.locator('input[name="email"]').fill('admin@otoroshi.io');
    await page.locator('input[name="email"]').press('Tab');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', { name: 'Login' }).click();

    await page.waitForURL('/bo/dashboard');

    await expect(page.locator('#content-scroll-container img')).toBeVisible();

    await page.context().storageState({ path: authFile });
});