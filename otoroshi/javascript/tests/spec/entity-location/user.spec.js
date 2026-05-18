import { test, expect } from '@playwright/test';
import { validAnonymousModal } from '../../utils';

let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/tester.json' });
});

test.afterAll(async () => {
    await context.close();
});

async function shouldDefaultTeamsAndTenant(path, action = 'add item') {
    const page = await context.newPage();
    await validAnonymousModal(page)

    await page.goto(`/bo/dashboard/${path}`);

    await page.getByRole('button', { name: action, exact: false }).click();

    await expect(page.locator('#content-scroll-container')).toContainText('tester-team');
}

async function shouldDefaultTeamsAndTenantOnRoutes() {
    const page = await context.newPage();
    await validAnonymousModal(page)
    await page.goto('/bo/dashboard/routes');

    await page.getByRole('link', { name: /Create new route/ }).click();
    await page.locator('div').filter({ hasText: /^Location$/ }).nth(1).click();

    await expect(page.locator('#content-scroll-container')).toContainText('tester-team');
}

test('New Routes got the right entity location', async () => shouldDefaultTeamsAndTenantOnRoutes());
test('New Data Exporters got the right entity location', async () => shouldDefaultTeamsAndTenant('exporters'));
test('New Apikeys got the right entity location', async () => shouldDefaultTeamsAndTenant('apikeys'));
test('New Auth. modules got the right entity location', async () => shouldDefaultTeamsAndTenant('auth-configs'));
test('New Backends got the right entity location', async () => shouldDefaultTeamsAndTenant('backends'))
test('New JWT verifiers got the right entity location', async () => shouldDefaultTeamsAndTenant('jwt-verifiers'));
test('New Service Groups got the right entity location', async () => shouldDefaultTeamsAndTenant('groups'));
test('New TCP Services got the right entity location', async () => shouldDefaultTeamsAndTenant('tcp/services'));
test('New Wasm Plugins got the right entity location', async () => shouldDefaultTeamsAndTenant('wasm-plugins'));
