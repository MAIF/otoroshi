import { test, expect } from '@playwright/test'

let context

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' })
})

test.afterAll(async () => {
    await context.close()
})

async function createRoute(page) {
    await page.goto('/bo/dashboard/routes')
    await page.getByRole('link', { name: ' Create new route' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).click();
    await page.getByRole('textbox', { name: 'The name of your route. Only' }).fill('JSON editor route');
    await page.getByRole('button', { name: 'Create route' }).click();
    await expect(page.locator('#form-container')).toContainText('http://newroute.oto.tools:9999/*');
}

async function deleteRoute(page) {
    await page.getByRole('link', { name: ' Informations' }).click();
    await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click();
    await page.getByRole('button', { name: 'Delete this route' }).click();
    await page.getByRole('button', { name: 'Ok' }).click();
}

async function openFrontendJsonEditor(page) {
    await page.locator('.frontend-container-button').first().click();
    await page.getByRole('button', { name: 'Code Editor' }).click();
    await expect(page.locator('#form .monaco-editor').first()).toBeVisible({ timeout: 30_000 });
}

async function setEditorContent(page, content) {
    await page.evaluate((text) => {
        const editors = window.monaco.editor.getEditors();
        const form = document.getElementById('form');
        const editor =
            editors.find((e) => form && form.contains(e.getContainerDomNode())) ||
            editors[editors.length - 1];
        editor.focus();
        editor.setValue(text);
    }, content);
}

test('Route plugin JSON editor does not crash when the JSON becomes invalid', async () => {
    const page = await context.newPage()
    const pageErrors = []
    page.on('pageerror', (err) => pageErrors.push(err))

    await createRoute(page)
    await openFrontendJsonEditor(page)

    await setEditorContent(page, 'this is not json')

    await expect(page.getByText('Invalid JSON')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Visual Editor' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Code Editor' })).toBeVisible()
    expect(pageErrors.filter((e) => e.name === 'SyntaxError')).toHaveLength(0)

    await deleteRoute(page)
})

test('Route plugin JSON editor recovers when a valid JSON is entered again', async () => {
    const page = await context.newPage()
    const pageErrors = []
    page.on('pageerror', (err) => pageErrors.push(err))

    await createRoute(page)
    await openFrontendJsonEditor(page)

    await setEditorContent(page, 'broken')
    await expect(page.getByText('Invalid JSON')).toBeVisible()

    await setEditorContent(page, '{ "plugin": { "domains": ["valid.oto.tools/*"], "strip_path": true } }')

    await expect(page.getByText('Invalid JSON')).toBeHidden()
    await expect(page.getByRole('button', { name: 'Visual Editor' })).toBeVisible()
    expect(pageErrors.filter((e) => e.name === 'SyntaxError')).toHaveLength(0)

    await deleteRoute(page)
})
