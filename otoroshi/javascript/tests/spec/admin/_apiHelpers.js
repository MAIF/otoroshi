// Shared helpers for the API lifecycle Playwright suites.
// Underscore prefix so Playwright doesn't pick it up as a spec file.

const { expect } = require('@playwright/test');
const { validAnonymousModal } = require('../../utils');

const PROXY_ANY = '/bo/api/proxy/apis/any/v1';
const PROXY_APIS = '/bo/api/proxy/apis/apis.otoroshi.io/v1';

async function createApiViaUI(page, { name, description = 'lifecycle test api' } = {}) {
  const finalName = name || uniqueName('lifecycle-api');
  await page.goto('/bo/dashboard/apis');
  await validAnonymousModal(page);

  // The "Create new API" Link uses an inline onClick that fetches the template and
  // then history.push() to apis/<id>/new. Wait explicitly for that URL change —
  // otherwise the next selector races against an unfinished navigation.
  await Promise.all([
    page.waitForURL(/\/new(\?|$)/, { timeout: 15_000 }),
    page.getByRole('link', { name: /Create new API/ }).click(),
  ]);

  // Picker step — click the "Build from Scratch" card via its title text.
  await page.locator('button:has-text("Build from Scratch")').click();
  await page.getByRole('button', { name: 'Continue', exact: true }).click();

  // Form step — `id` is disabled, Location renders an unknown number of inputs.
  // Editable text inputs in declaration order end with: ... name, description.
  // Wait for description's empty input to be ready, then fill last-2 + last.
  const editable = page.locator('input[type="text"]:not([disabled])');
  await expect(editable.last()).toBeVisible({ timeout: 10_000 });
  const count = await editable.count();
  if (count < 2) {
    throw new Error(`expected at least 2 editable text inputs, found ${count}`);
  }
  await editable.nth(count - 2).fill(finalName);
  await editable.nth(count - 1).fill(description);
  // Click Create and wait for the wizard to redirect to the API page
  // (no DEV/PROD toggle exists yet — toggle only appears after first publish).
  await Promise.all([
    page.waitForURL(/\/apis\/api_[^/]+(\?|$)/, { timeout: 15_000 }),
    page.getByRole('button', { name: 'Create', exact: true }).click(),
  ]);

  const match = page.url().match(/\/apis\/(api_[^/?#]+)/);
  if (!match) throw new Error(`could not extract apiId from URL: ${page.url()}`);
  return match[1].replace(/\/.*$/, '');
}

// Unique suffix so concurrent runs / leftover entities don't collide.
function uniqueName(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

async function createApiViaApi(page, { name, description = 'rules test api' } = {}) {
  const finalName = name || uniqueName('rules-api');
  const template = await getJson(page, `${PROXY_ANY}/apis/_template`);
  const body = { ...template, name: finalName, description };
  const created = await postJson(page, `${PROXY_ANY}/apis`, body);
  if (!created || !created.id) {
    throw new Error(`createApiViaApi failed: ${JSON.stringify(created)}`);
  }
  return created.id;
}

async function deleteApiViaUI(page) {
  await page.getByRole('link', { name: ' Informations' }).click();
  await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click();
  await page.getByRole('button', { name: 'Delete this API' }).click();
  await page.getByRole('button', { name: 'Ok' }).click();
}

async function deleteApiViaApi(page, apiId) {
  if (!apiId) return;
  // Order matters: drop the draft first so a stale draft can't resurrect data.
  await page.request.delete(`${PROXY_ANY}/drafts/${apiId}`).catch(() => {});
  await page.request.delete(`${PROXY_ANY}/apis/${apiId}`).catch(() => {});
}

async function getProd(page, apiId) {
  return getJson(page, `${PROXY_ANY}/apis/${apiId}`);
}

async function getDraft(page, apiId) {
  return getJson(page, `${PROXY_ANY}/drafts/${apiId}`);
}

async function getDraftRaw(page, apiId) {
  // Returns the raw HTTP response so callers can assert status (e.g. 404).
  return page.request.get(`${PROXY_ANY}/drafts/${apiId}`);
}

async function putProd(page, apiId, body) {
  return page.request.put(`${PROXY_ANY}/apis/${apiId}`, { data: body });
}

async function putDraft(page, apiId, body) {
  return page.request.put(`${PROXY_ANY}/drafts/${apiId}`, { data: body });
}

async function postDeployment(page, apiId, body) {
  return page.request.post(`${PROXY_APIS}/apis/${apiId}/deployments`, { data: body });
}

// Helpers ---------------------------------------------------------------

async function getJson(page, path) {
  const res = await page.request.get(path);
  return res.json();
}

async function postJson(page, path, body) {
  const res = await page.request.post(path, { data: body });
  return res.json();
}

// Build a published API the fast way (no UI): create + draft + deploy.
// The deployment endpoint requires a draft to exist (otherwise it returns 404).
async function createPublishedApi(page, opts = {}) {
  const apiId = await createApiViaApi(page, opts);
  const api = await getProd(page, apiId);

  // Create the draft wrapper so the deployment endpoint can find it.
  const draftTemplate = await getJson(page, `${PROXY_ANY}/drafts/_template`);
  const draftWrapper = {
    ...draftTemplate,
    id: apiId,
    kind: apiId.split('_')[0],
    name: api.name,
    content: api,
  };
  const createDraftRes = await page.request.post(`${PROXY_ANY}/drafts`, { data: draftWrapper });
  if (createDraftRes.status() >= 400) {
    throw new Error(
      `createPublishedApi: draft creation failed: ${createDraftRes.status()} ${await createDraftRes.text()}`
    );
  }

  const deployment = {
    apiRef: apiId,
    owner: 'tests',
    at: Date.now(),
    apiDefinition: { ...api, deployments: [] },
    draftId: apiId,
    version: api.version || '0.0.1',
  };
  const depRes = await postDeployment(page, apiId, deployment);
  if (depRes.status() >= 400) {
    throw new Error(`createPublishedApi: deploy failed: ${depRes.status()} ${await depRes.text()}`);
  }
  return apiId;
}

// Wipe any leftover APIs from previous (failed) test runs that match a name
// prefix. Conservative on purpose — we only delete what we recognise as ours.
async function wipeLeftovers(page, namePrefixes = []) {
  if (!namePrefixes.length) return;
  const res = await page.request.get(`${PROXY_ANY}/apis`);
  if (res.status() >= 400) return;
  const apis = await res.json();
  const stale = (Array.isArray(apis) ? apis : []).filter(
    (a) => a?.name && namePrefixes.some((p) => a.name.startsWith(p))
  );
  for (const a of stale) {
    await deleteApiViaApi(page, a.id);
  }
}

module.exports = {
  PROXY_ANY,
  PROXY_APIS,
  uniqueName,
  createApiViaUI,
  createApiViaApi,
  createPublishedApi,
  deleteApiViaUI,
  deleteApiViaApi,
  getProd,
  getDraft,
  getDraftRaw,
  putProd,
  putDraft,
  postDeployment,
  wipeLeftovers,
};
