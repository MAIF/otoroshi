# Phase 1 — API lifecycle: business rules enforcement + Playwright e2e tests

## Context

We want to harden the API entity in Otoroshi (`/Users/79966B/Documents/opensource/otoroshi/otoroshi/app/next/models/Api.scala`) before iterating on UX. Today the spec documented by the team (Draft vs Published modes, status lifecycle, production read-only for the "design" layer, X-OTOROSHI-TESTING header as dev/prod discriminator) is enforced **only partially** — mostly in the frontend, with gaps. The backend `Api.writeValidator` accepts any update; `createNewVersion` is the only place that flips state. Phase 1 closes those gaps by:

1. Adding backend-side validation of the rules (defense in depth).
2. Filling frontend gaps where production read-only is not actually enforced today (notably `Informations.js`).
3. Writing Playwright e2e tests that codify the lifecycle and become a regression net.

A separate Phase 2 (NOT covered here) will redesign the Getting Started UI as a Stripe-like bottom-left stepper that allows skipping steps. Plan + subscriptions tests are also deferred ("on testera après").

User confirmations during exploration:
- Keep `removed` as the 4th status (not `closed`).
- Backend + frontend enforcement (both).
- `publishAPI` keeps wiping the draft after deploy — and deployment payload should be reduced (today stores full `apiDefinition`, too much).
- Production-editable fields: everything in Informations / Actions / Exposition (API Gateway = domain+contextPath) / Plans / Clients tabs is allowed in prod. **Only `routes`, `backends`, `flows`, `documentation`, `testing` require a deploy** (must edit via Draft).

---

## Part A — Backend enforcement (`Api.scala` + `apis.scala`)

### A.1 — Add helper `Api.isValidStateTransition` (`app/next/models/Api.scala`, near line 1781)

Pure function:

```
def isValidStateTransition(from: ApiState, to: ApiState, viaDeploy: Boolean): Boolean
```

Allowed transitions:

| from \ to    | staging | published | deprecated | removed |
|--------------|---------|-----------|------------|---------|
| staging      | yes (no-op) | yes ONLY if `viaDeploy=true` | no | no |
| published    | no      | yes (no-op) | yes | yes |
| deprecated   | no      | yes (republish, direct) | yes (no-op) | yes |
| removed      | yes (reopen) | no | no | yes (no-op) |

Plus `def transitionError(from, to): JsValue` returning `{error: "invalid_state_transition", error_description, from, to}`.

### A.2 — Add helper `Api.diffProtectedFields` (same file)

```
def diffProtectedFields(oldApi: Api, newApi: Api): Seq[String]
```

**Locked in production** (these MUST go through Draft + deploy):
- `routes`
- `backends`
- `flows`
- `documentation`
- `testing`

Every other field on the `Api` case class (lines 1444-1476) is allowed: `name`, `description`, `domain`, `contextPath`, `metadata`, `tags`, `enabled`, `state` (subject to A.1 rules), `plans`, `clients`, `clientsBackendConfig`, `owner`, `members`, `visibility`, `groups`, `location`, `version`, `versions`, `blueprint`, `debugFlow`, `capture`, `exportReporting`, `hooks`, `deployments`.

Implement via field-by-field equality on the case class (not JSON diff).

### A.3 — Wire `Api.writeValidator` (`Api.scala:1783-1799`)

Replace the no-op body with:

1. **Create path** (`oldEntity` is `None`): require `entity.state == ApiStaging`. Otherwise return `Left({error: "cannot_create_in_state", state: entity.state.name})`.
2. **Update path** (`oldEntity` is `Some(old)`):
   - `if (!isValidStateTransition(old.state, entity.state, viaDeploy=false)) return Left(transitionError(...))`.
   - `if (old.state != ApiStaging)`: compute `diffProtectedFields(old, entity)`. If non-empty, return `Left({error: "production_readonly", fields})`.
3. On success, call existing `ApiConsistencyService.applyApiChanges(old, entity, isDraft=false)` and return `Right(entity)`.

Drafts are exempt: they go through `Draft.writeValidator` (`app/api/api.scala:994`), a separate path.

### A.4 — Add transition guard in `createNewVersion` (`app/next/controllers/apis.scala:649-718`)

Before calling `env.datastores.apiDataStore.set(updatedApi)` (around line 690), compute `targetState = if (apiDraft.state == ApiStaging) ApiPublished else api.state` and check `Api.isValidStateTransition(api.state, targetState, viaDeploy=true)`. If false, return `BadRequest({error: "invalid_deploy_transition", from, to})`. This catches deploying a `removed` API, etc.

Add a code comment noting that this is intentional redundancy because `apiDataStore.set` bypasses `writeValidator`.

### A.5 — Reduce deployment payload (`apis.scala` around line 670 + `Api.scala:147-154`)

Per user note: deployments currently snapshot the full `apiDefinition: JsValue` — too much data. Slim the snapshot to what's actually useful for rollback / audit:

- Keep: `id`, `apiRef`, `owner`, `at`, `version`, and a minimal `apiDefinition` containing only `routes`, `backends`, `flows`, `documentation` (the locked-in-prod fields — the "design" snapshot).
- Drop: subscriptions/clients/plans state, testing config, metadata diff noise.

Keep the `Seq(deployment) ++ api.deployments).slice(0, 5)` cap (5 latest). If the slim snapshot still feels too heavy, consider gzipping or storing in a separate datastore — flag as a follow-up.

### A.6 — JSON read fallback stays as-is (`Api.scala:1958-1966`)

Unknown state values silently default to `ApiStaging`. Keep this — no migration burden. Add a `logger.warn` when it triggers.

### A.7 — Don't touch `Draft.writeValidator`

Drafts are exempt from all the above. Confirmed at `app/api/api.scala:994`.

---

## Part B — Frontend audit & gap-fix

### B.1 — Transitions in `Actions.js` (lines 354-449)

Already matches A.1. **No code change**. Add `data-testid` attributes for stable selectors: `action-card-publish`, `action-card-deprecate`, `action-card-close`, `action-card-reopen`, `action-card-republish`.

### B.2 — Production read-only gaps to close

Audit summary:

| Component | Current | Action |
|---|---|---|
| [Routes.js:456](otoroshi/javascript/src/pages/ApiEditor/Routes.js#L456) | `showActions={isDraft}` | OK — locked in prod |
| [Backends.js:90](otoroshi/javascript/src/pages/ApiEditor/Backends.js#L90) | `showActions={isDraft}` | OK |
| [PluginChains.js](otoroshi/javascript/src/pages/ApiEditor/PluginChains.js) | (verify during impl) | Lock writes in prod (flows are locked) |
| [Documentation.js:203](otoroshi/javascript/src/pages/ApiEditor/Documentation.js#L203) | uses `isDraft` | Confirm writes are gated; documentation is locked in prod |
| [Testing.js:168](otoroshi/javascript/src/pages/ApiEditor/Testing.js#L168) | returns `<TestingProductionMode />` if prod | OK — locked |
| [Informations.js](otoroshi/javascript/src/pages/ApiEditor/Informations.js) | fully editable in prod | **No-op** — Informations IS editable in prod per user spec |
| [APIGateway.js](otoroshi/javascript/src/pages/ApiEditor/APIGateway.js) | (verify) | Editable in prod (Exposition tab is prod-mutable per spec) |
| [Plans.js](otoroshi/javascript/src/pages/ApiEditor/Plans.js) | uses `updateItem` → `updateAPI` in prod | OK — editable |
| [Clients.js](otoroshi/javascript/src/pages/ApiEditor/Clients.js) | uses `updateItem` → `updateAPI` in prod | OK — editable |
| [Subscriptions.js](otoroshi/javascript/src/pages/ApiEditor/Subscriptions.js) | separate entity | OK |

**Concrete gaps to fix:**
- `PluginChains.js` — confirm writes are gated; add `disabled={!isDraft}` on Save / Add buttons if not.
- `Documentation.js` — confirm writes are gated to draft only (locked field per A.2).

### B.3 — Surface backend rejections in the UI

When the backend returns `{error: "production_readonly"}` or `{error: "invalid_state_transition"}`, render via `window.newAlert(err.error_description || err.error)`. Touchpoints: `Actions.js` (state transition errors), `Informations.js` / `APIGateway.js` / wherever `updateAPI` is called (read-only rejections).

### B.4 — Wipe-draft-on-deploy stays

Per user: `Actions.js:70` `.then(() => deleteById(api.id))` is correct — keep wiping. Update Test 2 assertions accordingly (draft is gone after publish; reopens fresh from prod when "Edit in Draft Mode" is clicked).

### B.5 — `data-testid`s for test stability

Add to:
- Action cards (B.1)
- `Testing.js` header key/value inputs + rotate button: `testing-header-key`, `testing-header-value`, `testing-rotate-button`
- `DraftOnly.js` VersionToggle: `version-toggle`
- `Informations.js` Save button: `save-informations`
- Each Sidebar tab (`Sidebar.js`): `sidebar-tab-{name}`
- Getting Started step cards: `gs-step-{n}`

---

## Part C — Playwright e2e tests

All new tests in `/Users/79966B/Documents/opensource/otoroshi/otoroshi/javascript/tests/spec/admin/`. Reuses existing auth state (`tests/playwright/.auth/admin.json`) and `validAnonymousModal` helper from `tests/utils.js`.

### Shared helper module (NEW)

**File**: [javascript/tests/spec/admin/_apiHelpers.js](otoroshi/javascript/tests/spec/admin/_apiHelpers.js)

Exports:
- `createApiViaUI(page, {name, description})` → `apiId` (extracts the existing `createAPI` flow from `api.spec.js:21-40`)
- `deleteApiViaUI(page)`, `deleteApiViaApi(page, apiId)`
- `createApiViaApi(page, {name, description})` → `apiId` (via `page.request.post`)
- `getDraft(page, apiId)` / `getProd(page, apiId)` → JSON
- `putProd(page, apiId, body)` / `putDraft(page, apiId, body)` → response
- `postDeployment(page, apiId, body)` → response

Underscore prefix so Playwright doesn't pick it up as a spec file.

### Spec file 1 — `api-lifecycle.spec.js` (NEW)

**Test 1 — "creates an API from scratch and lands in staging" (UI)**
- Use `createApiViaUI`. After Create:
  - URL matches `/apis/(api_dev_[a-f0-9-]+)`.
  - `getByText('DEV', { exact: true })` visible.
  - `getProd(apiId).state === 'staging'`.
  - `getDraft(apiId).content.state === 'staging'`.
  - `getDraft(apiId).content.testing.headerKey === 'X-OTOROSHI-TESTING'`.
  - `getDraft(apiId).content.testing.headerValue` matches `/^[0-9a-f-]{36}$/`.
- Cleanup: `deleteApiViaApi`.

**Test 2 — "walks through getting-started, publishes, draft is wiped" (UI)**

Set timeout to 30s: `test.setTimeout(30_000)` at top of test.

Steps drive the 7 cards from [Dashboard.js:720-777](otoroshi/javascript/src/pages/ApiEditor/Dashboard.js#L720-L777):
1. Create endpoint (frontend domain text + Create).
2. Add backend (target config + Save).
3. Skip plugin chain (it has `showOnlyIfPublished: true`).
4. Configure API Gateway (domain + contextPath in `APIGateway.js`).
5. Enable testing: toggle on; assert `getByTestId('testing-header-key')` shows `X-OTOROSHI-TESTING`; assert `getByTestId('testing-header-value')` matches UUID; click rotate; capture new value; assert different + still UUID.
6. Add a plan (keyless, status: published).
7. Click Publish card on dashboard; confirm modal.

API-level assertions after publish:
- `getProd(apiId).state === 'published'`.
- `getProd(apiId).deployments.length >= 1`, first has `apiRef === apiId`, `at` is recent.
- `getProd(apiId).deployments[0].apiDefinition` contains `routes`, `backends`, `flows`, `documentation` only (per A.5 slim payload).
- `getDraft(apiId)` returns **404** (draft wiped per B.4).
- Click "Edit in Draft Mode" → draft recreated; `getDraft(apiId)` returns 200 with same content as prod.

Cleanup: `deleteApiViaApi`.

**Test 3 — "draft vs prod fetch diverges after a draft-only edit" (UI + API)**
- Setup: create + publish (via `_apiHelpers.createPublishedApi`).
- Switch to `?version=Draft`, change description in Informations, Save.
- Assert `getDraft(apiId).content.description === 'draft-only edit'`.
- Assert `getProd(apiId).description !== 'draft-only edit'`.
- Reload UI at `?version=Published`: original description shown.
- Reload at `?version=Draft`: new description shown.

**Test 4 — "X-OTOROSHI-TESTING header gates dev vs prod traffic" (gateway)**
- Setup: create + publish API. Domain `e2e-test.oto.tools`, contextPath `/v1`, route `/hello`, backend → `127.0.0.1:9999/api/cluster/node/health` (always responds).
- Draft-only modification: add route `/hello-draft` in draft. Enable testing. Capture `headerValue`.
- **No re-deploy.**

Assertions (using `page.request`):
- `GET http://e2e-test.oto.tools:9999/v1/hello` → 200 (prod route fires).
- `GET http://e2e-test.oto.tools:9999/v1/hello-draft` → 404 (draft frontend not matched without header).
- `GET http://e2e-test.oto.tools:9999/v1/hello-draft` with `X-OTOROSHI-TESTING: <value>` → 200 (draft route fires).
- `GET http://e2e-test.oto.tools:9999/v1/hello` with `X-OTOROSHI-TESTING: <value>` → 200 (prod route still fires; header is permissive, not exclusive).

Mechanism reference: [Api.scala:1497-1504](otoroshi/app/next/models/Api.scala#L1497-L1504) (`addSecurityOnDrafRoute` injects the header), [Api.scala:1516-1544](otoroshi/app/next/models/Api.scala#L1516-L1544) (`buildDraftRoutes`).

### Spec file 2 — `api-rules.spec.js` (NEW)

Pure API-level tests for speed.

**Test 5 — "state transitions allowed/denied"**

One test per cell of the matrix. Use `createApiViaApi`; for non-staging starting states, transition through the legal chain to get there.

Allowed (200 expected via `putProd` or deployment endpoint):
- staging → published via `postDeployment` (only legal path)
- published → deprecated (PUT)
- published → removed (PUT)
- deprecated → published (PUT, republish)
- deprecated → removed (PUT)
- removed → staging (PUT)

Denied (400 with `error: "invalid_state_transition"` expected):
- staging → published via plain PUT (must use deploy)
- staging → deprecated
- staging → removed
- published → staging
- deprecated → staging
- removed → published
- removed → deprecated

Cleanup per test.

**Test 6 — "production read-only enforcement"**
- Setup: create + publish via helpers.

Allowed (200):
- Modify `plans` (add a plan).
- Modify `clients`.
- Modify `name`, `description`, `version`.
- Modify `metadata`, `tags`, `enabled`.
- Modify `domain`, `contextPath`.

Denied (400, `error: "production_readonly"`, `fields` lists offender):
- Modify `routes`.
- Modify `backends`.
- Modify `flows`.
- Modify `documentation`.
- Modify `testing.headerValue`.

**Test 7 — "create cannot start outside staging"**
- POST `/bo/api/proxy/apis/any/v1/apis` with body containing `state: "published"` → 400, `error: "cannot_create_in_state"`.

### Spec file 3 — extension to `api-lifecycle.spec.js`: UI cross-checks

**Test 8 — "Actions tab only shows legal transitions"**
- Staging API: only `action-card-publish` visible.
- Published API: `action-card-deprecate` and `action-card-close` visible; no publish/republish.
- Deprecated API: `action-card-republish` and `action-card-close` visible.
- Removed API: `action-card-reopen` visible.

**Test 9 — "production-locked fields are disabled in UI"**
- Published API, navigate to:
  - Endpoints tab → "Add endpoint" button hidden/disabled.
  - Backends tab → "Add backend" hidden/disabled.
  - Plugin chains tab → "Add" hidden/disabled.
  - Documentation tab → editor read-only.
  - Testing tab → renders `TestingProductionMode` (no header rotate button).
- Same API, switch to `?version=Draft` → all the above become editable.

### Backend unit spec (NEW)

**File**: `/Users/79966B/Documents/opensource/otoroshi/otoroshi/test/functional/ApiBusinessRulesSpec.scala`

Pure unit tests for `Api.isValidStateTransition` and `Api.diffProtectedFields`. No Play server. Quick to run.

Run: `sbt "testOnly *ApiBusinessRulesSpec*"`.

---

## Part D — Verification

### Backend
```
sbt "testOnly *ApiBusinessRulesSpec*"
sbt "testOnly *AdminApi*"
```

### Playwright
From `javascript/`:
```
npx playwright install chromium  # first time
npx playwright test tests/spec/admin/api-lifecycle.spec.js
npx playwright test tests/spec/admin/api-rules.spec.js
npx playwright test  # full suite
npx playwright test --headed --debug tests/spec/admin/api-lifecycle.spec.js
```

Prereq: Otoroshi running at `http://otoroshi.oto.tools:9999` (`sbt run`).

### Manual smoke (10 min)
1. Start Otoroshi; log in as admin.
2. Create API via "Build from Scratch" → DEV badge, `state=staging`.
3. Walk through getting-started: endpoint → backend → API Gateway → testing (note header value, click rotate) → plan → Publish.
4. After publish: PROD badge, draft is gone, `curl /apis/{id}` shows `state=published`, `deployments` has slim payload.
5. Click "Edit in Draft Mode" → draft reappears with prod content.
6. In Draft, change description; save. Confirm `curl /apis/{id}` unchanged, `curl /drafts/{id}` shows new description.
7. In Production view, navigate to Informations → name/description editable. Routes tab → Add button hidden.
8. `curl -X PUT /apis/{id}` with changed `routes` → 400 `production_readonly`.
9. `curl -X PUT /apis/{id}` with `state: "staging"` from published → 400 `invalid_state_transition`.
10. Add a route in draft at `/hello-draft`. Call gateway domain without header → 404. With header → 200.

---

## Critical files

**Backend (changes):**
- [otoroshi/app/next/models/Api.scala](otoroshi/app/next/models/Api.scala) — A.1, A.2, A.3, A.5 (deployment payload trim), A.6 (warn on unknown state)
- [otoroshi/app/next/controllers/apis.scala](otoroshi/app/next/controllers/apis.scala) — A.4, A.5

**Backend (NEW):**
- [otoroshi/test/functional/ApiBusinessRulesSpec.scala](otoroshi/test/functional/ApiBusinessRulesSpec.scala) — unit tests for A.1, A.2

**Frontend (changes):**
- [otoroshi/javascript/src/pages/ApiEditor/Actions.js](otoroshi/javascript/src/pages/ApiEditor/Actions.js) — B.1 testids + B.3 error toast
- [otoroshi/javascript/src/pages/ApiEditor/PluginChains.js](otoroshi/javascript/src/pages/ApiEditor/PluginChains.js) — B.2 gate writes if gap
- [otoroshi/javascript/src/pages/ApiEditor/Documentation.js](otoroshi/javascript/src/pages/ApiEditor/Documentation.js) — B.2 gate writes if gap
- [otoroshi/javascript/src/pages/ApiEditor/Testing.js](otoroshi/javascript/src/pages/ApiEditor/Testing.js) — B.5 testids
- [otoroshi/javascript/src/pages/ApiEditor/DraftOnly.js](otoroshi/javascript/src/pages/ApiEditor/DraftOnly.js) — B.5 testid on VersionToggle
- [otoroshi/javascript/src/pages/ApiEditor/Informations.js](otoroshi/javascript/src/pages/ApiEditor/Informations.js) — B.3 error toast + B.5 Save testid
- [otoroshi/javascript/src/pages/ApiEditor/Sidebar.js](otoroshi/javascript/src/pages/ApiEditor/Sidebar.js) — B.5 testids on tabs
- [otoroshi/javascript/src/pages/ApiEditor/Dashboard.js](otoroshi/javascript/src/pages/ApiEditor/Dashboard.js) — B.5 testids on getting-started cards (no behavior change — Phase 2 redesigns these)

**Frontend (NEW tests):**
- [otoroshi/javascript/tests/spec/admin/_apiHelpers.js](otoroshi/javascript/tests/spec/admin/_apiHelpers.js)
- [otoroshi/javascript/tests/spec/admin/api-lifecycle.spec.js](otoroshi/javascript/tests/spec/admin/api-lifecycle.spec.js)
- [otoroshi/javascript/tests/spec/admin/api-rules.spec.js](otoroshi/javascript/tests/spec/admin/api-rules.spec.js)

---

## Risks & notes

- `createNewVersion` bypasses `writeValidator` (calls `apiDataStore.set` directly). A.4 adds a redundant transition guard. Code comment explains why.
- Extensions/plugins can call `apiDataStore.set` directly, bypassing all our HTTP-layer rules. Acceptable scope: enforcement at the API layer, not data layer. Mention in PR description.
- No superadmin override. Anyone with `canUserWriteJson` on the API resource is subject to the rules. Confirmed with user.
- `ApiConsistencyService.scala` has stray `println` debug statements (lines 25, 55, 131). Out of scope for this phase — leave for a separate cleanup PR.
- `hooks.js:19`: `isDraft = version === 'Draft' || version === 'staging'`. The `'staging'` case makes draft writes happen on staging APIs even without `?version=Draft` in the URL. Test 1 must not assume the URL carries `?version=` right after creation.
- Playwright `timeout: 5000` in [playwright.config.js:6](otoroshi/javascript/playwright.config.js#L6) is too tight for Test 2. Use `test.setTimeout(30_000)` at file top, don't change global config.
- Test 4 (gateway routing) needs `*.oto.tools` DNS resolution. Local dev works; CI may need a hosts shim. Existing tests already rely on `baseURL: http://otoroshi.oto.tools:9999` so this is established.

## Deferred to later phases (NOT this PR)

- Getting Started UI redesign (Stripe-style bottom-left stepper, skip-step support) → **Phase 2**.
- Plan + subscription lifecycle tests (subscribe, pending → enabled, plan deprecation → subscription migration) → **Phase 3**.
- Cleanup of `println` debug calls in `ApiConsistencyService.scala`.
- Cleanup of duplicated `updateDraft + updateAPI` state writes in `Actions.js:165-168`.
- Deployment payload further compression (gzip / separate store) if A.5 slim version still feels heavy.
