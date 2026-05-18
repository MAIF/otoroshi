import { test, expect } from '@playwright/test';
import { validAnonymousModal } from '../../utils';

let context;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
    await context.close();
});

// Drive the topbar search bar: type a query, pick the option, assert the page.
//   query        — what we type in the search input
//   optionLabel  — the exact label of the menu item we expect to find in the
//                  dropdown (must come from FeaturesPage `graph` / topbar)
//   expectedText — substring expected on the destination page (defaults to
//                  optionLabel)
async function searchAndOpen(query, optionLabel, expectedText) {
    const page = await context.newPage();
    await page.goto('/');
    await validAnonymousModal(page);

    // Focus the topbar search. `getByPlaceholder` resolves to a hidden form
    // input on react-select, so we target the visible placeholder text node
    // and let keyboard.type drive the now-focused combobox input.
    await page
        .locator('div')
        .filter({ hasText: /^Search service, line, etc \.\.\.$/ })
        .nth(2)
        .click();
    await page.keyboard.type(query);

    // Sanity: the option must actually show up in the dropdown.
    const option = page.getByText(optionLabel, { exact: true }).first();
    await expect(option, `'${optionLabel}' should appear in the search dropdown for query '${query}'`).toBeVisible();
    await option.click();

    await expect(page.locator('#content-scroll-container'))
        .toContainText(expectedText || optionLabel);
}

test('Search → HTTP Routes', async () => searchAndOpen('Routes', 'HTTP Routes', 'Routes'));
test('Search → Services', async () => searchAndOpen('Services', 'Services'));
test('Search → Administrators', async () => searchAndOpen('Admin', 'Administrators', 'All administrators'));
test('Search → Data exporters', async () => searchAndOpen('exporter', 'Data exporters'));
test('Search → Apikeys', async () => searchAndOpen('Apikeys', 'Apikeys'));
test('Search → Auth. modules', async () => searchAndOpen('Auth', 'Auth. modules', 'Authentication modules'));
test('Search → Backends', async () => searchAndOpen('Backends', 'Backends'));
test('Search → Drafts', async () => searchAndOpen('Drafts', 'Drafts'));
test('Search → Error Templates', async () => searchAndOpen('Error', 'Error Templates', 'Error templates'));
test('Search → JWT verifiers', async () => searchAndOpen('JWT', 'JWT verifiers', 'Jwt verifiers'));
test('Search → Service groups', async () => searchAndOpen('Service groups', 'Service groups', 'Groups'));
test('Search → TCP services', async () => searchAndOpen('TCP', 'TCP services', 'Tcp services'));
test('Search → Teams', async () => searchAndOpen('Teams', 'Teams'));
test('Search → Wasm Plugins', async () => searchAndOpen('Wasm', 'Wasm Plugins', 'Wasm plugins'));
test('Search → APIs', async () => searchAndOpen('APIs', 'APIs'));
test('Search → Route Tester', async () => searchAndOpen('Tester', 'Route Tester'));
test('Search → Resources loader', async () => searchAndOpen('Resource', 'Resources loader'));
test('Search → Workflows', async () => searchAndOpen('Workflows', 'Workflows'));
