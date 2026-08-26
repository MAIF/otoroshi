#!/usr/bin/env bun
/**
 * Provisions one demo API on a local Otoroshi, with a single route and one plan of each kind
 * (keyless, apikey, jwt, mtls, oauth2-local, oauth2-remote), then calls it the way each kind of consumer would. Nothing is
 * deleted at the end so the result can be browsed in the UI; re-running the script wipes what it
 * created before and starts over.
 *
 * Beware that every published plan stacks its own extractor on the single route, ordered by plugin
 * index: ApikeyCalls sits at 2.0 and the three other extractors at 3.0, in plan declaration order.
 * They all write to the same attribute, so the last one to resolve an identity wins. The response
 * carries X-Demo-Consumer and X-Demo-Plan so that whichever plan actually won is visible per call.
 *
 * Usage:
 *   bun scripts/api-plans-demo.ts              provision everything, then call it
 *   bun scripts/api-plans-demo.ts --cleanup    only remove what a previous run created
 *   bun scripts/api-plans-demo.ts --keep       provision without wiping the previous run first
 *   bun scripts/api-plans-demo.ts --no-calls   provision but do not call the api
 *   bun scripts/api-plans-demo.ts --help
 *
 * Env:
 *   OTOROSHI_HOST        default 127.0.0.1
 *   OTOROSHI_PORT        default 9999
 *   OTOROSHI_HTTPS_PORT  default 9998
 *   OTOROSHI_CLIENT_ID   default admin-api-apikey-id
 *   OTOROSHI_SECRET      default admin-api-apikey-secret
 *   OTOROSHI_DOMAIN      default oto.tools
 */

const HOST = process.env.OTOROSHI_HOST ?? '127.0.0.1';
const PORT = process.env.OTOROSHI_PORT ?? '9999';
const HTTPS_PORT = process.env.OTOROSHI_HTTPS_PORT ?? '9998';
const CLIENT_ID = process.env.OTOROSHI_CLIENT_ID ?? 'admin-api-apikey-id';
const SECRET = process.env.OTOROSHI_SECRET ?? 'admin-api-apikey-secret';
const DOMAIN = process.env.OTOROSHI_DOMAIN ?? 'oto.tools';

const PREFIX = 'apiplansdemo';
const BASE_DOMAIN = `${PREFIX}.${DOMAIN}`;
const JWT_SECRET = 'demo-jwt-secret';
const API_BASE = `http://${HOST}:${PORT}`;
const AUTH = 'Basic ' + Buffer.from(`${CLIENT_ID}:${SECRET}`).toString('base64');

const ids = {
  verifier: `${PREFIX}-verifier`,
  apikey: `${PREFIX}-apikey`,
  oauth2Apikey: `${PREFIX}-oauth2-apikey`,
  authModule: `${PREFIX}-auth-module`,
};
const OIDC_SECRET = 'demo-oidc-secret';
const OIDC_PORT = Number(process.env.OIDC_MOCK_PORT ?? 8099);
const OAUTH2_SECRET = 'demo-oauth2-secret';

// ---------------------------------------------------------------------------------------------
// fake oidc userinfo endpoint
// ---------------------------------------------------------------------------------------------

// The oauth2-remote plan verifies the token signature on its own, against the algo settings of the
// auth module: nothing remote is needed for that. What does need a server is `fetch_user`, which
// calls the userinfo endpoint of the module to fetch the profile of the token holder. This mock is
// only that endpoint, it does not implement any OIDC flow.
function startOidcMock() {
  return Bun.serve({
    port: OIDC_PORT,
    fetch(req) {
      const url = new URL(req.url);
      if (url.pathname === '/userinfo') {
        const token = (req.headers.get('Authorization') ?? '').replace('Bearer ', '');
        if (!token) return new Response('{"error":"no token"}', { status: 401 });
        return Response.json({
          sub: 'consumer-from-oidc',
          name: 'Demo Consumer',
          email: 'demo.consumer@example.com',
          groups: ['demo'],
          mock: true,
        });
      }
      return new Response('{"error":"not found"}', { status: 404 });
    },
  });
}

// ---------------------------------------------------------------------------------------------
// admin api plumbing
// ---------------------------------------------------------------------------------------------

async function adminCall(method: string, path: string, body?: unknown) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      Host: `otoroshi-api.${DOMAIN}`,
      Authorization: AUTH,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json: any = undefined;
  try {
    json = text.length > 0 ? JSON.parse(text) : undefined;
  } catch {
    json = text;
  }
  return { status: res.status, body: json };
}

async function must(method: string, path: string, body?: unknown, what = path) {
  const res = await adminCall(method, path, body);
  if (res.status >= 400) {
    throw new Error(`${what} failed: ${res.status} ${JSON.stringify(res.body)}`);
  }
  return res.body;
}

const quiet = (method: string, path: string) => adminCall(method, path);

// ---------------------------------------------------------------------------------------------
// jwt signing, HS512, no dependency
// ---------------------------------------------------------------------------------------------

function b64url(input: Buffer | string): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function signJwt(claims: Record<string, unknown>, secret: string): string {
  const header = b64url(JSON.stringify({ alg: 'HS512', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const payload = b64url(JSON.stringify({ iat: now, exp: now + 3600, ...claims }));
  const data = `${header}.${payload}`;
  const sig = new Bun.CryptoHasher('sha512', secret).update(data).digest();
  return `${data}.${b64url(sig)}`;
}

// ---------------------------------------------------------------------------------------------
// cleanup of a previous run
// ---------------------------------------------------------------------------------------------

async function cleanup() {
  // the api id is exactly the prefix, so no trailing separator here
  const apis = (await adminCall('GET', '/apis/apis.otoroshi.io/v1/apis')).body ?? [];
  const mine = Array.isArray(apis) ? apis.filter((a: any) => (a.id ?? '').startsWith(PREFIX)) : [];
  for (const api of mine) {
    await quiet('DELETE', `/apis/apis.otoroshi.io/v1/apis/${api.id}`);
    await quiet('DELETE', `/apis/proxy.otoroshi.io/v1/drafts/${api.id}`);
  }
  // belt and braces: the listing may not be reachable, the ids are known anyway
  await quiet('DELETE', `/apis/apis.otoroshi.io/v1/apis/${PREFIX}`);
  await quiet('DELETE', `/apis/proxy.otoroshi.io/v1/drafts/${PREFIX}`);
  await quiet('DELETE', `/api/apikeys/${ids.apikey}`);
  await quiet('DELETE', `/api/apikeys/${ids.oauth2Apikey}`);
  await quiet('DELETE', `/api/verifiers/${ids.verifier}`);
  await quiet('DELETE', `/api/auths/${ids.authModule}`);
  // the pki assigns its own ids, so our certs are tracked by name
  const certs = (await adminCall('GET', '/api/certificates')).body ?? [];
  const myCerts = Array.isArray(certs) ? certs.filter((c: any) => (c.name ?? '').startsWith(PREFIX)) : [];
  for (const c of myCerts) {
    await quiet('DELETE', `/api/certificates/${c.id}`);
  }
  console.log(`  removed ${mine.length} api(s), ${myCerts.length} certificate(s) and the rest of the previous run`);
}

// ---------------------------------------------------------------------------------------------
// provisioning
// ---------------------------------------------------------------------------------------------

// the pki saves the certificate under a generated id: renaming is what lets the next run find it
async function rename(cert: any, name: string) {
  await must('PUT', `/api/certificates/${cert.id}`, { ...cert, name, description: name }, `rename ${name}`);
}

async function createPki() {
  // a CA, a wildcard server cert so https works on every demo domain, and a client cert carrying
  // a UID that the mtls plan will use as the consumer identity
  const ca = await must(
    'POST',
    '/api/pki/cas',
    { hosts: [], key: { algo: 'rsa', size: 2048 }, subject: `CN=${PREFIX} CA, O=Otoroshi Demo`, duration: 365 * 24 * 3600 * 1000, ca: true },
    'ca creation'
  );
  const caId = ca.id;
  await rename(ca, `${PREFIX}-ca`);

  const server = await must(
    'POST',
    `/api/pki/cas/${caId}/certs`,
    {
      hosts: [`*.${BASE_DOMAIN}`, BASE_DOMAIN],
      key: { algo: 'rsa', size: 2048 },
      // an empty subject would force the SAN extension to be critical, which the pki refuses
      subject: `CN=*.${BASE_DOMAIN}, O=Otoroshi Demo`,
      duration: 365 * 24 * 3600 * 1000,
    },
    'server cert'
  );

  const client = await must(
    'POST',
    `/api/pki/cas/${caId}/certs`,
    {
      hosts: [],
      key: { algo: 'rsa', size: 2048 },
      subject: `UID=demo-consumer, CN=${PREFIX}-client, O=Otoroshi Demo`,
      duration: 365 * 24 * 3600 * 1000,
      client: true,
    },
    'client cert'
  );
  await rename(server, `${PREFIX}-server-cert`);
  await rename(client, `${PREFIX}-client-cert`);

  return { caId, server, client };
}

async function createVerifier() {
  await must(
    'POST',
    '/api/verifiers',
    {
      id: ids.verifier,
      name: ids.verifier,
      desc: 'demo verifier for the jwt plan',
      strict: true,
      source: { type: 'InHeader', name: 'Authorization', remove: 'Bearer ' },
      algoSettings: { type: 'HSAlgoSettings', size: 512, secret: JWT_SECRET, base64: false },
      strategy: { type: 'PassThrough', verificationSettings: { fields: {}, arrayFields: {} } },
    },
    'verifier'
  );
}

// NgOidcApikeyExtractor only needs the jwtVerifier of the module: it mounts a LocalJwtVerifier on
// those algo settings and never talks to the authorization server, so an HS512 secret is enough and
// no fake OIDC endpoint has to be served.
async function createAuthModule() {
  await must(
    'POST',
    '/api/auths',
    {
      id: ids.authModule,
      type: 'oauth2',
      name: ids.authModule,
      desc: 'demo oidc module for the oauth2-remote plan',
      clientId: 'demo-client',
      clientSecret: 'demo-client-secret',
      userInfoUrl: `http://127.0.0.1:${OIDC_PORT}/userinfo`,
      jwtVerifier: { type: 'HSAlgoSettings', size: 512, secret: OIDC_SECRET, base64: false },
    },
    'auth module'
  );
}

function apiPayload() {
  const id = PREFIX;
  const backendId = `${id}-backend`;
  const flowId = `${id}-flow`;
  // the plugins of a plan reach the runtime through the apiRef of the apikey: pluginFlow resolves
  // api + plan, and handleApikeyPluginsFlow merges them into the chain of the call. Same header
  // everywhere, one value per plan, so a call proves which plan chain actually ran.
  const planPlugins = (kind: string) => ({
    overrides: false,
    plugins: [
      {
        enabled: true,
        debug: false,
        plugin: 'cp:otoroshi.next.plugins.AdditionalHeadersOut',
        include: [],
        exclude: [],
        bound_listeners: [],
        config: { headers: { 'X-Plan-Plugin': `from-${kind}-plan` } },
      },
    ],
  });
  const plan = (kind: string, accessModeConfiguration: Record<string, unknown>) => ({
    id: `${id}-${kind}-plan`,
    plugins: planPlugins(kind),
    name: `${kind} plan`,
    description: `a ${kind} plan`,
    status: 'published',
    access_mode_configuration_type: kind,
    access_mode_configuration: accessModeConfiguration,
    visibility: { kind: 'public', config: {} },
    validation: { kind: 'auto', config: {} },
    pricing: { enabled: false },
    tags: [`${PREFIX}-${kind}`],
    metadata: { demo: kind },
  });
  return {
    id,
    name: `${PREFIX} demo`,
    description: 'one api, one route, one plan of each kind',
    domain: BASE_DOMAIN,
    contextPath: '',
    version: '0.0.1',
    versions: ['0.0.1'],
    state: 'staging',
    enabled: true,
    blueprint: 'REST',
    debugFlow: false,
    capture: false,
    exportReporting: false,
    groups: [],
    tags: [],
    metadata: {},
    testing: { enabled: false, headerKey: 'X-OTOROSHI-TESTING', headerValue: 'demo' },
    backends: [
      {
        id: backendId,
        name: 'demo-backend',
        client: 'default_backend_client',
        backend: {
          targets: [{ id: 'target_1', hostname: 'request.otoroshi.io', port: 443, tls: true, weight: 1, protocol: 'HTTP/1.1', predicate: { type: 'AlwaysMatch' }, ip_address: null }],
          root: '/',
          rewrite: false,
          load_balancing: { type: 'RoundRobin' },
        },
      },
    ],
    clients_backend_config: [],
    flows: [
      {
        id: flowId,
        name: 'demo-flow',
        plugins: [
          { enabled: true, debug: false, plugin: 'cp:otoroshi.next.plugins.OverrideHost', include: [], exclude: [], config: {}, bound_listeners: [] },
          // surfaces the identity that actually reached the backend, whichever plan produced it
          {
            enabled: true,
            debug: false,
            plugin: 'cp:otoroshi.next.plugins.AdditionalHeadersOut',
            include: [],
            exclude: [],
            bound_listeners: [],
            config: {
              headers: {
                'X-Demo-Consumer': '${apikey.clientId}',
                'X-Demo-Plan': '${apikey.api.plan}',
                'X-Demo-User': '${apikey.metadata.user_profile:none}',
              },
            },
          },
        ],
      },
    ],
    routes: [
      {
        id: `${id}-route`,
        enabled: true,
        name: 'demo-route',
        frontend: { domains: ['/'], headers: {}, cookies: {}, query: {}, methods: [], strip_path: true, exact: false },
        flow_ref: flowId,
        backend: backendId,
      },
    ],
    plans: [
      plan('keyless', { expr: '${req.ip}', create_if_missing: true }),
      plan('apikey', {}),
      plan('jwt', { verifier: ids.verifier, client_id_path: 'client_id', create_if_missing: true }),
      plan('mtls', {
        regex_subject_dns: [`.*CN=${PREFIX}-client.*`],
        client_id_field: 'UID',
        create_if_missing: true,
      }),
      plan('oauth2-local', {}),
      plan('oauth2-remote', {
        verifier: ids.authModule,
        client_id_path: 'client_id',
        fetch_user: true,
        user_metadata_key: 'user_profile',
        create_if_missing: true,
      }),
    ],
    subscriptions: [],
    deployments: [],
    clients: [],
    hooks: [],
  };
}

async function createAndPublish(payload: ReturnType<typeof apiPayload>) {
  await must('POST', '/apis/apis.otoroshi.io/v1/apis', payload, `api ${payload.id}`);
  // deploying reads the draft, so it has to exist first
  await must(
    'POST',
    '/apis/proxy.otoroshi.io/v1/drafts',
    { id: payload.id, name: payload.name, description: payload.description, kind: 'Api', content: payload },
    `draft ${payload.id}`
  );
  await must(
    'POST',
    `/apis/apis.otoroshi.io/v1/apis/${payload.id}/deployments`,
    {
      apiRef: payload.id,
      owner: 'api-plans-demo',
      at: Date.now(),
      apiDefinition: { ...payload, deployments: [] },
      draftId: payload.id,
    },
    `deploy ${payload.id}`
  );
}

// ---------------------------------------------------------------------------------------------
// the calls
// ---------------------------------------------------------------------------------------------

type CallResult = {
  how: string;
  status: number | string;
  consumer?: string;
  plan?: string;
  user?: string;
  planPlugin?: string;
  expectedPlanPlugin?: string;
};

function consumerOf(res: Response): { consumer?: string; plan?: string } {
  const raw = (h: string) => {
    const v = res.headers.get(h) ?? undefined;
    // an unresolved expression means no apikey was in the context
    return v && !v.includes('${') ? v : undefined;
  };
  return {
    consumer: raw('X-Demo-Consumer'),
    plan: raw('X-Demo-Plan'),
    user: raw('X-Demo-User'),
    planPlugin: res.headers.get('X-Plan-Plugin') ?? undefined,
  };
}

async function callHttp(
  how: string,
  expectedPlan: string,
  headers: Record<string, string> = {}
): Promise<CallResult> {
  try {
    const res = await fetch(`${API_BASE}/`, { headers: { Host: BASE_DOMAIN, ...headers } });
    return { how, status: res.status, expectedPlanPlugin: expectedPlan, ...consumerOf(res) };
  } catch (e: any) {
    return { how, status: `error: ${e.message}`, expectedPlanPlugin: expectedPlan };
  }
}

async function callMtls(how: string, expectedPlan: string, cert: any): Promise<CallResult> {
  try {
    const res = await fetch(`https://${BASE_DOMAIN}:${HTTPS_PORT}/`, {
      tls: { cert: cert.chain, key: cert.privateKey, rejectUnauthorized: false },
    } as any);
    return { how, status: res.status, expectedPlanPlugin: expectedPlan, ...consumerOf(res) };
  } catch (e: any) {
    return { how, status: `error: ${e.message}`, expectedPlanPlugin: expectedPlan };
  }
}

// ---------------------------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------------------------

const flags = new Set(process.argv.slice(2));
const unknown = [...flags].filter((f) => !['--cleanup', '--keep', '--no-calls', '--help', '-h'].includes(f));

if (flags.has('--help') || flags.has('-h') || unknown.length > 0) {
  if (unknown.length > 0) console.log(`\nunknown flag: ${unknown.join(', ')}`);
  console.log(`
usage: bun scripts/api-plans-demo.ts [flags]

  --cleanup    only remove what a previous run created, then exit
  --keep       provision without wiping the previous run first
  --no-calls   provision but do not call the api
  --help, -h   this message

env: OTOROSHI_HOST, OTOROSHI_PORT, OTOROSHI_HTTPS_PORT, OTOROSHI_CLIENT_ID, OTOROSHI_SECRET, OTOROSHI_DOMAIN
`);
  process.exit(unknown.length > 0 ? 1 : 0);
}

console.log(`\n=== api plans demo on ${API_BASE} ===\n`);

if (flags.has('--cleanup')) {
  console.log('cleaning up');
  await cleanup();
  console.log('\ndone, nothing was recreated\n');
  process.exit(0);
}

if (flags.has('--keep')) {
  console.log('1. keeping what is already there');
} else {
  console.log('1. cleaning up a previous run');
  await cleanup();
}

console.log('2. creating the pki (ca, wildcard server cert, client cert with UID=demo-consumer)');
const pki = await createPki();

console.log(`3. starting the fake oidc userinfo endpoint on :${OIDC_PORT}`);
const oidcMock = startOidcMock();

console.log('3b. creating the jwt verifier and the oidc auth module');
await createVerifier();
await createAuthModule();

console.log('4. creating and publishing the api (one route, six plans)');
const apiPl = apiPayload();
await createAndPublish(apiPl);

console.log('5. creating an apikey for the apikey plan');
await must(
  'POST',
  '/api/apikeys',
  {
    clientId: ids.apikey,
    clientSecret: 'demo-secret',
    clientName: ids.apikey,
    authorizedEntities: [`api_${PREFIX}`, /*`route_${PREFIX}-route_prod`*/],
    apiRef: {
      api: apiPl.id,
      plan: `apiplansdemo-apikey-plan`,
      sub: 'xxx',
    },
    enabled: true,
  },
  'apikey'
);

console.log('5b. creating a dedicated apikey for the oauth2-local plan');
await must(
  'POST',
  '/api/apikeys',
  {
    clientId: ids.oauth2Apikey,
    clientSecret: OAUTH2_SECRET,
    clientName: ids.oauth2Apikey,
    authorizedEntities: [`api_${PREFIX}`],
    apiRef: {
      api: apiPl.id,
      plan: `${PREFIX}-oauth2-local-plan`,
      sub: 'xxx',
    },
    enabled: true,
  },
  'oauth2 apikey'
);

console.log('6. waiting for the proxy state to pick up the generated route');
await Bun.sleep(12000);

if (flags.has('--no-calls')) {
  oidcMock.stop(true);
  console.log(`\n=== provisioned, no call made, browse it at http://otoroshi.${DOMAIN}:${PORT} ===\n`);
  process.exit(0);
}

console.log('7. calling the api\n');
const results: CallResult[] = [
  await callHttp('no credential at all', 'from-keyless-plan'),
  await callHttp('Otoroshi-Client-Id / Secret', 'from-apikey-plan', {
    'Otoroshi-Client-Id': ids.apikey,
    'Otoroshi-Client-Secret': 'demo-secret',
  }),
  await callHttp('Bearer token, client_id claim', 'from-jwt-plan', {
    Authorization: `Bearer ${signJwt({ iss: 'demo', client_id: 'consumer-from-token' }, JWT_SECRET)}`,
  }),
  await callMtls('client certificate', 'from-mtls-plan', pki.client),
  // the apikey doubles as the signing key: ApikeyCalls reads the clientId claim, looks the apikey
  // up, then validates the HS512 signature against its own clientSecret
  await callHttp('apikey as a signed jwt', 'from-oauth2-local-plan', {
    Authorization: `Bearer ${signJwt({ clientId: ids.oauth2Apikey }, OAUTH2_SECRET)}`,
  }),
  await callHttp('oidc token, client_id claim', 'from-oauth2-remote-plan', {
    Authorization: `Bearer ${signJwt({ iss: 'demo-idp', client_id: 'consumer-from-oidc' }, OIDC_SECRET)}`,
  }),
];

const failures: string[] = [];

for (const r of results) {
  const statusOk = r.status === 200;
  const planOk = r.planPlugin === r.expectedPlanPlugin;
  const who = r.consumer ? `${r.consumer}${r.plan ? `  (plan ${r.plan})` : ''}` : 'no consumer';
  console.log(
    `  ${statusOk ? '\u2713' : '\u2717'} ${String(r.how).padEnd(30)} -> ${String(r.status).padEnd(6)} ${who}`
  );
  if (r.user && r.user !== 'none') console.log(`       user profile: ${r.user}`);
  console.log(
    `       plan plugin : ${planOk ? '\u2713' : '\u2717'} ${r.planPlugin ?? 'none'}` +
      (planOk ? '' : `  (expected ${r.expectedPlanPlugin})`)
  );
  if (!statusOk) failures.push(`${r.how}: expected 200, got ${r.status}`);
  if (!planOk) {
    failures.push(`${r.how}: expected plan plugin ${r.expectedPlanPlugin}, got ${r.planPlugin ?? 'none'}`);
  }
  // an identity is what every plan is supposed to produce, so its absence is a failure too
  if (statusOk && !r.consumer) failures.push(`${r.how}: no consumer identity reached the backend`);
}

oidcMock.stop(true);

const GREEN = '\u001b[32m';
const RED = '\u001b[31m';
const BOLD = '\u001b[1m';
const OFF = '\u001b[0m';

if (failures.length === 0) {
  console.log(`
${GREEN}${BOLD}  +--------------------------------------------------------+
  |                                                        |
  |   ALL CHECKS PASSED - ${String(results.length).padStart(2)} calls, every plan verified   |
  |                                                        |
  +--------------------------------------------------------+${OFF}`);
} else {
  console.log(`
${RED}${BOLD}  +--------------------------------------------------------+
  |                                                        |
  |   ${String(failures.length).padStart(2)} CHECK(S) FAILED                                  |
  |                                                        |
  +--------------------------------------------------------+${OFF}`);
  for (const f of failures) console.log(`${RED}   - ${f}${OFF}`);
}

console.log(`\n=== nothing was deleted, browse it at http://otoroshi.${DOMAIN}:${PORT} ===`);
console.log(`  api           ${PREFIX} (plans: keyless, apikey, jwt, mtls, oauth2-local)`);
console.log(`  domain        ${BASE_DOMAIN}`);
console.log(`  jwt secret    ${JWT_SECRET} (HS512, claim client_id)`);
console.log(`  apikey        ${ids.apikey} / demo-secret`);
console.log(`  oauth2 apikey ${ids.oauth2Apikey} / ${OAUTH2_SECRET} (HS512 signer, claim clientId)`);
console.log(`  auth module   ${ids.authModule}, jwtVerifier HS512 ${OIDC_SECRET} (claim client_id)`);
console.log(`  oidc mock     http://127.0.0.1:${OIDC_PORT}/userinfo, only up while the script runs`);
console.log(`  client cert   subject UID=demo-consumer, CN=${PREFIX}-client`);
console.log(
  `\n  note: the apikeys minted by the keyless, jwt and mtls plans live in memory only and are`
);
console.log(`        never persisted, so they will not show up in the apikeys page`);
console.log(`\n  re-run to wipe and recreate, or --cleanup to only remove it all\n`);

process.exit(failures.length === 0 ? 0 : 1);
