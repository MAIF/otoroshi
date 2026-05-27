import { gradeFor } from './grades';

export const SEVERITY_WEIGHTS = { error: 1.0, warn: 0.5, info: 0.2, hint: 0.1 };
// From most to least severe — used for ordering and for stable iteration.
export const SEVERITY_ORDER = ['error', 'warn', 'info', 'hint'];

export const CATEGORIES = [
  { key: 'documentation', label: 'Documentation' },
  { key: 'security', label: 'Security' },
  { key: 'routing', label: 'Routing' },
  { key: 'governance', label: 'Governance' },
];

const categoryLabel = (key) => (CATEGORIES.find((c) => c.key === key) || {}).label || key;

// Safe accessors — the API entity may be partially filled while editing a draft.
const routesOf = (api) => api?.routes || [];
const backendsOf = (api) => api?.backends || [];
const flowsOf = (api) => api?.flows || [];
const plansOf = (api) => api?.plans || [];
const pluginIds = (api) =>
  flowsOf(api).flatMap((f) => (f?.plugins || []).map((p) => p?.plugin || ''));
const hasPluginMatching = (api, needle) =>
  pluginIds(api).some((id) => (id || '').toLowerCase().includes(needle.toLowerCase()));

const KEYLESS_TYPES = ['keyless', 'public'];

export const RULES = [
  // ---- Documentation ----------------------------------------------------
  {
    id: 'doc-description-missing',
    category: 'documentation',
    severity: 'error',
    message: 'The API has no description.',
    fix: 'Add a clear description so consumers understand what this API does.',
    to: 'informations',
    failed: (api) => !api?.description || api.description.trim().length === 0,
  },
  {
    id: 'doc-description-short',
    category: 'documentation',
    severity: 'warn',
    message: 'The API description is very short.',
    fix: 'Expand the description to at least 20 characters.',
    to: 'informations',
    failed: (api) => {
      const d = (api?.description || '').trim();
      return d.length > 0 && d.length < 20;
    },
  },
  {
    id: 'doc-disabled',
    category: 'documentation',
    severity: 'warn',
    message: 'Documentation is disabled for this API.',
    fix: 'Enable documentation to publish guides and references for consumers.',
    to: 'documentation',
    failed: (api) => !api?.documentation || api.documentation.enabled === false,
  },
  {
    id: 'doc-no-pages',
    category: 'documentation',
    severity: 'info',
    message: 'The documentation has no content pages.',
    fix: 'Add documentation pages (markdown guides, …) shown in the dev portal.',
    to: 'documentation',
    failed: (api) => (api?.documentation?.resources?.length || 0) === 0,
  },
  {
    id: 'doc-no-openapi',
    category: 'documentation',
    severity: 'info',
    message: 'No OpenAPI specification is referenced.',
    fix: 'Reference an OpenAPI spec so consumers get an interactive API reference.',
    to: 'documentation',
    failed: (api) => (api?.documentation?.references?.length || 0) === 0,
  },
  // ---- Security ---------------------------------------------------------
  {
    id: 'sec-no-plans',
    category: 'security',
    severity: 'warn',
    message: 'The API exposes no consumption plan.',
    fix: 'Create at least one plan so access can be governed and subscribed to.',
    to: 'plans',
    failed: (api) => plansOf(api).length === 0,
  },
  {
    id: 'sec-keyless-plan',
    category: 'security',
    severity: 'warn',
    message: 'At least one plan is keyless / public (no authentication).',
    fix: 'Prefer API key, JWT, OAuth2 or mTLS plans to protect your API.',
    to: 'plans',
    failed: (api) =>
      plansOf(api).some((p) => KEYLESS_TYPES.includes(p?.access_mode_configuration_type)),
  },
  {
    id: 'sec-no-rate-limiting',
    category: 'security',
    severity: 'info',
    message: 'No plan defines a rate-limiting / quota strategy.',
    fix: 'Configure a rate-limiting & quotas strategy on at least one plan.',
    to: 'plans',
    // Only relevant once plans exist — otherwise sec-no-plans already covers it.
    applies: (api) => plansOf(api).length > 0,
    failed: (api) => !plansOf(api).some((p) => Boolean(p?.rateLimiting && p.rateLimiting.strategy)),
  },
  {
    id: 'sec-no-https',
    category: 'security',
    severity: 'info',
    message: 'HTTPS is not enforced (no ForceHttpsTraffic plugin).',
    fix: 'Add the "Force HTTPS traffic" plugin to reject plain-text requests.',
    to: 'plugin-chains',
    failed: (api) => !hasPluginMatching(api, 'ForceHttpsTraffic'),
  },
  // ---- Routing completeness --------------------------------------------
  {
    id: 'route-none',
    category: 'routing',
    severity: 'error',
    message: 'The API has no endpoint.',
    fix: 'Define at least one endpoint to expose your API.',
    to: 'endpoints',
    failed: (api) => routesOf(api).length === 0,
  },
  {
    id: 'backend-no-targets',
    category: 'routing',
    severity: 'error',
    message: 'A backend has no target configured.',
    fix: 'Configure at least one target (hostname/port) on every backend.',
    to: 'backends',
    failed: (api) =>
      backendsOf(api).length === 0 ||
      backendsOf(api).some((b) => (b?.backend?.targets?.length || 0) === 0),
  },
  {
    id: 'backend-no-healthcheck',
    category: 'routing',
    severity: 'info',
    message: 'No backend has a health-check enabled.',
    fix: 'Enable health-checks so unhealthy targets are detected automatically.',
    to: 'backends',
    applies: (api) => backendsOf(api).length > 0,
    failed: (api) => !backendsOf(api).some((b) => b?.backend?.health_check?.enabled === true),
  },
  {
    id: 'route-all-methods',
    category: 'routing',
    severity: 'info',
    message: 'An endpoint accepts every HTTP method (no explicit methods).',
    fix: 'Restrict endpoints to the HTTP methods they actually support.',
    to: 'endpoints',
    applies: (api) => routesOf(api).length > 0,
    failed: (api) =>
      routesOf(api).some(
        (r) => ((r?.frontend?.methods || []).filter((m) => (m || '').length).length) === 0
      ),
  },
  {
    id: 'route-disabled',
    category: 'routing',
    severity: 'hint',
    message: 'An endpoint is disabled.',
    fix: 'Remove or re-enable disabled endpoints to keep the definition clean.',
    to: 'endpoints',
    applies: (api) => routesOf(api).length > 0,
    failed: (api) => routesOf(api).some((r) => r?.enabled === false),
  },
  // ---- Governance / metadata -------------------------------------------
  {
    id: 'gov-no-owner',
    category: 'governance',
    severity: 'warn',
    message: 'The API has no owner.',
    fix: 'Assign an owner so the API has a clear point of contact.',
    to: 'informations',
    failed: (api) => !api?.owner?.ref,
  },
  {
    id: 'gov-no-tags',
    category: 'governance',
    severity: 'info',
    message: 'The API has no tags.',
    fix: 'Add tags to make the API easier to find and classify.',
    to: 'informations',
    failed: (api) => (api?.tags?.length || 0) === 0,
  },
  {
    id: 'gov-no-metadata',
    category: 'governance',
    severity: 'hint',
    message: 'The API has no metadata.',
    fix: 'Add metadata (team, domain, …) to enrich governance and search.',
    to: 'informations',
    failed: (api) => Object.keys(api?.metadata || {}).length === 0,
  },
  {
    id: 'gov-version-semver',
    category: 'governance',
    severity: 'info',
    message: 'The API version is not semantic (x.y.z).',
    fix: 'Use semantic versioning (e.g. 1.0.0) for predictable lifecycle management.',
    to: 'informations',
    failed: (api) => !/^\d+\.\d+\.\d+$/.test(api?.version || ''),
  },
];

function ruleFailed(rule, api) {
  try {
    return !!rule.failed(api);
  } catch (e) {
    return false;
  }
}

function ruleApplies(rule, api) {
  if (!rule.applies) return true;
  try {
    return !!rule.applies(api);
  } catch (e) {
    return true;
  }
}

// Weighted completeness: passed-rule weight / total applicable-rule weight.
function scoreRules(rules, api) {
  let totalWeight = 0;
  let earnedWeight = 0;
  const counts = { error: 0, warn: 0, info: 0, hint: 0 };
  const issues = [];

  rules.forEach((rule) => {
    if (!ruleApplies(rule, api)) return;
    const weight = SEVERITY_WEIGHTS[rule.severity] || 0;
    totalWeight += weight;
    if (ruleFailed(rule, api)) {
      counts[rule.severity] = (counts[rule.severity] || 0) + 1;
      issues.push({
        id: rule.id,
        category: rule.category,
        categoryLabel: categoryLabel(rule.category),
        severity: rule.severity,
        message: rule.message,
        fix: rule.fix,
        to: rule.to,
      });
    } else {
      earnedWeight += weight;
    }
  });

  const percent = totalWeight === 0 ? 100 : Math.round((100 * earnedWeight) / totalWeight);
  return { percent, counts, issues };
}

export function computeApiQuality(api) {
  const global = scoreRules(RULES, api);
  const { grade, color } = gradeFor(global.percent);

  const categories = CATEGORIES.map(({ key, label }) => {
    const cat = scoreRules(
      RULES.filter((r) => r.category === key),
      api
    );
    const g = gradeFor(cat.percent);
    return {
      key,
      label,
      percent: cat.percent,
      grade: g.grade,
      color: g.color,
      counts: cat.counts,
      issues: cat.issues,
      total: RULES.filter((r) => r.category === key).length,
    };
  });

  return {
    percent: global.percent,
    grade,
    color,
    counts: global.counts,
    issues: global.issues,
    categories,
  };
}
