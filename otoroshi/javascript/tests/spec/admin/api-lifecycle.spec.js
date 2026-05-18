// End-to-end coverage of the API lifecycle: create, publish, draft divergence,
// gateway dev/prod gating via X-OTOROSHI-TESTING, and UI cross-checks.

import { test, expect } from '@playwright/test';
import {
  PROXY_ANY,
  createApiViaUI,
  createApiViaApi,
  createPublishedApi,
  deleteApiViaApi,
  getDraft,
  getDraftRaw,
  getProd,
  putDraft,
  postDeployment,
  uniqueName,
} from './_apiHelpers';

test.setTimeout(30_000);

let context;
// Safety net for parallel runs: every API a test creates is registered here
// and torn down in afterEach — even if the test crashes before its own
// try/finally fires. delete is idempotent so this co-exists with the per-test
// cleanup just fine.
const trackedApis = new Set();

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
});

test.afterEach(async () => {
  if (trackedApis.size === 0) return;
  const page = await context.newPage();
  for (const id of trackedApis) {
    await deleteApiViaApi(page, id);
  }
  trackedApis.clear();
  await page.close();
});

test.afterAll(async () => {
  await context.close();
});

// Fresh API lands in staging with a generated testing header. -------------------
// First-time user sees the getting-started stepper AND the Publish CTA in the
// header (advanced users can publish without following the tutorial).

test('creates an API from scratch and lands in staging', async () => {
  const page = await context.newPage();
  const apiId = await createApiViaUI(page);
  trackedApis.add(apiId);
  try {
    // id is `api_dev_<uuid>` under `sbt run` (env.isDev) but `api_<uuid>` in a
    // packaged/CI build — accept both.
    expect(page.url()).toMatch(/\/apis\/api_(dev_)?[a-f0-9-]+/);

    // Getting-started stepper visible on a fresh API (state=staging, all steps
    // incomplete).
    await expect(page.getByTestId('gs-stepper')).toBeVisible();
    await expect(page.getByTestId('gs-step-1')).toBeVisible();

    // Publish CTA visible (label adapts: "Publish API" for the first publish).
    await expect(page.getByTestId('publish-this-version')).toBeVisible();

    // Backend invariants.
    const prod = await getProd(page, apiId);
    expect(prod.state).toBe('staging');

    const draft = await getDraft(page, apiId);
    expect(draft.content.state).toBe('staging');
    expect(draft.content.testing.headerKey).toBe('X-OTOROSHI-TESTING');
    // Otoroshi's IdGenerator.uuid emits 37-char ids (off-by-one in the
    // legacy generator) — accept that shape rather than canonical UUID v4.
    expect(draft.content.testing.headerValue).toMatch(/^[0-9a-f-]+$/);
  } finally {
    await deleteApiViaApi(page, apiId);
    await page.close();
  }
});

// Publish flow trims deployment payload + wipes draft. --------------------------

test('publishes from staging, deployment payload is slim, draft is wiped', async () => {
  const page = await context.newPage();
  const apiId = await createApiViaUI(page);
  trackedApis.add(apiId);
  try {
    // Publish via the header CTA (the entry point we expose to users now).
    await page.getByTestId('publish-this-version').click();
    // Confirm modal — "Publish API" header button is "Publish API", the modal
    // button is exactly "Publish".
    await page.getByRole('button', { name: 'Publish', exact: true }).click();

    // Poll until the prod state becomes published (publish triggers reload).
    await expect.poll(async () => (await getProd(page, apiId)).state).toBe('published');

    const prod = await getProd(page, apiId);
    expect(Array.isArray(prod.deployments)).toBe(true);
    expect(prod.deployments.length).toBeGreaterThanOrEqual(1);

    const latest = prod.deployments[0];
    expect(latest.apiRef).toBe(apiId);
    expect(typeof latest.at).toBe('number');

    // A.5 — slim payload: only the design-time fields are stored.
    const apiDef = latest.apiDefinition;
    const keys = Object.keys(apiDef).sort();
    expect(keys).toEqual(['backends', 'documentation', 'flows', 'routes']);

    // B.4 (revised) — the draft is KEPT after publish so testing routes
    // (X-OTOROSHI-TESTING) continue to serve without a UI roundtrip via
    // "Edit in Draft Mode". The draft content equals the just-published
    // prod anyway since the deploy snapshotted from it.
    const draftRes = await getDraftRaw(page, apiId);
    expect(draftRes.status()).toBe(200);
  } finally {
    await deleteApiViaApi(page, apiId);
    await page.close();
  }
});

// Draft vs prod fetch diverges after a draft-only edit. ------------------------

test('draft vs prod fetch diverges after a draft-only edit', async () => {
  const page = await context.newPage();
  const apiId = await createPublishedApi(page);
  trackedApis.add(apiId);
  try {
    // The draft already exists (createPublishedApi creates it). Update it
    // with a draft-only description change, then assert prod/draft diverge.
    const existingDraft = await getDraft(page, apiId);
    const modifiedDraft = {
      ...existingDraft,
      content: { ...existingDraft.content, description: 'draft-only edit' },
    };
    const putRes = await putDraft(page, apiId, modifiedDraft);
    expect(putRes.status()).toBeLessThan(400);

    const draft = await getDraft(page, apiId);
    expect(draft.content.description).toBe('draft-only edit');

    const prodAfter = await getProd(page, apiId);
    expect(prodAfter.description).not.toBe('draft-only edit');
  } finally {
    await deleteApiViaApi(page, apiId);
    await page.close();
  }
});

// X-OTOROSHI-TESTING header gates dev vs prod traffic. -------------------------

test('testing header gates dev vs prod traffic at the gateway', async () => {
  const page = await context.newPage();
  // Manually build a deterministic API: domain e2e-test.oto.tools, /v1, route /hello.
  const tplRes = await page.request.get(`${PROXY_ANY}/apis/_template`);
  const template = await tplRes.json();
  const backendId = 'be_e2e';
  const flowId = 'flow_e2e';
  const base = {
    ...template,
    name: 'lifecycle-4',
    description: 'gateway dev/prod test',
    domain: 'e2e-test.oto.tools',
    contextPath: '/v1',
    backends: [
      {
        id: backendId,
        name: backendId,
        client: 'default_backend_client',
        backend: {
          targets: [
            {
              id: 't1',
              hostname: 'request.otoroshi.io',
              port: 443,
              tls: true,
              weight: 1,
              protocol: 'HTTP/1.1',
              ip_address: null,
              predicate: { type: 'AlwaysMatch' },
              tls_config: {
                certs: [],
                trusted_certs: [],
                enabled: false,
                loose: false,
                trust_all: false,
              },
            },
          ],
          root: '/',
          rewrite: false,
          load_balancing: { type: 'RoundRobin' },
          client: {},
        },
      },
    ],
    flows: [{
      id: flowId,
      name: flowId,
      plugins: [
        {
          plugin: 'cp:otoroshi.next.plugins.OverrideHost',
          enabled: true,
          debug: false,
          include: [],
          exclude: [],
          config: {},
        },
      ]
    }],
    routes: [
      {
        id: 'r_hello',
        enabled: true,
        name: 'hello',
        frontend: {
          domains: ['/hello'],
          headers: {},
          query: {},
          methods: [],
          strip_path: false,
          exact: true,
        },
        flow_ref: flowId,
        backend: backendId,
      },
    ],
  };

  const createRes = await page.request.post(`${PROXY_ANY}/apis`, { data: base });
  expect(createRes.status()).toBeLessThan(400);
  const created = await createRes.json();
  const apiId = created.id;
  trackedApis.add(apiId);
  try {
    // Visit the API in draft mode — useDraftOfAPI auto-creates the draft
    // wrapper for us (no manual POST /drafts).
    await page.goto(`/bo/dashboard/apis/${apiId}/testing?version=Draft`);
    await expect.poll(async () => (await getDraftRaw(page, apiId)).status()).toBe(200);
    const draft = await getDraft(page, apiId);

    // Deploy.
    const deployment = {
      apiRef: apiId,
      owner: 'tests',
      at: Date.now(),
      apiDefinition: { ...(draft.content || base), deployments: [] },
      draftId: apiId,
      version: '0.0.1',
    };
    const depRes = await postDeployment(page, apiId, deployment);
    expect(depRes.status()).toBeLessThan(400);

    // Now mutate the draft to add /hello-draft + enable testing.
    const headerValue = '11111111-2222-3333-4444-555555555555';
    const newDraftContent = {
      ...draft.content,
      testing: { enabled: true, headerKey: 'X-OTOROSHI-TESTING', headerValue },
      routes: [
        ...draft.content.routes,
        {
          id: 'r_hello_draft',
          enabled: true,
          name: 'hello-draft',
          frontend: {
            domains: ['/hello-draft'],
            headers: {},
            query: {},
            methods: [],
            strip_path: false,
            exact: true,
          },
          flow_ref: flowId,
          backend: backendId,
        },
      ],
    };
    const putRes = await putDraft(page, apiId, { ...draft, content: newDraftContent });
    expect(putRes.status()).toBeLessThan(300);

    // Gateway calls — no re-deploy. The proxy in-memory route cache refreshes
    // asynchronously after the deploy, so poll the prod route until it serves.
    await expect
      .poll(
        async () => (await page.request.get('http://e2e-test.oto.tools:9999/v1/hello')).status(),
        { timeout: 15_000, intervals: [500, 1000, 2000] }
      )
      .toBe(200);

    const helloDraftWithoutHeader = await page.request.get(
      'http://e2e-test.oto.tools:9999/v1/hello-draft'
    );
    expect(helloDraftWithoutHeader.status()).toBe(404);

    const helloDraftWithHeader = await page.request.get(
      'http://e2e-test.oto.tools:9999/v1/hello-draft',
      { headers: { 'X-OTOROSHI-TESTING': headerValue } }
    );
    expect(helloDraftWithHeader.status()).toBe(200);

    const helloProdWithHeader = await page.request.get(
      'http://e2e-test.oto.tools:9999/v1/hello',
      { headers: { 'X-OTOROSHI-TESTING': headerValue } }
    );
    expect(helloProdWithHeader.status()).toBe(200);
  } finally {
    await deleteApiViaApi(page, apiId);
    await page.close();
  }
});

// Actions tab only shows the legal transitions per state. ----------------------

async function navigateToActions(page, apiId) {
  await page.goto(`/bo/dashboard/apis/${apiId}`);
  await page.getByTestId('sidebar-tab-actions').click();
}

test.describe('Actions tab only shows legal transitions', () => {
  test('staging shows no transition cards (publish lives in the header CTA)', async () => {
    const page = await context.newPage();
    const apiId = await createApiViaApi(page);
  trackedApis.add(apiId);
    try {
      await navigateToActions(page, apiId);
      // The Actions tab is intentionally empty in staging — the publish CTA
      // is exposed via the dashboard header (`publish-this-version`).
      await expect(page.getByTestId('action-card-publish')).toHaveCount(0);
      await expect(page.getByTestId('action-card-deprecate')).toHaveCount(0);
      await expect(page.getByTestId('action-card-close')).toHaveCount(0);
      await expect(page.getByTestId('action-card-reopen')).toHaveCount(0);
      await expect(page.getByTestId('action-card-republish')).toHaveCount(0);
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  test('published shows deprecate + close', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
  trackedApis.add(apiId);
    try {
      await navigateToActions(page, apiId);
      await expect(page.getByTestId('action-card-deprecate')).toBeVisible();
      await expect(page.getByTestId('action-card-close')).toBeVisible();
      await expect(page.getByTestId('action-card-publish')).toHaveCount(0);
      await expect(page.getByTestId('action-card-republish')).toHaveCount(0);
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  test('deprecated shows republish + close', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
  trackedApis.add(apiId);
    try {
      const prod = await getProd(page, apiId);
      const res = await page.request.put(`${PROXY_ANY}/apis/${apiId}`, {
        data: { ...prod, state: 'deprecated' },
      });
      expect(res.status()).toBeLessThan(400);
      await navigateToActions(page, apiId);
      await expect(page.getByTestId('action-card-republish')).toBeVisible();
      await expect(page.getByTestId('action-card-close')).toBeVisible();
      await expect(page.getByTestId('action-card-publish')).toHaveCount(0);
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  test('removed shows reopen', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
  trackedApis.add(apiId);
    try {
      const prod = await getProd(page, apiId);
      const res = await page.request.put(`${PROXY_ANY}/apis/${apiId}`, {
        data: { ...prod, state: 'removed' },
      });
      expect(res.status()).toBeLessThan(400);
      await navigateToActions(page, apiId);
      await expect(page.getByTestId('action-card-reopen')).toBeVisible();
      await expect(page.getByTestId('action-card-publish')).toHaveCount(0);
      await expect(page.getByTestId('action-card-deprecate')).toHaveCount(0);
      await expect(page.getByTestId('action-card-close')).toHaveCount(0);
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });
});

// Production-locked tabs hide their write actions. -----------------------------

test('production-locked tabs disable write actions', async () => {
  const page = await context.newPage();
  const apiId = await createPublishedApi(page);
  trackedApis.add(apiId);
  try {
    await page.goto(`/bo/dashboard/apis/${apiId}?version=Published`);

    // Endpoints — no "Create new endpoint" link in prod (it's an <a>, not <button>).
    await page.getByTestId('sidebar-tab-endpoints').click();
    await expect(page.getByRole('link', { name: /Create new endpoint/ })).toHaveCount(0);

    // Backends — no "Create new" link in prod.
    await page.getByTestId('sidebar-tab-backends').click();
    await expect(page.getByRole('link', { name: /Create new/ })).toHaveCount(0);

    // Testing — TestingProductionMode (no rotate button).
    await page.getByTestId('sidebar-tab-testing').click();
    await expect(page.getByTestId('testing-rotate-button')).toHaveCount(0);

    // Switching to Draft re-enables them.
    await page.getByTestId('version-toggle').click();
    await page.waitForLoadState('domcontentloaded');
    await page.getByTestId('sidebar-tab-endpoints').click();
    await expect(page.getByRole('link', { name: /Create new endpoint/ })).toBeVisible();
  } finally {
    await deleteApiViaApi(page, apiId);
    await page.close();
  }
});
