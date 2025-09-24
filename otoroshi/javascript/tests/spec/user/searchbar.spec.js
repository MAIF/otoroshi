const { test, expect } = require('@playwright/test');
const { validAnonymousModal } = require('../../utils');

test('search bar must return entity', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/playwright/.auth/tester.json' });
    const page = await context.newPage();
    await page.goto('/');
    await validAnonymousModal(page)

    await page.locator('#navbar').click();

    await page.locator('#react-select-2-input').fill(' routes');
    await page.locator('#react-select-2-option-0').getByText('Routes').click();

    expect(page).toHaveURL(/bo\/dashboard\/routes/);

    await context.close()
});
