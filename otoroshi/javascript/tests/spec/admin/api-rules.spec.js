// API-level enforcement of API lifecycle business rules.
// Validates Api.writeValidator (state transitions, production read-only)
// and createNewVersion (invalid deploy transitions).

const { test, expect } = require('@playwright/test');
const {
  PROXY_ANY,
  createApiViaApi,
  createPublishedApi,
  deleteApiViaApi,
  getProd,
  putProd,
  postDeployment,
  wipeLeftovers,
} = require('./_apiHelpers');

test.setTimeout(30_000);

// API names this suite creates start with one of these prefixes — used for
// pre/post sweeps to clean leftovers from prior failed runs.
const LEFTOVER_PREFIXES = ['rules-api', 'should-fail'];

let context;

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' });
  const page = await context.newPage();
  await wipeLeftovers(page, LEFTOVER_PREFIXES);
  await page.close();
});

test.afterAll(async () => {
  const page = await context.newPage();
  await wipeLeftovers(page, LEFTOVER_PREFIXES);
  await page.close();
  await context.close();
});

// Bring a freshly-created (staging) API to a target legal state by walking the matrix.
async function bringTo(page, apiId, target) {
  if (target === 'staging') return;
  // staging -> published requires a deploy
  await createPublishedHelperDeploy(page, apiId);
  if (target === 'published') return;
  // published -> deprecated / removed via plain PUT
  const prod = await getProd(page, apiId);
  if (target === 'deprecated') {
    const res = await putProd(page, apiId, { ...prod, state: 'deprecated' });
    expect(res.status()).toBeLessThan(400);
    return;
  }
  if (target === 'removed') {
    const res = await putProd(page, apiId, { ...prod, state: 'removed' });
    expect(res.status()).toBeLessThan(400);
    return;
  }
  throw new Error(`unknown target state: ${target}`);
}

async function createPublishedHelperDeploy(page, apiId) {
  // POST /apis doesn't auto-create a draft — but the deployment endpoint
  // requires one. Ensure it exists (idempotent).
  const existing = await page.request.get(`${PROXY_ANY}/drafts/${apiId}`);
  let draftContent;
  if (existing.status() === 200) {
    draftContent = (await existing.json()).content;
  } else {
    const api = await getProd(page, apiId);
    const draftTpl = await page.request.get(`${PROXY_ANY}/drafts/_template`);
    const draftWrapper = {
      ...(await draftTpl.json()),
      id: apiId,
      kind: apiId.split('_')[0],
      name: api.name,
      content: api,
    };
    const created = await page.request.post(`${PROXY_ANY}/drafts`, { data: draftWrapper });
    expect(created.status()).toBeLessThan(400);
    draftContent = api;
  }
  const deployment = {
    apiRef: apiId,
    owner: 'tests',
    at: Date.now(),
    apiDefinition: { ...draftContent, deployments: [] },
    draftId: apiId,
    version: '0.0.1',
  };
  const res = await postDeployment(page, apiId, deployment);
  expect(res.status()).toBeLessThan(400);
}

// State transitions allowed/denied -----------------------------

test.describe('state transitions', () => {
  // Allowed transitions
  for (const { from, to } of [
    { from: 'published', to: 'deprecated' },
    { from: 'published', to: 'removed' },
    { from: 'deprecated', to: 'published' },
    { from: 'deprecated', to: 'removed' },
    { from: 'removed', to: 'staging' },
  ]) {
    test(`allows ${from} -> ${to}`, async () => {
      const page = await context.newPage();
      const apiId = await createApiViaApi(page);
      try {
        await bringTo(page, apiId, from);
        const prod = await getProd(page, apiId);
        const res = await putProd(page, apiId, { ...prod, state: to });
        expect(res.status()).toBeLessThan(400);
        const after = await getProd(page, apiId);
        expect(after.state).toBe(to);
      } finally {
        await deleteApiViaApi(page, apiId);
        await page.close();
      }
    });
  }

  test('allows staging -> published via deployment endpoint', async () => {
    const page = await context.newPage();
    const apiId = await createApiViaApi(page);
    try {
      await createPublishedHelperDeploy(page, apiId);
      const after = await getProd(page, apiId);
      expect(after.state).toBe('published');
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  // Denied transitions via plain PUT
  for (const { from, to } of [
    { from: 'staging', to: 'published' }, // must use deploy
    { from: 'staging', to: 'deprecated' },
    { from: 'staging', to: 'removed' },
    { from: 'published', to: 'staging' },
    { from: 'deprecated', to: 'staging' },
    { from: 'removed', to: 'published' },
    { from: 'removed', to: 'deprecated' },
  ]) {
    test(`denies ${from} -> ${to} via PUT`, async () => {
      const page = await context.newPage();
      const apiId = await createApiViaApi(page);
      try {
        await bringTo(page, apiId, from);
        const prod = await getProd(page, apiId);
        const res = await putProd(page, apiId, { ...prod, state: to });
        expect(res.status()).toBe(400);
        const body = await res.json();
        expect(body.error).toBe('invalid_state_transition');
        expect(body.from).toBe(from);
        expect(body.to).toBe(to);
      } finally {
        await deleteApiViaApi(page, apiId);
        await page.close();
      }
    });
  }
});

// Production read-only enforcement -----------------------------

test.describe('production read-only', () => {
  test('allows edits on unprotected fields', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
    try {
      const prod = await getProd(page, apiId);
      const updates = [
        { ...prod, name: 'renamed' },
        { ...prod, description: 'new desc' },
        { ...prod, version: '0.0.2' },
        { ...prod, metadata: { ...(prod.metadata || {}), k: 'v' } },
        { ...prod, tags: ['t1'] },
        { ...prod, enabled: !prod.enabled },
        { ...prod, domain: 'rules.oto.tools' },
        { ...prod, contextPath: '/v2' },
      ];
      for (const body of updates) {
        const res = await putProd(page, apiId, body);
        expect(res.status()).toBeLessThan(400);
      }
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  test('allows adding a plan in production', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
    try {
      const prod = await getProd(page, apiId);
      const plan = {
        id: 'plan_test',
        name: 'Free plan',
        type: 'free',
        accessModeConfigurationType: 'keyless',
        accessModeConfiguration: null,
        consumerKind: 'keyless',
        visibility: 'public',
        documentation: null,
        autoValidation: true,
        subscriptionProcess: [],
        integrationProcess: 'apikey',
        status: 'published',
      };
      const res = await putProd(page, apiId, { ...prod, plans: [...(prod.plans || []), plan] });
      expect(res.status()).toBeLessThan(400);
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  // For each locked field we need a mutation that survives JSON round-trip
  // (malformed entries get silently dropped at deserialization → no diff →
  // false negative). Strategy:
  //  - routes: template ships zero, so inject a fully-shaped route
  //  - backends / flows: template ships one entry each, so mutate its `name`
  const lockedFieldMutations = {
    routes: (prod) => [
      ...prod.routes,
      {
        id: 'sentinel_route',
        enabled: true,
        name: 'sentinel',
        frontend: {
          // ApiRoute.frontend.domains entries are path-only — api.domain +
          // api.contextPath get prefixed automatically when the NgRoute is
          // built (see Api.scala routeToNgRoute).
          domains: ['/sentinel'],
          headers: {},
          query: {},
          methods: [],
          strip_path: false,
          exact: true,
        },
        flow_ref: prod.flows[0]?.id || 'default_plugin_chain',
        backend: prod.backends[0]?.id || 'default_backend',
      },
    ],
    backends: (prod) =>
      prod.backends.map((b, i) => (i === 0 ? { ...b, name: 'mutated_' + b.name } : b)),
    flows: (prod) =>
      prod.flows.map((f, i) => (i === 0 ? { ...f, name: 'mutated_' + f.name } : f)),
  };

  for (const field of Object.keys(lockedFieldMutations)) {
    test(`denies ${field} edit in production`, async () => {
      const page = await context.newPage();
      const apiId = await createPublishedApi(page);
      try {
        const prod = await getProd(page, apiId);
        const body = { ...prod, [field]: lockedFieldMutations[field](prod) };
        const res = await putProd(page, apiId, body);
        expect(res.status()).toBe(400);
        const errBody = await res.json();
        expect(errBody.error).toBe('production_readonly');
        expect(errBody.fields).toContain(field);
      } finally {
        await deleteApiViaApi(page, apiId);
        await page.close();
      }
    });
  }

  test('denies testing.headerValue edit in production', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
    try {
      const prod = await getProd(page, apiId);
      const body = {
        ...prod,
        testing: { ...(prod.testing || {}), headerValue: 'tampered-value-1234' },
      };
      const res = await putProd(page, apiId, body);
      expect(res.status()).toBe(400);
      const errBody = await res.json();
      expect(errBody.error).toBe('production_readonly');
      expect(errBody.fields).toContain('testing');
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });

  test('denies documentation edit in production', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
    try {
      const prod = await getProd(page, apiId);
      // Use values that cannot collide with ApiDocumentation defaults
      // (enabled=true, empty metadata/tags) — otherwise the JSON round-trip
      // produces an identical case class and no diff fires.
      const body = {
        ...prod,
        documentation: {
          ...(prod.documentation || {}),
          enabled: false,
          tags: ['mutated_doc'],
          metadata: { mutated: 'yes' },
        },
      };
      const res = await putProd(page, apiId, body);
      expect(res.status()).toBe(400);
      const errBody = await res.json();
      expect(errBody.error).toBe('production_readonly');
      expect(errBody.fields).toContain('documentation');
    } finally {
      await deleteApiViaApi(page, apiId);
      await page.close();
    }
  });
});

// Create cannot start outside staging --------------------------

test('create with non-staging state is rejected', async () => {
  const page = await context.newPage();
  let leakedId;
  try {
    const tplRes = await page.request.get(`${PROXY_ANY}/apis/_template`);
    const template = await tplRes.json();
    const body = { ...template, name: 'should-fail', state: 'published' };
    const res = await page.request.post(`${PROXY_ANY}/apis`, { data: body });
    if (res.status() < 400) {
      // Defensive: backend let it through (shouldn't happen) — capture id for cleanup.
      const created = await res.json();
      leakedId = created.id;
    }
    expect(res.status()).toBe(400);
    const errBody = await res.json();
    expect(errBody.error).toBe('cannot_create_in_state');
    expect(errBody.state).toBe('published');
  } finally {
    if (leakedId) await deleteApiViaApi(page, leakedId);
    await page.close();
  }
});
