const { test, expect } = require('@playwright/test');
const { SECTIONS } = require('../../utils');

let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
    await context.close();
});

async function deleteAPI() {
    await page.getByRole('link', { name: ' Informations' }).click();
    await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click();
    await page.getByRole('button', { name: 'Delete this API' }).click();
    await page.getByRole('button', { name: 'Ok' }).click();
}

async function createAPI() {
    const page = await context.newPage();
    await page.goto('http://otoroshi.oto.tools:9999/bo/dashboard/apis')

    await page.getByRole('link', { name: ' Create new API' }).click();
    await page.getByRole('textbox', { name: 'Name' }).click();
    await page.getByRole('textbox', { name: 'Name' }).press('ControlOrMeta+a');
    await page.getByRole('textbox', { name: 'Name' }).fill('My new API');
    await page.getByRole('textbox', { name: 'Name' }).press('Tab');
    await page.getByRole('textbox', { name: 'Description' }).fill('The best API');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByText('DEV', { exact: true })).toBeVisible();
    await expect(page.getByText('0/sec')).toBeVisible();
    await expect(page.getByText('0', { exact: true }).first()).toBeVisible();
}

test('Should be able to create an API', async () => {
    await createAPI()
    await deleteAPI()
});

test('Should be able to create a route, a consumer and to publish an API', async () => {
    await createAPI()


    await page.getByText('Create a new Route').click();
    await page.getByRole('textbox').click();
    await page.getByRole('textbox').press('ControlOrMeta+ArrowLeft');
    await page.getByRole('textbox').press('Alt+Shift+ArrowRight');
    await page.getByRole('textbox').fill('my-frst-api.oto.tools');
    await page.locator('div').filter({ hasText: /^2\. Add plugins to your route by selecting a flow$/ }).nth(1).click();
    await expect(page.getByText('default_flow')).toBeVisible();
    await page.locator('div').filter({ hasText: /^3\. Configure the backend$/ }).nth(1).click();
    await expect(page.getByText('LOCALdefault_backend')).toBeVisible();
    await page.locator('div').filter({ hasText: /^4\. Additional informations$/ }).nth(1).click();
    await page.getByRole('textbox', { name: 'My users route' }).click();
    await page.getByRole('textbox', { name: 'My users route' }).press('ControlOrMeta+a');
    await page.getByRole('textbox', { name: 'My users route' }).fill('My first API route');
    await page.getByRole('button', { name: 'Create DEV' }).click();
    await expect(page.locator('div').filter({ hasText: /^Routes 1$/ }).locator('span')).toBeVisible();

    await page.getByText('Consumers apply security').click();
    await page.getByRole('button', { name: 'keyless' }).click();
    await page.locator('#content-scroll-container input[type="text"]').click();
    await page.locator('#content-scroll-container input[type="text"]').press('ControlOrMeta+a');
    await page.locator('#content-scroll-container input[type="text"]').fill('My first ');
    await page.locator('#content-scroll-container input[type="text"]').press('ControlOrMeta+a');
    await page.locator('#content-scroll-container input[type="text"]').fill('my first keless');
    await page.locator('#content-scroll-container input[type="text"]').press('Alt+Shift+ArrowLeft');
    await page.locator('#content-scroll-container input[type="text"]').fill('my first ketyle');
    await page.locator('#content-scroll-container input[type="text"]').press('Alt+Shift+ArrowLeft');
    await page.locator('#content-scroll-container input[type="text"]').fill('my first keyless consummer');
    await page.locator('#content-scroll-container input[type="text"]').press('ArrowLeft');
    await page.locator('#content-scroll-container input[type="text"]').press('ArrowLeft');
    await page.locator('#content-scroll-container input[type="text"]').fill('my first keyless consumer');
    await page.getByRole('button', { name: 'Create DEV' }).click();

    await page.getByText('Learn about testing API').click();
    await page.locator('div').filter({ hasText: /^Off$/ }).locator('div').nth(1).click();
    await page.getByRole('button', { name: 'Update DEV' }).click();

    await page.getByRole('link', { name: ' Overview' }).click();
    await page.getByText('Publish your API to the').click();

    

    await deleteAPI()
});
