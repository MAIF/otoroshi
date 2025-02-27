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
    const page = await context.newPage();
    await page.goto('/');

    await page.getByText(section).click();
    await page
        .locator(`div[title^="${tab}"]`).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expected ? expected : tab);
}

test('Show Cluster members', async () => showTableOfEntity(SECTIONS.NETWORKING, 'Cluster members', undefined, { admin: true }));
test('Show Connected tunnels', async () => showTableOfEntity(SECTIONS.NETWORKING, 'Connected tunnels'));
test('Show Eureka servers', async () => showTableOfEntity(SECTIONS.NETWORKING, 'Eureka servers'));

test('Show Resources loader', async () => showTableOfEntity(SECTIONS.TOOLING, 'Resources loader'));

test('Show Danger zone', async () => showTableOfEntity(SECTIONS.CONFIGURATION, 'Danger zone'));

test('Show Alerts log', async () => showTableOfEntity(SECTIONS.ANALYTICS, 'Alerts log'));
test('Show Analytics', async () => showTableOfEntity(SECTIONS.ANALYTICS, 'Analytics'));
test('Show Audit log', async () => showTableOfEntity(SECTIONS.ANALYTICS, 'Audit log'));
test('Show Events log', async () => showTableOfEntity(SECTIONS.ANALYTICS, 'Events log'));
test('Show Global Status', async () => showTableOfEntity(SECTIONS.ANALYTICS, 'Global Status', 'Global status'));