const { test, expect } = require('@playwright/test');
const { SECTIONS } = require('../../utils');

test('create an apikey', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/playwright/.auth/user.json' });
    const page = await context.newPage();

    await page.goto('/');
    await page.getByText(SECTIONS.MANAGE_RESOURCES).click();
    await page
        .locator(`div[title^="Apikeys"]`).click();

    await page
        .locator(`button[title^="Create a new Apikey"]`).click();

    await page.getByPlaceholder('The name of the client (ie.').click();
    await page.getByPlaceholder('The name of the client (ie.').fill('apikey name');
    await page.getByPlaceholder('A useful description for this').click();
    await page.getByPlaceholder('A useful description for this').fill('apikey description');
    await page.getByRole('button', { name: 'Create Apikey' }).click();
    await expect(page.getByRole('grid')).toContainText('apikey name');

    await context.close()
});
