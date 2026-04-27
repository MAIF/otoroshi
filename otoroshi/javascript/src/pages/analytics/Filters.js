import React, { Component } from 'react';
import { nextClient } from '../../services/BackOfficeServices';
import { ReactSelectOverride } from '../../components/inputs/ReactSelectOverride';
import { parseTime } from './service';

function toLocalDateTime(input, fallback) {
  const d = parseTime(input, fallback);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function localDateTimeToIso(local) {
  if (!local) return '';
  const d = new Date(local);
  if (isNaN(d.getTime())) return '';
  return d.toISOString();
}

const TIME_PRESETS = [
  { label: '1h', from: 'now-1h', to: 'now' },
  { label: '6h', from: 'now-6h', to: 'now' },
  { label: '24h', from: 'now-24h', to: 'now' },
  { label: '7d', from: 'now-7d', to: 'now' },
  { label: '30d', from: 'now-30d', to: 'now' },
  { label: '90d', from: 'now-90d', to: 'now' },
];

const REFRESH_OPTIONS = [
  { label: '10s', value: 10 },
  { label: '30s', value: 30 },
  { label: '1m', value: 60 },
  { label: '5m', value: 300 },
];

export class Filters extends Component {
  state = {
    routes: [],
    apis: [],
    apikeys: [],
    groups: [],
    nowTick: 0,
  };

  componentDidMount() {
    this.loadOptions();
    // Re-render every second so "Xs ago" stays fresh.
    this.tickTimer = setInterval(() => this.setState({ nowTick: Date.now() }), 1000);
  }

  componentWillUnmount() {
    if (this.tickTimer) clearInterval(this.tickTimer);
  }

  loadOptions = () => {
    Promise.all([
      nextClient.forEntity(nextClient.ENTITIES.ROUTES).findAll().catch(() => []),
      nextClient
        .forEntityNextWithGroup('apis.otoroshi.io', nextClient.ENTITIES.APIS)
        .findAll()
        .catch(() => []),
      nextClient.forEntity(nextClient.ENTITIES.APIKEYS).findAll().catch(() => []),
      nextClient.forEntity(nextClient.ENTITIES.GROUPS).findAll().catch(() => []),
    ]).then(([routes, apis, apikeys, groups]) => {
      this.setState({
        routes: Array.isArray(routes) ? routes : [],
        apis: Array.isArray(apis) ? apis : [],
        apikeys: Array.isArray(apikeys) ? apikeys : [],
        groups: Array.isArray(groups) ? groups : [],
      });
    });
  };

  update = (patch) => {
    this.props.onChange({ ...this.props.value, ...patch });
  };

  selectPreset = (preset) => {
    this.update({ from: preset.from, to: preset.to });
  };

  toggleAutoRefresh = (active) => {
    const v = this.props.value || {};
    const last = v.refreshInterval || v.refresh || 30;
    if (active) {
      this.update({ refresh: last, refreshInterval: last });
    } else {
      this.update({ refresh: 0, refreshInterval: last });
    }
  };

  changeInterval = (value) => {
    const v = this.props.value || {};
    const wasActive = (v.refresh || 0) > 0;
    this.update({
      refreshInterval: value,
      refresh: wasActive ? value : 0,
    });
  };

  formatRelative(ts) {
    if (!ts) return '';
    const diff = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    let rel;
    if (diff < 60) rel = `${diff}s ago`;
    else if (diff < 3600) rel = `${Math.floor(diff / 60)}m ago`;
    else rel = `${Math.floor(diff / 3600)}h ago`;
    const d = new Date(ts);
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return `${rel} (${hh}:${mm}:${ss})`;
  }

  isActivePreset(preset) {
    const v = this.props.value || {};
    return v.from === preset.from && v.to === preset.to;
  }

  render() {
    const v = this.props.value || {};
    const { routes, apis, apikeys, groups } = this.state;
    const autoActive = (v.refresh || 0) > 0;
    const interval = v.refreshInterval || v.refresh || 30;

    return (
      <div
        className="user-analytics-filters"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          padding: 8,
          background: '#1a1a1a',
          border: '1px solid #2a2a2a',
          borderRadius: 4,
        }}
      >
        {/* Row 1: from/to datetime (left) | presets + last refresh + auto-refresh (right) */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Field label="From">
              <input
                type="datetime-local"
                className="form-control form-control-sm"
                value={toLocalDateTime(v.from, new Date(Date.now() - 3600 * 1000))}
                onChange={(e) => {
                  const iso = localDateTimeToIso(e.target.value);
                  if (iso) this.update({ from: iso });
                }}
                style={{ width: 200 }}
              />
            </Field>
            <Field label="To">
              <input
                type="datetime-local"
                className="form-control form-control-sm"
                value={toLocalDateTime(v.to, new Date())}
                onChange={(e) => {
                  const iso = localDateTimeToIso(e.target.value);
                  if (iso) this.update({ to: iso });
                }}
                style={{ width: 200 }}
              />
            </Field>
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              marginLeft: 'auto',
              flexWrap: 'wrap',
            }}
          >
            <div className="btn-group btn-group-sm" role="group">
              {TIME_PRESETS.map((p) => {
                const active = this.isActivePreset(p);
                return (
                  <button
                    key={p.label}
                    type="button"
                    className={`btn btn-sm ${active ? 'btn-primary' : 'btn-outline-secondary'}`}
                    onClick={() => this.selectPreset(p)}
                  >
                    {p.label}
                  </button>
                );
              })}
            </div>

            <span style={{ color: '#888', fontSize: 12, fontFamily: 'monospace', minWidth: 160 }}>
              {this.props.lastRefreshAt ? this.formatRelative(this.props.lastRefreshAt) : '—'}
            </span>

            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={this.props.onRefresh}
              title="Refresh now"
            >
              <i className="fas fa-sync" />
            </button>

            <label
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                color: '#ddd',
                fontSize: 12,
                marginBottom: 0,
                cursor: 'pointer',
              }}
              title="Toggle auto-refresh"
            >
              <input
                type="checkbox"
                checked={autoActive}
                onChange={(e) => this.toggleAutoRefresh(e.target.checked)}
                style={{ cursor: 'pointer' }}
              />
              Auto
            </label>

            <select
              className="form-control form-control-sm"
              value={interval}
              onChange={(e) => this.changeInterval(Number(e.target.value))}
              style={{ width: 80 }}
              disabled={!autoActive}
            >
              {REFRESH_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Row 2: entity filters + compare + copy link */}
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
          <Selector
            label="Route"
            value={v.route}
            options={routes}
            onChange={(val) => this.update({ route: val })}
          />
          <Selector
            label="API"
            value={v.api}
            options={apis}
            onChange={(val) => this.update({ api: val })}
          />
          <Selector
            label="API key"
            value={v.apikey}
            options={apikeys}
            idKey="clientId"
            labelKey="clientName"
            onChange={(val) => this.update({ apikey: val })}
          />
          <Selector
            label="Group"
            value={v.group}
            options={groups}
            onChange={(val) => this.update({ group: val })}
          />

          <Field label="Compare">
            <input
              type="checkbox"
              checked={!!v.compare}
              onChange={(e) => this.update({ compare: e.target.checked })}
              style={{ marginTop: 6 }}
            />
          </Field>

          <button
            type="button"
            className="btn btn-sm btn-secondary"
            onClick={() => {
              try {
                navigator.clipboard.writeText(window.location.href);
              } catch (_) {}
            }}
            title="Copy permalink"
            style={{ marginLeft: 'auto' }}
          >
            <i className="fas fa-link" /> Copy link
          </button>
        </div>
      </div>
    );
  }
}

function Field({ label, children }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', fontSize: 11, color: '#aaa' }}>
      <span style={{ marginBottom: 2 }}>{label}</span>
      {children}
    </label>
  );
}

function Selector({ label, value, options, onChange, idKey = 'id', labelKey = 'name' }) {
  const opts = (options || []).map((o) => ({
    value: o[idKey],
    label: o[labelKey] || o[idKey],
  }));
  return (
    <Field label={label}>
      <div style={{ width: 220 }}>
        <ReactSelectOverride
          isClearable
          placeholder="— any —"
          value={value || ''}
          options={opts}
          onChange={(val) => onChange(val || '')}
        />
      </div>
    </Field>
  );
}
