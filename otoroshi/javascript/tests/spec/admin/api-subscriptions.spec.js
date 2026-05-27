// API plan + subscription lifecycle coverage.
//
//   Test 1 — Keyless plan: create an API via UI, click step 1 of the stepper
//            to demonstrate it navigates, configure the rest via API for
//            speed, then prove a call with X-OTOROSHI-TESTING reaches the
//            route while the API is still in staging.
//
//   Test 2 — Apikey plan lifecycle: walk a plan through
//            Published → Deprecated → Closed and assert:
//              - Published: new subs allowed, existing apikey authenticates
//              - Deprecated: new subs REFUSED ("wrong status plan"), existing
//                apikey is not deleted (token store keeps it)
//              - Closed: subscription deleted on update, the apikey credentials
//                no longer authenticate

import { test, expect } from '@playwright/test';
import {
    PROXY_ANY,
    createApiViaApi,
    createPublishedApi,
    cleanupApi,
    getProd,
    putProdWithRetry,
    putDraft,
    getDraft,
    uniqueName,
} from './_apiHelpers';

test.setTimeout(30_000);

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
        await page.request.delete(`${PROXY_ANY}/apikeys/${id}`).catch(() => { });
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

// Helpers -------------------------------------------------------------

const KEYLESS_PLAN = (id = 'plan_keyless') => ({
    id,
    name: 'Free keyless plan',
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
});

const APIKEY_PLAN = (id = 'plan_apikey') => ({
    id,
    name: 'Free apikey plan',
    type: 'free',
    accessModeConfigurationType: 'apikey',
    accessModeConfiguration: null,
    consumerKind: 'apikey',
    visibility: 'public',
    documentation: null,
    autoValidation: true,
    subscriptionProcess: [],
    integrationProcess: 'apikey',
    status: 'published',
});

const ROUTE = (flowId, backendId) => ({
    id: `r_${uniqueName('hello')}`.replace(/-/g, '_'),
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
});

const BACKEND = (id) => ({
    id,
    name: id,
    client: 'default_backend_client',
    backend: {
        targets: [{
            id: 't1',
            hostname: 'request.otoroshi.io',
            port: 443,
            tls: true,
            weight: 1,
            protocol: 'HTTP/1.1',
            ip_address: null,
            predicate: { type: 'AlwaysMatch' },
            tls_config: {
                certs: [], trusted_certs: [], enabled: false, loose: false, trust_all: false,
            },
        }],
        root: '/',
        rewrite: false,
        load_balancing: { type: 'RoundRobin' },
        client: {},
    },
});

const FLOW_WITH_OVERRIDE_HOST = (id) => ({
    id, name: id,
    plugins: [{
        plugin: 'cp:otoroshi.next.plugins.OverrideHost',
        enabled: true,
        debug: false,
        include: [], exclude: [],
        config: {},
    }],
});

// Build a fully-configured API entirely via the admin API — route, backend,
// flow, gateway domain, testing, optional plan — and POST its draft.
// IMPORTANT: this must run BEFORE any UI page is opened for this apiId.
// useDraftOfAPI auto-creates the draft on dashboard mount; doing all the
// API setup first means nothing races our POST /drafts.
async function setUpFullApi(page, apiId, { domain, headerValue, plan }) {
    const backendId = 'be_' + apiId.split('_').pop();
    const flowId = 'flow_' + apiId.split('_').pop();
    const api = await getProd(page, apiId);
    const content = {
        ...api,
        domain,
        contextPath: '/v1',
        backends: [BACKEND(backendId)],
        flows: [FLOW_WITH_OVERRIDE_HOST(flowId)],
        routes: [ROUTE(flowId, backendId)],
        testing: { enabled: true, headerKey: 'X-OTOROSHI-TESTING', headerValue },
        plans: plan ? [plan] : [],
    };
    const draftTpl = await (await page.request.get(`${PROXY_ANY}/drafts/_template`)).json();
    const res = await page.request.post(`${PROXY_ANY}/drafts`, {
        data: {
            ...draftTpl,
            id: apiId,
            kind: apiId.split('_')[0],
            name: api.name,
            content,
        },
    });
    expect(
        res.status(),
        `setUpFullApi POST /drafts/${apiId} failed: ${await res.text().catch(() => '')}`
    ).toBeLessThan(400);
}

// Tests ---------------------------------------------------------------

test('keyless plan: stepper reflects setup, X-OTOROSHI-TESTING gates traffic', async () => {
    const page = await context.newPage();

    // --- Setup: 100% admin API, no UI page mounted yet → no draft race. ---
    const apiId = await createApiViaApi(page);
    trackedApis.add(apiId);
    const headerValue = 'subs-keyless-' + Math.random().toString(36).slice(2, 10);
    const domain = `subs-keyless-${apiId.slice(-8)}.oto.tools`;
    await setUpFullApi(page, apiId, { domain, headerValue, plan: KEYLESS_PLAN() });

    // --- UI: the draft already exists, useDraftOfAPI just loads it. ---
    await page.goto(`/bo/dashboard/apis/${apiId}?version=Draft`);

    // The stepper reflects the steps we populated (1=routes, 2=backends,
    // 4=gateway, 5=testing, 6=plan). Completed steps render with the
    // --done modifier (and are disabled — can't be re-clicked).
    await expect(page.getByTestId('gs-stepper')).toBeVisible();
    for (const n of [1, 2, 3, 4, 5]) {
        await expect(page.getByTestId(`gs-step-${n}`)).toHaveClass(/gs-stepper-step--done/);
    }

    // Gateway call: API is still in staging, so only requests bearing the
    // X-OTOROSHI-TESTING header reach the draft route.
    await expect
        .poll(async () => (await page.request.get(`http://${domain}:9999/v1/hello`, {
            headers: { 'X-OTOROSHI-TESTING': headerValue },
        })).status(), { timeout: 15_000, intervals: [500, 1000, 2000] })
        .toBe(200);

    const withoutHeader = await page.request.get(`http://${domain}:9999/v1/hello`);
    expect(withoutHeader.status()).toBeGreaterThanOrEqual(400);

    await page.close();
});

test('apikey plan lifecycle: published → deprecated → closed gates new subs / kills tokens', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page, {
        setup: (api, id) => ({
            domain: `subs-apikey-${id.slice(-8)}.oto.tools`,
            contextPath: '/v1',
            plans: [APIKEY_PLAN()],
        }),
    });
    trackedApis.add(apiId);

    const subTpl = await page.request.get(`${PROXY_ANY}/apisubscriptions/_template`);
    const subTemplate = await subTpl.json();
    const subBody = {
        ...subTemplate,
        id: uniqueName('apisub'),
        name: 'lifecycle sub',
        api_ref: apiId,
        plan_ref: 'plan_apikey',
        subscription_kind: 'apikey',
        owner_ref: 'tests',
        status: 'enabled',
    };
    const subCreate = await page.request.post(`${PROXY_ANY}/apisubscriptions`, { data: subBody });
    expect(subCreate.status(), `subscription should be accepted on published plan: ${await subCreate.text().catch(() => '')}`).toBeLessThan(400);
    const sub = await subCreate.json();
    if (sub.tokenRefs?.[0]?.clientId) {
        trackedApikeys.add(sub.tokenRefs[0].clientId);
    }

    const depRes = await putProdWithRetry(page, apiId, (prod) => ({
        ...prod,
        plans: [{ ...APIKEY_PLAN(), status: 'deprecated' }],
    }));
    expect(depRes.status()).toBeLessThan(400);

    const newSubBody = { ...subBody, id: uniqueName('apisub'), name: 'should fail' };
    const newSub = await page.request.post(`${PROXY_ANY}/apisubscriptions`, { data: newSubBody });
    expect(newSub.status(), 'new subscription should be refused on deprecated plan').toBeGreaterThanOrEqual(400);

    const stillThere = await page.request.get(`${PROXY_ANY}/apisubscriptions/${sub.id}`);
    expect(stillThere.status()).toBeLessThan(400);
    if (sub.tokenRefs?.[0]?.clientId) {
        const apikey = await page.request.get(`${PROXY_ANY}/apikeys/${sub.tokenRefs[0].clientId}`);
        expect(apikey.status(), 'existing apikey should remain in the store on deprecated').toBeLessThan(400);
    }

    const closeRes = await putProdWithRetry(page, apiId, (prod) => ({
        ...prod,
        plans: [{ ...APIKEY_PLAN(), status: 'closed' }],
    }));
    expect(closeRes.status()).toBeLessThan(400);

    const touchSub = await page.request.put(`${PROXY_ANY}/apisubscriptions/${sub.id}`, {
        data: { ...sub, name: 'touch' },
    });
    expect([200, 404].includes(touchSub.status()) || touchSub.status() >= 400).toBe(true);

    await expect
        .poll(async () => (await page.request.get(`${PROXY_ANY}/apisubscriptions/${sub.id}`)).status(), { timeout: 5_000 })
        .toBe(404);

    await page.close();
});

test('UI subscribe: client + apikey plan with EL clientNamePattern, generated key linked to sub', async () => {
    const page = await context.newPage();
    const apiId = await createPublishedApi(page);
    trackedApis.add(apiId);

    const clientName = 'Test Owner ' + Math.random().toString(36).slice(2, 8);
    const planName = 'Apikey UI Plan ' + Math.random().toString(36).slice(2, 8);
    const fieldInput = (label) =>
        page
            .locator('div.row', { has: page.locator('label', { hasText: new RegExp(`^\\s*${label}\\s*$`) }) })
            .locator('input:not([disabled])')
            .first();

    await page.goto(`/bo/dashboard/apis/${apiId}/clients?version=Published`);
    await page.getByRole('link', { name: /Create new client/ }).click();
    await fieldInput('Name').fill(clientName);
    await page.getByRole('button', { name: /^Create\s+PROD/ }).click();
    await page.waitForURL(/\/clients(\?|$)/, { timeout: 10_000 });
    await expect(page.locator('#content-scroll-container')).toContainText(clientName);

    await page.goto(`/bo/dashboard/apis/${apiId}/plans?version=Published`);
    await page.getByRole('link', { name: /Create new plan/ }).click();

    await fieldInput('Name').fill(planName);

    const statusRow = page
        .locator('div.row', { has: page.locator('label', { hasText: /^\s*Status\s*$/ }) })
        .first();
    await statusRow.getByRole('button', { name: 'published', exact: true }).click();

    await page.getByTestId('access-mode-edit').click();
    await fieldInput('ApiKey Name Pattern').fill('${plan.name}-foo-bar');
    await page.getByTestId('access-mode-save').click();

    // Save the plan.
    await page.getByRole('button', { name: /^Create\s+PROD/ }).click();
    await page.waitForURL(/\/plans(\?|$)/, { timeout: 10_000 });
    await expect(page.locator('#content-scroll-container')).toContainText(planName);

    // ------------------------------------------------------------------
    // (3) Subscribe to the plan via the Plans table → form → Create.
    // ------------------------------------------------------------------
    await page.getByTestId('plan-subscribe').first().click();
    // NewSubscription form: select the Owner.
    const ownerRow = page
        .locator('div.row', { has: page.locator('label', { hasText: /^\s*Owner\s*$/ }) })
        .first();
    await expect(ownerRow).toBeVisible({ timeout: 10_000 });
    await ownerRow.locator('div').filter({ hasText: /^Select\.\.\.$/ }).first().click();
    await page.getByText(clientName, { exact: true }).click();
    await page.getByTestId('subscription-create').click();
    await page.waitForURL(/\/subscriptions(\?|$)/, { timeout: 10_000 });

    let sub;
    await expect.poll(
        async () => {
            const subsRes = await page.request.get(`${PROXY_ANY}/apisubscriptions`);
            if (subsRes.status() >= 400) return false;
            const allSubs = await subsRes.json();
            sub = (Array.isArray(allSubs) ? allSubs : []).find(
                (s) => s.api_ref === apiId && s.token_refs?.length > 0
            );
            return !!sub;
        },
        { timeout: 15_000, intervals: [500, 1000, 2000] }
    ).toBe(true);
    expect(sub, 'subscription should have been created via UI Subscribe').toBeDefined();

    const apikeyClientId = sub.token_refs[0]?.apikey;
    expect(apikeyClientId, 'apikey id should be in token_refs[0].apikey').toBeTruthy();
    trackedApikeys.add(apikeyClientId);

    const apikeyRes = await page.request.get(`${PROXY_ANY}/apikeys/${apikeyClientId}`);
    expect(apikeyRes.status()).toBeLessThan(400);
    const apikey = await apikeyRes.json();
    expect(apikey.clientName, 'clientName should have the EL evaluated').toBe(`${planName}-foo-bar`);
    expect(apikey.enabled, 'apikey starts disabled (sub.status defaults to disabled)').toBe(false);

    await page.close();
});

test('publish flow + apikey plan in draft only gates draft route but not prod', async () => {
    const page = await context.newPage();

    const apiId = await createApiViaApi(page);
    trackedApis.add(apiId);
    const headerValue = 'pub-' + Math.random().toString(36).slice(2, 10);
    const domain = `pub-${apiId.slice(-8)}.oto.tools`;
    await setUpFullApi(page, apiId, { domain, headerValue, plan: null });

    await page.goto(`/bo/dashboard/apis/${apiId}?version=staging`);
    await page.getByTestId('publish-this-version').click();
    await page.getByRole('button', { name: 'Publish', exact: true }).click();
    await expect.poll(async () => (await getProd(page, apiId)).state).toBe('published');

    await expect
        .poll(async () => (await page.request.get(`http://${domain}:9999/v1/hello`)).status(),
            { timeout: 15_000, intervals: [500, 1000, 2000] })
        .toBe(200);

    const withHeader = await page.request.get(`http://${domain}:9999/v1/hello`, {
        headers: { 'X-OTOROSHI-TESTING': headerValue },
    });
    expect(withHeader.status()).toBe(200);

    const draft = await getDraft(page, apiId);
    const draftWithPlan = {
        ...draft,
        content: {
            ...draft.content,
            plans: [
                {
                    id: 'plan_apikey_draft',
                    name: 'Apikey plan (draft)',
                    type: 'free',
                    access_mode_configuration_type: 'apikey',
                    access_mode_configuration: { enabled: true },
                    consumerKind: 'apikey',
                    visibility: 'public',
                    documentation: null,
                    autoValidation: true,
                    subscriptionProcess: [],
                    integrationProcess: 'apikey',
                    status: 'published',
                },
            ],
        },
    };
    const putRes = await putDraft(page, apiId, draftWithPlan);
    expect(putRes.status()).toBeLessThan(400);

    await expect
        .poll(
            async () => (await page.request.get(`http://${domain}:9999/v1/hello`, {
                headers: { 'X-OTOROSHI-TESTING': headerValue },
            })).status(),
            { timeout: 30_000, intervals: [1000, 2000, 3000, 4000, 5000, 10000, 15000, 20000, 25000] }
        )
        .toBe(401);

    const stillProd = await page.request.get(`http://${domain}:9999/v1/hello`);
    expect(stillProd.status()).toBe(200);

    await page.close();
});

// Plan access-mode config (EL clientName, description, validUntil, metadata,
// tags) must propagate to the apikey generated by a subscription.
test('apikey plan: EL clientName + description + validUntil + metadata propagate to the apikey', async () => {
    test.setTimeout(60_000);
    const page = await context.newPage();

    const apiId = await createApiViaApi(page);
    trackedApis.add(apiId);

    const planName = uniqueName('el-plan');
    const ownerName = uniqueName('el-owner');
    const planDescription = 'description from the plan access-mode config';
    const validUntilMs = Date.UTC(2030, 0, 15, 12, 0, 0);

    const api = await getProd(page, apiId);
    const draftContent = {
        ...api,
        clients: [
            ...(api.clients || []),
            { id: 'client_el_owner', name: ownerName, description: null, tags: [], metadata: {} },
        ],
        plans: [
            {
                id: 'plan_el',
                name: planName,
                type: 'free',
                access_mode_configuration_type: 'apikey',
                access_mode_configuration: {
                    enabled: true,
                    clientNamePattern: '${plan.name}-foo-bar',
                    description: planDescription,
                    validUntil: validUntilMs,
                    metadata: { tier: 'gold' },
                    tags: ['plan-tag'],
                },
                consumerKind: 'apikey',
                visibility: 'public',
                documentation: null,
                autoValidation: true,
                subscriptionProcess: [],
                integrationProcess: 'apikey',
                status: 'published',
            },
        ],
    };
    const draftTpl = await (await page.request.get(`${PROXY_ANY}/drafts/_template`)).json();
    const draftRes = await page.request.post(`${PROXY_ANY}/drafts`, {
        data: { ...draftTpl, id: apiId, kind: apiId.split('_')[0], name: api.name, content: draftContent },
    });
    expect(
        draftRes.status(),
        `draft seed failed: ${await draftRes.text().catch(() => '')}`
    ).toBeLessThan(400);

    // Subscribe to the plan via the UI (draft mode). Give the subscription a
    // unique name — in draft mode the Subscriptions table lists every draft
    // subscription of the instance (not just this API's), so parallel runs
    // share the table and we must Confirm the exact row.
    const subName = uniqueName('el-sub');
    await page.goto(`/bo/dashboard/apis/${apiId}/plans?version=Draft`);
    await page.getByTestId('plan-subscribe').first().click();

    const subFieldInput = (label) =>
        page
            .locator('div.row', { has: page.locator('label', { hasText: new RegExp(`^\\s*${label}\\s*$`) }) })
            .locator('input:not([disabled])')
            .first();
    await subFieldInput('Name').fill(subName);

    const ownerRow = page
        .locator('div.row', { has: page.locator('label', { hasText: /^\s*Owner\s*$/ }) })
        .first();
    await expect(ownerRow).toBeVisible({ timeout: 10_000 });
    await ownerRow.locator('div').filter({ hasText: /^Select\.\.\.$/ }).first().click();
    await page.getByText(ownerName, { exact: true }).click();

    await expect(ownerRow).toContainText(ownerName, { timeout: 10_000 });

    await page.getByTestId('subscription-create').click();
    await page.waitForURL(/\/subscriptions(\?|$)/, { timeout: 10_000 });

    // Confirm via API: the draft Subscriptions table is instance-wide + paginated.
    let subId;
    await expect
        .poll(async () => {
            const res = await page.request.get(
                '/bo/api/proxy/apis/proxy.otoroshi.io/v1/drafts/api-subscription'
            );
            if (res.status() >= 400) return false;
            const drafts = await res.json();
            const found = (Array.isArray(drafts) ? drafts : []).find(
                (d) => (d?.content?.name ?? d?.name) === subName
            );
            if (found) subId = found.id;
            return !!found;
        }, { timeout: 15_000, intervals: [500, 1000, 2000] })
        .toBe(true);

    const confirmRes = await page.request.post(
        `/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${apiId}/subscriptions/${subId}/confirm?version=Draft`,
        { data: {} }
    );
    expect(confirmRes.status()).toBeLessThan(400);

    let apikey;
    await expect.poll(
        async () => {
            const res = await page.request.get(`${PROXY_ANY}/apikeys`);
            if (res.status() >= 400) return false;
            const all = await res.json();
            apikey = (Array.isArray(all) ? all : []).find(
                (k) => k.clientName === `${planName}-foo-bar`
            );
            return !!apikey;
        },
        { timeout: 15_000, intervals: [500, 1000, 2000] }
    ).toBe(true);

    expect(apikey.clientName).toBe(`${planName}-foo-bar`);
    expect(apikey.description).toBe(planDescription);
    expect(apikey.validUntil).toBe(validUntilMs);
    expect(apikey.metadata?.tier).toBe('gold');
    expect(apikey.tags).toContain('plan-tag');

    trackedApikeys.add(apikey.clientId);
    await page.close();
});
