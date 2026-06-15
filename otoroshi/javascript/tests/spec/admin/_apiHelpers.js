// Shared helpers for the API lifecycle Playwright suites.
// Underscore prefix so Playwright doesn't pick it up as a spec file.

import { expect } from '@playwright/test';

export const PROXY_ANY = '/bo/api/proxy/apis/any/v1';
export const PROXY_APIS = '/bo/api/proxy/apis/apis.otoroshi.io/v1';

export async function createApiViaUI(page, { name, description = 'lifecycle test api' } = {}) {
  const finalName = name || uniqueName('lifecycle-api');
  await page.goto('/bo/dashboard/apis');


  await Promise.all([
    page.waitForURL(/\/new(\?|$)/, { timeout: 15_000 }),
    page.getByRole('link', { name: /Create new API/ }).click(),
  ]);

  
  await page.locator('button:has-text("Build from Scratch")').click();
  await page.getByRole('button', { name: 'Continue', exact: true }).click();

  const editable = page.locator('input[type="text"]:not([disabled])');
  await expect(editable.last()).toBeVisible({ timeout: 10_000 });
  const count = await editable.count();
  if (count < 2) {
    throw new Error(`expected at least 2 editable text inputs, found ${count}`);
  }
  await editable.nth(count - 2).fill(finalName);
  await editable.nth(count - 1).fill(description);
  
  await Promise.all([
    page.waitForURL(/\/apis\/api_[^/]+(\?|$)/, { timeout: 15_000 }),
    page.getByRole('button', { name: 'Create', exact: true }).click(),
  ]);

  const match = page.url().match(/\/apis\/(api_[^/?#]+)/);
  if (!match) throw new Error(`could not extract apiId from URL: ${page.url()}`);
  return match[1].replace(/\/.*$/, '');
}

// Unique suffix so concurrent runs / leftover entities don't collide.
export function uniqueName(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export async function createApiViaApi(page, { name, description = 'rules test api' } = {}) {
  const finalName = name || uniqueName('rules-api');
  const template = await getJson(page, `${PROXY_ANY}/apis/_template`);
  const body = { ...template, name: finalName, description };
  const created = await postJson(page, `${PROXY_ANY}/apis`, body);
  if (!created || !created.id) {
    throw new Error(`createApiViaApi failed: ${JSON.stringify(created)}`);
  }
  return created.id;
}

export async function deleteApiViaUI(page) {
  await page.getByRole('link', { name: ' Informations' }).click();
  await page.locator('div').filter({ hasText: /^Danger zone$/ }).nth(1).click();
  await page.getByRole('button', { name: 'Delete this API' }).click();
  await page.getByRole('button', { name: 'Ok' }).click();
}

export async function deleteApiViaApi(page, apiId) {
  if (!apiId) return;
  // Order matters: drop the draft first so a stale draft can't resurrect data.
  await page.request.delete(`${PROXY_ANY}/drafts/${apiId}`).catch(() => { });
  await page.request.delete(`${PROXY_ANY}/apis/${apiId}`).catch(() => { });
}

// Drafts are listed by kind on the proxy.otoroshi.io entity.
const PROXY_PROXY = '/bo/api/proxy/apis/proxy.otoroshi.io/v1';

export async function cleanupApi(page, apiId) {
  if (!apiId) return;

  // 1) Prod subscriptions referencing this API.
  const subsRes = await page.request.get(`${PROXY_ANY}/apisubscriptions`).catch(() => null);
  if (subsRes && subsRes.status() < 400) {
    const subs = await subsRes.json().catch(() => []);
    for (const s of Array.isArray(subs) ? subs : []) {
      if (s?.api_ref === apiId) {
        await page.request.delete(`${PROXY_ANY}/apisubscriptions/${s.id}`).catch(() => { });
      }
    }
  }

  // 2) Draft subscriptions referencing this API (deleted as draft entities).
  const draftSubsRes = await page.request.get(`${PROXY_PROXY}/drafts/api-subscription`).catch(() => null);
  if (draftSubsRes && draftSubsRes.status() < 400) {
    const draftSubs = await draftSubsRes.json().catch(() => []);
    for (const d of Array.isArray(draftSubs) ? draftSubs : []) {
      const apiRef = d?.content?.api_ref ?? d?.api_ref;
      if (apiRef === apiId) {
        await page.request.delete(`${PROXY_ANY}/drafts/${d.id}`).catch(() => { });
      }
    }
  }

  // 3) The API and its draft.
  await deleteApiViaApi(page, apiId);
}

export async function getProd(page, apiId) {
  return getJson(page, `${PROXY_ANY}/apis/${apiId}`);
}

export async function getDraft(page, apiId) {
  return getJson(page, `${PROXY_ANY}/drafts/${apiId}`);
}

export async function getDraftRaw(page, apiId) {
  // Returns the raw HTTP response so callers can assert status (e.g. 404).
  return page.request.get(`${PROXY_ANY}/drafts/${apiId}`);
}

export async function putProd(page, apiId, body) {
  return page.request.put(`${PROXY_ANY}/apis/${apiId}`, { data: body });
}


export async function putProdWithRetry(page, apiId, mutate, { attempts = 5, delayMs = 500 } = {}) {
  let last;
  for (let i = 0; i < attempts; i++) {
    const current = await getProd(page, apiId);
    last = await putProd(page, apiId, mutate({ ...current }));
    if (last.status() < 400) return last;
    if (i < attempts - 1) await page.waitForTimeout(delayMs);
  }
  return last;
}

export async function putDraft(page, apiId, body) {
  return page.request.put(`${PROXY_ANY}/drafts/${apiId}`, { data: body });
}

export async function postDeployment(page, apiId, body) {
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


export async function createPublishedApi(page, opts = {}) {
  const { setup, ...createOpts } = opts;
  const apiId = await createApiViaApi(page, createOpts);
  const base = await getProd(page, apiId);
  const api = setup ? { ...base, ...setup(base, apiId) } : base;

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


export async function wipeLeftovers(page, namePrefixes = []) {
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

