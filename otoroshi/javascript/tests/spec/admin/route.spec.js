const { test, expect } = require('@playwright/test')

let context

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' })
})

test.afterAll(async () => {
    await context.close()
})

async function createRoute(page) {
    await page.goto('/bo/dashboard/routes')
    await page.getByRole('link', { name: ' Create new route' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).fill('My first route');

    await page.getByRole('button', { name: 'Create route' }).click();
    await expect(page.locator('#form-container')).toContainText('http://newroute.oto.tools:9999/*');
}

async function deleteRoute(page) {
    await page.getByRole('link', { name: ' Informations' }).click();
    await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click();
    await page.getByRole('button', { name: 'Delete this route' }).click();
    await page.getByRole('button', { name: 'Ok' }).click();
}

test('Should be able to create a route', async () => {
    const page = await context.newPage()
    await createRoute(page)
    await deleteRoute(page)
})

async function editDraftName(page) {
    await page.getByRole('button', { name: 'Draft' }).click();
    await expect(page.getByRole('button', { name: 'Publish draft' })).toBeVisible();
    await page.getByRole('link', { name: ' Informations' }).click();
    await expect(page.getByRole('button', { name: 'Draft', exact: true })).toBeVisible();

    await page.getByRole('textbox', { name: 'The name of your route. Only' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).press('ControlOrMeta+a');
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).fill('draft version');

    await page.getByRole('button', { name: 'Published' }).click();
    await expect(page.getByRole('textbox', { name: 'The name of your route. Only' })).toHaveValue('My first route');

    await page.getByRole('button', { name: 'Draft' }).click();
    await expect(page.getByRole('textbox', { name: 'The name of your route. Only' })).toHaveValue('draft version');
}

test('Should be able to edit the draft', async () => {
    const page = await context.newPage()
    await createRoute(page)
    await editDraftName(page)
    await deleteRoute(page)
})

test('Should be able to publish draft changes', async () => {
    const page = await context.newPage()
    await createRoute(page)

    await editDraftName(page)

    await page.getByRole('button', { name: 'Draft', exact: true }).click();
    await page.getByRole('button', { name: 'Publish draft' }).click();
    await page.getByRole('button', { name: 'I want to publish this draft to production' }).click();
        
    await page.getByRole('button', { name: 'Published' }).click();
    await expect(page.getByRole('textbox', { name: 'The name of your route. Only' })).toHaveValue('draft version');

    await deleteRoute(page)
})