const { test, expect } = require('@playwright/test')

let context

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' })
})

test.afterAll(async () => {
    await context.close()
})

async function deleteAPI(page) {
    await page.getByRole('link', { name: ' Informations' }).click()
    await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click()
    await page.getByRole('button', { name: 'Delete this API' }).click()
    await page.getByRole('button', { name: 'Ok' }).click()
}

async function createAPI(page) {
    await page.goto('/bo/dashboard/apis')
    await page.getByRole('link', { name: ' Create new API' }).click()
    await page.getByRole('textbox', { name: 'Name' }).click()
    await page.getByRole('textbox', { name: 'Name' }).press('ControlOrMeta+a')
    await page.getByRole('textbox', { name: 'Name' }).fill('My new API')
    await page.getByRole('textbox', { name: 'Name' }).press('Tab')
    await page.getByRole('textbox', { name: 'Description' }).fill('The best API')
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByText('DEV', { exact: true })).toBeVisible()
    await expect(page.getByText('0/sec')).toBeVisible()
    await expect(page.getByText('0', { exact: true }).first()).toBeVisible()
}

test('Should be able to create an API', async () => {
    const page = await context.newPage()
    await createAPI(page)
    await deleteAPI(page)
})

async function publishedDraftAPI(page) {
    await page.getByText('Create a new Route').click()
    await page.getByRole('textbox').click()
    await page.getByRole('textbox').press('ControlOrMeta+ArrowLeft')
    await page.getByRole('textbox').press('Alt+Shift+ArrowRight')
    await page.getByRole('textbox').fill('my-frst-api.oto.tools')
    
    await page.getByText('3. Add plugins to your route').click();
    await expect(page.getByText('default_flow')).toBeVisible()

    await page.getByText('4. Configure the backend').click();
    await expect(page.getByText('LOCALdefault_backend')).toBeVisible()

    await page.getByText('5. Additional informations').click();    
    await page.getByRole('textbox', { name: 'My users route' }).click()
    await page.getByRole('textbox', { name: 'My users route' }).fill('My first API route')
    await page.getByRole('button', { name: 'Create DEV' }).click()
    await expect(page.locator('div').filter({ hasText: /^Routes 1$/ }).locator('span')).toBeVisible()

    await page.getByText('Consumers apply security').click()
    await page.getByRole('button', { name: 'keyless' }).click()
    await page.locator('#content-scroll-container input[type="text"]').click()
    await page.locator('#content-scroll-container input[type="text"]').fill('my first keyless consumer')
    await page.getByRole('button', { name: 'published' }).click();
    await page.getByRole('button', { name: 'Create DEV' }).click()

    await page.getByText('Learn about testing API').click()
    await page.locator('div').filter({ hasText: /^Off$/ }).locator('div').nth(1).click()
    await page.getByRole('link', { name: ' Overview' }).click()

    await page.getByText('Publish your API to the').click()
    await page.getByRole('button', { name: 'Publish and expose to the' }).click()
    await expect(page.getByRole('heading', { name: 'New API PROD' }).locator('span')).toBeVisible()
    await expect(page.locator('div').filter({ hasText: /^PRODPublished$/ }).nth(2)).toBeVisible()
}

test('Should be able to create a route, a consumer and to publish an API', async () => {
    const page = await context.newPage()
    await createAPI(page)
    await publishedDraftAPI(page)
    await deleteAPI(page)
})

test('Subscribers can only subscribe to published consumers', async () => {
    const page = await context.newPage()
    await createAPI(page)
    await publishedDraftAPI(page)

    await page.getByRole('button', { name: 'Subscribe' }).click();
    await page.locator('#content-scroll-container form div').filter({ hasText: 'OwnershipOwner Published' }).getByRole('textbox').click();
    await page.locator('#content-scroll-container form div').filter({ hasText: 'OwnershipOwner Published' }).getByRole('textbox').fill('me');
    await expect(page.getByText('my first keyless consumer')).toBeVisible();
    await page.getByRole('button', { name: 'Create PROD' }).click();
    await page.getByRole('link', { name: ' Overview' }).click();
    await expect(page.getByText('New API Consumer Subscription').first()).toBeVisible();

    await deleteAPI(page)
})

async function createApikeyConsumer(page) {
    await page.getByRole('link', { name: ' Consumers' }).click();
    await page.getByRole('link', { name: ' Create new consumer' }).click();
    await page.locator('#content-scroll-container input[type="text"]').click();
    await page.locator('#content-scroll-container input[type="text"]').fill('apikeys consumer');
    await page.getByRole('button', { name: 'published' }).click();
    await page.getByRole('button', { name: 'apikey' }).click();
    await page.getByRole('button', { name: 'Create PROD' }).click();
}

async function assignConsumerOnFlow(page) {
    await page.getByRole('link', { name: ' Flows' }).click();
    await page.getByRole('link', { name: ' Create new Flow' }).click();
    await page.getByText('apikeyOff').click();
    await page.getByRole('button', { name: 'Create PROD' }).click();
    await page.getByRole('link', { name: ' Flows' }).click();
    await page.getByRole('button', { name: '' }).nth(0).click();
    await expect(page.locator('div').filter({ hasText: /^Apikeys$/ }).first()).toBeVisible();
    await page.getByRole('link', { name: ' Flows' }).click();
}

test('API keys consumer selected in a flow should apply the API Keys plugin.', async () => {
    const page = await context.newPage()
    await createAPI(page)
    await publishedDraftAPI(page)

    // create and published a new consumer
    await createApikeyConsumer(page)

    // create a new flow and select the consumer
    await assignConsumerOnFlow(page)

    await deleteAPI(page)
})

test('Draft version should be promote in production environment', async () => {
    const page = await context.newPage()
    await createAPI(page)
    await publishedDraftAPI(page)


    await page.locator('#sidebar svg').nth(1).click();
    await page.getByRole('option', { name: 'Draft' }).click();
    await page.getByRole('link', { name: ' Informations' }).click();
    await page.getByRole('textbox').first().click();
    await page.getByRole('textbox').first().press('ControlOrMeta+a');
    await page.getByRole('textbox').first().fill('my draft version');
    await page.getByRole('button', { name: 'Update DEV' }).click();
    await page.getByRole('button', { name: 'Publish new version' }).click();
    await page.getByRole('button', { name: 'major' }).click();

    await page.getByRole('button', { name: 'I want to publish this API' }).click();


    await expect(page.getByRole('heading', { name: 'my draft version PROD' })).toBeVisible();

    // await deleteAPI(page)
})