// Plugin chain drawer, on the two entities that embed it: api plans and apikeys.
//
// Api plan: create the plan in the UI with two plugins picked in the drawer, save,
// reopen it and check both are still there. Then add a third one without saving:
// the summary shows it, but a reload brings the plan back to two plugins.
//
// Apikey: add a plugin in the drawer, check the summary, and prove the chain is
// only persisted when the apikey itself is saved.

import { test, expect } from '@playwright/test';
import {
  PROXY_ANY,
  createApiViaApi,
  cleanupApi,
  getDraft,
  uniqueName,
} from './_apiHelpers';

test.setTimeout(60_000);

// Searched by id, so this does not depend on the label the catalog returns.
// The label is what the summary renders: last segment of the id, de-camel-cased.
const PLUGIN_A = { id: 'OverrideHost', label: 'Override Host' };
const PLUGIN_B = { id: 'AdditionalHeadersIn', label: 'Additional Headers In' };
const PLUGIN_C = { id: 'RemoveHeadersIn', label: 'Remove Headers In' };

let context;
const trackedApis = new Set();
const trackedApikeys = new Set();

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterEach(async () => {
  if (trackedApis.size === 0 && trackedApikeys.size === 0) return;
  const page = await context.newPage();
  for (const id of trackedApikeys) {
    await page.request.delete(`${PROXY_ANY}/apikeys/${id}`).catch(() => {});
  }
  for (const id of trackedApis) {
    await cleanupApi(page, id);
  }
  trackedApis.clear();
  trackedApikeys.clear();
  await page.close();
});

test.afterAll(async () => {
  await context.close();
});

const summary = (page) => page.getByTestId('plugins-chain-summary');
const summaryItems = (page) => summary(page).locator('li');

// The Plugins section is collapsed by default on both forms.
async function openPluginsSection(page) {
  await page
    .locator('div')
    .filter({ hasText: /^Plugins$/ })
    .first()
    .click();
  await expect(page.getByTestId('plugins-chain-open')).toBeVisible({ timeout: 15_000 });
}

async function addPluginsInDrawer(page, plugins) {
  await page.getByTestId('plugins-chain-open').click();

  const drawer = page.locator('.plugins-chain-drawer');
  await expect(drawer).toBeVisible();

  const search = drawer.locator('.search-plugin');
  await expect(search).toBeVisible({ timeout: 15_000 });

  for (const plugin of plugins) {
    await search.fill(plugin.id);
    await drawer.locator('.element-arrow').first().click();
  }

  await page.getByTestId('plugins-chain-close').click();
  await expect(drawer).toBeHidden();
}

test('api plan: two plugins survive the save, an unsaved third one does not', async () => {
  const page = await context.newPage();

  const apiId = await createApiViaApi(page, { name: uniqueName('chain-plan') });
  trackedApis.add(apiId);

  // Create the plan in the UI, with two plugins picked in the drawer.
  await page.goto(`/bo/dashboard/apis/${apiId}/plans/new`);
  await openPluginsSection(page);
  await expect(page.getByText('No plugin in this chain')).toBeVisible();

  await addPluginsInDrawer(page, [PLUGIN_A, PLUGIN_B]);
  await expect(summaryItems(page)).toHaveCount(2);
  await expect(summary(page)).toContainText(PLUGIN_A.label);
  await expect(summary(page)).toContainText(PLUGIN_B.label);

  // Saving navigates back to the plans list.
  await page.getByRole('button', { name: /^Create\s+/ }).click();
  await page.waitForURL(/\/plans(\?|$)/, { timeout: 15_000 });

  // The form generates the plan id: fetch it back from the draft.
  const draft = await getDraft(page, apiId);
  const plan = (draft.content?.plans || [])[0];
  expect(plan).toBeTruthy();
  const planUrl = `/bo/dashboard/apis/${apiId}/plans/${plan.id}/edit`;

  // Reopen the plan: both plugins are there.
  await page.goto(planUrl);
  await openPluginsSection(page);
  await expect(summaryItems(page)).toHaveCount(2);
  await expect(summary(page)).toContainText(PLUGIN_A.label);
  await expect(summary(page)).toContainText(PLUGIN_B.label);

  // A third plugin added without saving feeds the summary...
  await addPluginsInDrawer(page, [PLUGIN_C]);
  await expect(summaryItems(page)).toHaveCount(3);
  await expect(summary(page)).toContainText(PLUGIN_C.label);

  // ...but a reload drops it: the plan still has its two saved plugins.
  await page.goto(planUrl);
  await openPluginsSection(page);
  await expect(summaryItems(page)).toHaveCount(2);
  await expect(summary(page)).not.toContainText(PLUGIN_C.label);

  await page.close();
});

test('apikey: the drawer feeds the summary, and the chain needs a save to survive a reload', async () => {
  const page = await context.newPage();

  const template = await (await page.request.get(`${PROXY_ANY}/apikeys/_template`)).json();
  const clientId = uniqueName('chainkey').replace(/-/g, '_');
  const created = await page.request.post(`${PROXY_ANY}/apikeys`, {
    data: { ...template, clientId, clientName: 'chain apikey' },
  });
  expect(created.status()).toBeLessThan(400);
  trackedApikeys.add(clientId);

  const apikeyUrl = `/bo/dashboard/apikeys/edit/${clientId}`;

  await page.goto(apikeyUrl);
  await openPluginsSection(page);
  await expect(page.getByText('No plugin in this chain')).toBeVisible();

  await addPluginsInDrawer(page, [PLUGIN_A]);
  await expect(summary(page)).toContainText(PLUGIN_A.label);

  // Nothing was saved: the chain is gone.
  await page.goto(apikeyUrl);
  await openPluginsSection(page);
  await expect(summary(page)).toHaveCount(0);

  await addPluginsInDrawer(page, [PLUGIN_A]);
  await expect(summary(page)).toContainText(PLUGIN_A.label);

  // The apikey table stays on the form after saving, so wait on the request itself.
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'PUT' && r.status() < 400, {
      timeout: 15_000,
    }),
    page.getByRole('button', { name: /Update Api[Kk]ey/ }).click(),
  ]);

  await page.goto(apikeyUrl);
  await openPluginsSection(page);
  await expect(summary(page)).toContainText(PLUGIN_A.label);

  await page.close();
});
