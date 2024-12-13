const { test, expect } = require('@playwright/test');

const SECTIONS = {
    MANAGE_RESOURCES: 'Manage resources',
    CONFIGURATION: 'Configuration',
    ANALYTICS: 'Analytics',
    NETWORKING: 'Networking',
    SESSIONS: 'Sessions',
    TOOLING: 'Tooling',
}

async function showTableOfEntity(page, section, tab, expected) {
    await page.goto('/');

    await page.getByText(section).click();
    await page
        .locator(`div[title^="${tab}"]`).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expected ? expected : tab);
}

test('Show Routes', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Routes'));
test('Show Services', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Services'));
test('Show Admins', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Administrator', 'All administrators'));
test('Show Data Exporters', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Data exporters'));
test('Show Apikeys', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Apikeys'));
test('Show Auth. modules', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Auth. modules', 'Authentication modules'));
test('Show Backends', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Backends'));
test('Show Drafts', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Drafts'));
test('Show Error Templates', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Error Templates', 'Error templates'));
test('Show JWT verifiers', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'JWT verifiers', 'Jwt verifiers'));
test('Show Service Groups', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Service groups', 'Groups'));
test('Show TCP Services', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'TCP services', 'Tcp services'));
test('Show Teams', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Teams'));
test('Show Wasm Plugins', async ({ page }) => showTableOfEntity(page, SECTIONS.MANAGE_RESOURCES, 'Wasm Plugins', 'Wasm plugins'));

test('Show Cluster members', async ({ page }) => showTableOfEntity(page, SECTIONS.NETWORKING, 'Cluster members'));
test('Show Connected tunnels', async ({ page }) => showTableOfEntity(page, SECTIONS.NETWORKING, 'Connected tunnels'));
test('Show Eureka servers', async ({ page }) => showTableOfEntity(page, SECTIONS.NETWORKING, 'Eureka servers'));

test('Show Resources loader', async ({ page }) => showTableOfEntity(page, SECTIONS.TOOLING, 'Resources loader'));

test('Show Danger zone', async ({ page }) => showTableOfEntity(page, SECTIONS.CONFIGURATION, 'Danger zone'));

test('Show Alerts log', async ({ page }) => showTableOfEntity(page, SECTIONS.ANALYTICS, 'Alerts log'));
test('Show Analytics', async ({ page }) => showTableOfEntity(page, SECTIONS.ANALYTICS, 'Analytics'));
test('Show Audit log', async ({ page }) => showTableOfEntity(page, SECTIONS.ANALYTICS, 'Audit log'));
test('Show Events log', async ({ page }) => showTableOfEntity(page, SECTIONS.ANALYTICS, 'Events log'));
test('Show Global Status', async ({ page }) => showTableOfEntity(page, SECTIONS.ANALYTICS, 'Global Status', 'Global status'));

