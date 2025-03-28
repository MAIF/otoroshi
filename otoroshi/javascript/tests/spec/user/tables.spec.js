const { test, expect, describe } = require('@playwright/test');
const { SECTIONS } = require('../../utils');


let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
    await context.close();
});

async function showTableOfEntity(section, tab, expected) {
    const page = await context.newPage()
    await page.goto('/');

    await page.getByText(section).click();
    await page
        .locator(`div[title^="${tab}"]`).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expected ? expected : tab);
}

test('Show Routes', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Routes'));
test('Show Services', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Services'));
test('Show Admins', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Administrator', 'All administrators'));
test('Show Data Exporters', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Data exporters'));
test('Show Apikeys', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Apikeys'));
test('Show Auth. modules', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Auth. modules', 'Authentication modules'));
test('Show Backends', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Backends'));
test('Show Drafts', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Drafts'));
test('Show Error Templates', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Error Templates', 'Error templates'));
test('Show JWT verifiers', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'JWT verifiers', 'Jwt verifiers'));
test('Show Service Groups', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Service groups', 'Groups'));
test('Show TCP Services', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'TCP services', 'Tcp services'));
test('Show Teams', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Teams'));
test('Show Wasm Plugins', async () => showTableOfEntity(SECTIONS.MANAGE_RESOURCES, 'Wasm Plugins', 'Wasm plugins'));