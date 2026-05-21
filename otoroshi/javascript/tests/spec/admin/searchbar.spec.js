import { test, expect } from '@playwright/test';

let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
    await context.close();
});

test('access to the admin api', async () => {
    const page = await context.newPage()
    await page.goto('/');

    await page.locator('#navbar').click();

    // react-select auto-generates ids (`react-select-N-input`) from a global
    // mount counter — the number is not stable across renders/CI. Target the
    // search input inside the navbar instead.
    const searchInput = page.locator('#navbar input').first();
    await searchInput.fill(' admin');

    const adminOption = page.getByText('rout.otoroshi-admin-api');
    await expect(adminOption).toBeVisible({ timeout: 10_000 });
    await adminOption.click();

    await expect(page).toHaveURL(/\/bo\/dashboard\/routes\/admin-api-service/);
});