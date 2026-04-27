import { apisClient } from '../../services/BackOfficeServices';

// ============================================================================
// REST client — analytics queries & dashboards CRUD
// ============================================================================

const analyticsBase = '/bo/api/proxy/api/analytics';

const fetchJson = (url, opts = {}) =>
  fetch(url, {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(opts.body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });

export const runQuery = (payload, signal) =>
  fetchJson(`${analyticsBase}/_query`, { method: 'POST', body: payload, signal }).then((r) =>
    r.json().then((j) => ({ ok: r.ok, status: r.status, body: j }))
  );

export const fetchSchema = () =>
  fetchJson(`${analyticsBase}/_schema`).then((r) => r.json());

export const testConnection = (settings) =>
  fetchJson(`${analyticsBase}/_test-connection`, { method: 'POST', body: settings }).then((r) =>
    r.json()
  );

export const setActiveExporter = (id) =>
  fetchJson(`${analyticsBase}/_set-active-exporter/${id}`, { method: 'POST' }).then((r) => r.json());

export const migrateLegacy = (payload) =>
  fetchJson(`${analyticsBase}/_migrate`, { method: 'POST', body: payload }).then((r) => r.json());

export const restoreDefaults = () =>
  fetchJson(`${analyticsBase}/dashboards/_restore-defaults`, { method: 'POST' }).then((r) =>
    r.json()
  );

// CRUD dashboards goes through the generic Resource router.
export const dashboards = apisClient('analytics.otoroshi.io', 'v1', 'user-dashboards');

// CRUD alerts goes through the generic Resource router as well.
export const alerts = apisClient('analytics.otoroshi.io', 'v1', 'user-alerts');

// ============================================================================
// Time range parsing (`now`, `now-1h`, ISO 8601)
// ============================================================================

const RELATIVE_RE = /^now-(\d+)([smhd])$/;

export function parseTime(input, fallback = new Date()) {
  if (!input) return fallback;
  const s = String(input).trim();
  if (s === 'now') return new Date();
  const m = RELATIVE_RE.exec(s);
  if (m) {
    const num = parseInt(m[1], 10);
    const unit = m[2];
    const factor = unit === 's' ? 1 : unit === 'm' ? 60 : unit === 'h' ? 3600 : 86400;
    return new Date(Date.now() - num * factor * 1000);
  }
  const d = new Date(s);
  return isNaN(d.getTime()) ? fallback : d;
}

export function rangeToInstants({ from, to }) {
  const f = parseTime(from, new Date(Date.now() - 3600 * 1000));
  const t = parseTime(to, new Date());
  return { from: f, to: t };
}

export const PRESETS = [
  { label: 'Last 1h', from: 'now-1h', to: 'now' },
  { label: 'Last 6h', from: 'now-6h', to: 'now' },
  { label: 'Last 12h', from: 'now-12h', to: 'now' },
  { label: 'Last 24h', from: 'now-24h', to: 'now' },
  { label: 'Last 7d', from: 'now-7d', to: 'now' },
  { label: 'Last 30d', from: 'now-30d', to: 'now' },
];

// ============================================================================
// URL <-> state serialization
// ============================================================================

export function filtersToQuery(state) {
  const q = {};
  if (state.from) q.from = state.from;
  if (state.to) q.to = state.to;
  if (state.route) q.route = state.route;
  if (state.api) q.api = state.api;
  if (state.apikey) q.apikey = state.apikey;
  if (state.group) q.group = state.group;
  if (state.refresh != null) q.refresh = String(state.refresh);
  if (state.compare) q.compare = '1';
  return q;
}

export function queryToFilters(query) {
  return {
    from: query.from || 'now-1h',
    to: query.to || 'now',
    route: query.route || '',
    api: query.api || '',
    apikey: query.apikey || '',
    group: query.group || '',
    refresh: query.refresh != null ? Number(query.refresh) : 0,
    compare: query.compare === '1' || query.compare === 'true',
  };
}

export function buildFiltersPayload(filters) {
  const out = { from: filters.from, to: filters.to };
  if (filters.route) out.route_id = filters.route;
  if (filters.api) out.api_id = filters.api;
  if (filters.apikey) out.apikey_id = filters.apikey;
  if (filters.group) out.group_id = filters.group;
  return out;
}
