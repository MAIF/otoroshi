const { test, expect } = require('@playwright/test');

test('search bar must return entity', async ({ page }) => {
    await page.goto('/');
    await page.locator('#navbar').click();

    await page.locator('#react-select-2-input').fill(' routes');
    await page.locator('#react-select-2-option-0').getByText('Routes').click();

    expect(page).toHaveURL(/bo\/dashboard\/routes/);
});

test('access to the admin api', async ({ page }) => {
    await page.goto('/');

    await page.locator('#navbar').click();

    await page.locator('#react-select-2-input').fill(' admin');
    await page.getByText('rout.otoroshi-admin-api').click();

    expect(page).toHaveURL('/bo/dashboard/routes/admin-api-service?tab=flow');
});

