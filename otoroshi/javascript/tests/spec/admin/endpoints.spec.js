const { test, expect } = require('@playwright/test')

let context

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' })
})

test.afterAll(async () => {
    await context.close()
})

test('Should be able to get public keys in keys field', async () => {
    const cookies = await context.cookies();

    fetch('http://otoroshi.oto.tools:9999/.well-known/jwks.json', {
        cookies
    })
        .then(r => r.json())
        .then(rawKeys => expect(rawKeys.keys).toBeDefined())
})

test('Should be able to create a JWT verifier with || and ::', async () => {
    const page = await context.newPage()

    await page.goto('/bo/dashboard/routes')


    await page.getByRole('link', { name: ' Create new route' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).fill('JWT Verifier test');
    await page.getByRole('button', { name: 'Create route' }).click();
    await page.getByRole('textbox', { name: 'Search the plugin' }).click();
    await page.getByRole('textbox', { name: 'Search the plugin' }).fill('jwt ');
    await page.locator('div').filter({ hasText: /^AccessControlJwt verifiersJwt verification only$/ }).locator('i').nth(1).click();
    
    
})