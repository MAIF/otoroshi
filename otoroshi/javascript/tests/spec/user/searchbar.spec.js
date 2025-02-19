const { test, expect } = require('@playwright/test');

test('search bar must return entity', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/playwright/.auth/user.json' });
    const page = await context.newPage();

    await page.goto('/');
    await page.locator('#navbar').click();

    await page.locator('#react-select-2-input').fill(' routes');
    await page.locator('#react-select-2-option-0').getByText('Routes').click();

    expect(page).toHaveURL(/bo\/dashboard\/routes/);

    await context.close()
});
