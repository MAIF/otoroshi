const { test, expect } = require('@playwright/test');

let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
    await context.close();
});

async function shouldDefaultTeamsAndTenant(path, action = 'add item') {
    const page = await context.newPage();
    await page.goto(`/bo/dashboard/${path}`);

    await page.getByRole('button', { name: action, exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText('Default organization - The default organization');
    await expect(page.locator('#content-scroll-container')).toContainText('Default team - Default team created for any otoroshi instance');
}

async function shouldDefaultTenantOnTeam() {
    const page = await context.newPage();
    await page.goto(`/bo/dashboard/teams`);

    await page.getByRole('button', { name: 'add item', exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText('Default organization - The default organization');
}

async function shouldDefaultTeamsAndTenantOnRoutes() {
    const page = await context.newPage();
    await page.goto('/');

    await page.locator('#navbar').click();

    await page.locator('#react-select-2-input').fill('Routes');
    await page.getByRole('option', { name: ` Routes` }).locator('div').click();
    // await page.getByRole('button', { name: ' Add item' }).click();

    await page.getByRole('link', { name: ' Create new route' }).click();
    await page.locator('div').filter({ hasText: /^Location$/ }).nth(1).click();

    await expect(page.locator('#content-scroll-container')).toContainText('Default organization - The default organization');
    await expect(page.locator('#content-scroll-container')).toContainText('Default team - Default team created for any otoroshi instance');
}

test('New Routes got the right entity location', async () => shouldDefaultTeamsAndTenantOnRoutes());
test('New Services got the right entity location', async () => shouldDefaultTeamsAndTenant('services', 'new'));
test('New Data Exporters got the right entity location', async () => shouldDefaultTeamsAndTenant('exporters'));
test('New Apikeys got the right entity location', async () => shouldDefaultTeamsAndTenant('apikeys'));
test('New Auth. modules got the right entity location', async () => shouldDefaultTeamsAndTenant('auth-configs'));
test('New Backends got the right entity location', async () => shouldDefaultTeamsAndTenant('backends'));
test('New Error Templates got the right entity location', async () => shouldDefaultTeamsAndTenant('error-templates'));
test('New JWT verifiers got the right entity location', async () => shouldDefaultTeamsAndTenant('jwt-verifiers'));
test('New Service Groups got the right entity location', async () => shouldDefaultTeamsAndTenant('groups'));
test('New TCP Services got the right entity location', async () => shouldDefaultTeamsAndTenant('tcp/services'));
test('New Teams got the right entity location', async () => shouldDefaultTenantOnTeam());
test('New Wasm Plugins got the right entity location', async () => shouldDefaultTeamsAndTenant('wasm-plugins'));