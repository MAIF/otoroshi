const { test, expect } = require('@playwright/test');

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

    await page.locator('#react-select-2-input').fill(' admin');
    await page.getByText('rout.otoroshi-admin-api').click();

    expect(page).toHaveURL('/bo/dashboard/routes/admin-api-service?tab=flow');
});