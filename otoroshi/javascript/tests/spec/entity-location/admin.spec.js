// Entity-location defaults — ADMIN user variant.
// Logs in as admin@otoroshi.io (super-admin), so the default location should
// resolve to the default organization + default team.
// See tester.spec.js for the constrained-rights variant.
import { test, expect } from '@playwright/test';

let context;
// The default org/team name+description differ between a fresh instance and
// an older datastore (two divergent seed paths in Env.scala). Don't hardcode
// them — fetch the real entities and build the expected "<name> - <desc>"
// strings the Location component renders.
let expectedOrgText;
let expectedTeamText;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });

    const page = await context.newPage();
    const tenant = await (await page.request.get('/bo/api/proxy/api/tenants/default')).json();
    const team = await (await page.request.get('/bo/api/proxy/api/teams/default')).json();
    expectedOrgText = `${tenant.name} - ${tenant.description}`;
    expectedTeamText = `${team.name} - ${team.description}`;
    await page.close();
});

test.afterAll(async () => {
    await context.close();
});

async function shouldDefaultTeamsAndTenant(path, action = 'add item') {
    const page = await context.newPage();
    await page.goto(`/bo/dashboard/${path}`);

    await page.getByRole('button', { name: action, exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expectedOrgText);
    await expect(page.locator('#content-scroll-container')).toContainText(expectedTeamText);
}

async function shouldDefaultTenantOnTeam() {
    const page = await context.newPage();
    await page.goto(`/bo/dashboard/teams`);

    await page.getByRole('button', { name: 'add item', exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expectedOrgText);
}

async function shouldDefaultTeamsAndTenantOnRoutes() {
    const page = await context.newPage();
    await page.goto('/bo/dashboard/routes');

    await page.getByRole('link', { name: /Create new route/ }).click();
    await page.locator('div').filter({ hasText: /^Location$/ }).nth(1).click();

    await expect(page.locator('#content-scroll-container')).toContainText(expectedOrgText);
    await expect(page.locator('#content-scroll-container')).toContainText(expectedTeamText);
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
