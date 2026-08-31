// Route built in the UI + Authentication plugin backed by an in-memory auth module seeded via API.

import { test, expect } from '@playwright/test';
import bcrypt from 'bcryptjs';
import { PROXY_ANY, uniqueName } from './_apiHelpers';

test.setTimeout(90_000);

const AUTHS = '/bo/api/proxy/api/auths';
const AUTH_PLUGIN = 'cp:otoroshi.next.plugins.AuthModule';
const USER_EMAIL = 'pw-user@otoroshi.io';
const USER_PASSWORD = 'pw-password';

let context;
let routeId;
let authModuleId;

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterAll(async () => {
  const page = await context.newPage();
  if (routeId) await page.request.delete(`${PROXY_ANY}/routes/${routeId}`).catch(() => {});
  if (authModuleId) await page.request.delete(`${AUTHS}/${authModuleId}`).catch(() => {});
  await page.close();
  await context.close();
});

// secure=false: everything runs over http locally, a secure cookie would never come back.
async function createBasicAuthModule(page, name) {
  const template = await (await page.request.get(`${AUTHS}/_template?mod-type=basic`)).json();
  const id = uniqueName('auth_mod_pw');
  const res = await page.request.post(AUTHS, {
    data: {
      ...template,
      id,
      name,
      desc: 'Auto-seeded by Playwright route-auth-module.spec.js',
      sessionCookieValues: { ...template.sessionCookieValues, secure: false },
      users: [
        {
          name: 'PW user',
          email: USER_EMAIL,
          password: bcrypt.hashSync(USER_PASSWORD, 10),
          metadata: {},
          tags: [],
        },
      ],
    },
  });
  expect(res.status(), await res.text()).toBeLessThan(400);
  return id;
}

// The proxy state syncs every 10s: wait until the route answers with the login redirect.
async function waitForLoginRedirect(page, url) {
  await expect
    .poll(
      async () => {
        const res = await page.request.get(url, { maxRedirects: 0 }).catch(() => null);
        return res ? `${res.status()} ${res.headers()['location'] || ''}` : '';
      },
      { timeout: 30_000, intervals: [1_000] }
    )
    .toMatch(/^303 .*\/privateapps\/generic\/login/);
}

test('a route with the Authentication plugin asks for a login before reaching the backend', async () => {
  const page = await context.newPage();

  const moduleName = uniqueName('pw-auth-module');
  authModuleId = await createBasicAuthModule(page, moduleName);

  await page.goto('/bo/dashboard/routes');
  await page.getByRole('link', { name: ' Create new route' }).click();
  await page
    .getByRole('textbox', { name: 'The name of your route. Only' })
    .fill(uniqueName('pw-auth-route'));
  await Promise.all([
    page.waitForURL(/\/routes\/route_[^/?]+\?tab=flow/, { timeout: 15_000 }),
    page.getByRole('button', { name: 'Create route' }).click(),
  ]);
  routeId = page.url().match(/\/routes\/(route_[^/?#]+)/)[1];
  await expect(page.locator('#form-container')).toContainText('http://newroute.oto.tools:9999/*');

  // Frontend node: swap the default domain for a test one.
  const domain = `${uniqueName('pw-auth')}.oto.tools`;
  const routeUrl = `http://${domain}:9999/`;
  await page.locator('.dot.frontend-container-button').click();
  const domainInput = page.locator('#form input.form-control[type="text"]').first();
  await expect(domainInput).toHaveValue('newroute.oto.tools');
  await domainInput.fill(domain);

  // Authentication plugin, searched by id, then the module picked through the wizard.
  await page.locator('.search-plugin').fill('plugins.AuthModule');
  await page
    .locator('.element', { has: page.locator('p', { hasText: /^Authentication$/ }) })
    .first()
    .locator('.element-arrow')
    .click();
  await page.getByRole('button', { name: 'Select a module' }).click();
  await page.getByRole('button', { name: /Use an existing Authentication/ }).click();
  await page.getByText('Select a authentication configuration to continue').click();
  await page.keyboard.type(moduleName);
  await page.keyboard.press('Enter');
  await expect(page.locator('#form')).toContainText(moduleName);

  await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'PUT' && r.url().includes(routeId) && r.status() < 400,
      { timeout: 15_000 }
    ),
    page.getByRole('button', { name: 'Save', exact: true }).click(),
  ]);

  const saved = await (await page.request.get(`${PROXY_ANY}/routes/${routeId}`)).json();
  expect(saved.frontend.domains).toEqual([domain]);
  expect(saved.plugins.some((p) => p.plugin === 'cp:otoroshi.next.plugins.OverrideHost')).toBe(true);
  const authPlugin = saved.plugins.find((p) => p.plugin === AUTH_PLUGIN);
  expect(authPlugin?.config?.module ?? authPlugin?.config?.auth_module).toBe(authModuleId);

  // New tab: login page first, then the backend echo shows the host rewritten by Override Host.
  await waitForLoginRedirect(page, routeUrl);
  const tab = await context.newPage();
  await tab.goto(routeUrl);
  await expect(tab).toHaveURL(/privateapps\.oto\.tools:9999\/privateapps\/generic\/login/);
  await tab.locator('input[name="username"]').fill(USER_EMAIL);
  await tab.locator('input[name="password"]').fill(USER_PASSWORD);
  await Promise.all([
    tab.waitForURL(routeUrl, { timeout: 15_000 }),
    tab.getByRole('button', { name: 'Login' }).click(),
  ]);
  await expect(tab.locator('body')).toContainText('"host":"request.otoroshi.io"');

  // The session cookie sticks: no login asked again on reload.
  await tab.reload();
  await expect(tab).toHaveURL(routeUrl);
  await expect(tab.locator('body')).toContainText('"host":"request.otoroshi.io"');

  await tab.close();
  await page.close();
});
