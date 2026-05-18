// Entity-location defaults — ADMIN user variant.
// Logs in as admin@otoroshi.io (super-admin), so the default location should
// resolve to "Default organization" + "Default team".
// See tester.spec.js for the constrained-rights variant.
import { test, expect } from '@playwright/test';

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
    await expect(page.locator('#content-scroll-container')).toContainText('Default Team - The default Team of the default organization');
}

async function shouldDefaultTenantOnTeam() {
    const page = await context.newPage();
    await page.goto(`/bo/dashboard/teams`);

    await page.getByRole('button', { name: 'add item', exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText('Default organization - The default organization');
}

async function shouldDefaultTeamsAndTenantOnRoutes() {
    const page = await context.newPage();
    await page.goto('/bo/dashboard/routes');

    await page.getByRole('link', { name: /Create new route/ }).click();
    await page.locator('div').filter({ hasText: /^Location$/ }).nth(1).click();

    await expect(page.locator('#content-scroll-container')).toContainText('Default organization - The default organization');
    await expect(page.locator('#content-scroll-container')).toContainText('Default Team - The default Team of the default organization');
}

test('[admin] New Routes got the right entity location', async () => shouldDefaultTeamsAndTenantOnRoutes());
test('[admin] New Data Exporters got the right entity location', async () => shouldDefaultTeamsAndTenant('exporters'));
test('[admin] New Apikeys got the right entity location', async () => shouldDefaultTeamsAndTenant('apikeys'));
test('[admin] New Auth. modules got the right entity location', async () => shouldDefaultTeamsAndTenant('auth-configs'));
test('[admin] New Backends got the right entity location', async () => shouldDefaultTeamsAndTenant('backends'));
test('[admin] New Error Templates got the right entity location', async () => shouldDefaultTeamsAndTenant('error-templates'));
test('[admin] New JWT verifiers got the right entity location', async () => shouldDefaultTeamsAndTenant('jwt-verifiers'));
test('[admin] New Service Groups got the right entity location', async () => shouldDefaultTeamsAndTenant('groups'));
test('[admin] New TCP Services got the right entity location', async () => shouldDefaultTeamsAndTenant('tcp/services'));
test('[admin] New Teams got the right entity location', async () => shouldDefaultTenantOnTeam());
test('[admin] New Wasm Plugins got the right entity location', async () => shouldDefaultTeamsAndTenant('wasm-plugins'));
