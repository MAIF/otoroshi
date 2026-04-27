import React, { Component } from 'react';
import { nextClient } from '../../services/BackOfficeServices';
import { PRESETS } from './service';

const REFRESH_OPTIONS = [
  { label: 'Off', value: 0 },
  { label: '10 s', value: 10 },
  { label: '30 s', value: 30 },
  { label: '1 min', value: 60 },
  { label: '5 min', value: 300 },
];

export class Filters extends Component {
  state = {
    routes: [],
    apis: [],
    apikeys: [],
    groups: [],
  };

  componentDidMount() {
    this.loadOptions();
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

  onPreset = (e) => {
    const preset = PRESETS.find((p) => p.label === e.target.value);
    if (preset) this.update({ from: preset.from, to: preset.to });
  };

  copyLink = () => {
    try {
      navigator.clipboard.writeText(window.location.href);
    } catch (_) {}
  };

  render() {
    const v = this.props.value || {};
    const { routes, apis, apikeys, groups } = this.state;
    return (
      <div
        className="user-analytics-filters"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
          alignItems: 'flex-end',
          padding: 8,
          background: '#1a1a1a',
          border: '1px solid #2a2a2a',
          borderRadius: 4,
        }}
      >
        <Field label="Range">
          <select className="form-control form-control-sm" onChange={this.onPreset} value="">
            <option value="">Custom…</option>
            {PRESETS.map((p) => (
              <option key={p.label} value={p.label}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="From">
          <input
            type="text"
            className="form-control form-control-sm"
            value={v.from || ''}
            placeholder="now-1h or ISO"
            onChange={(e) => this.update({ from: e.target.value })}
            style={{ width: 150 }}
          />
        </Field>
        <Field label="To">
          <input
            type="text"
            className="form-control form-control-sm"
            value={v.to || ''}
            placeholder="now or ISO"
            onChange={(e) => this.update({ to: e.target.value })}
            style={{ width: 150 }}
          />
        </Field>

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

        <Field label="Refresh">
          <select
            className="form-control form-control-sm"
            value={v.refresh || 0}
            onChange={(e) => this.update({ refresh: Number(e.target.value) })}
          >
            {REFRESH_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </Field>

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
          onClick={this.copyLink}
          title="Copy permalink"
          style={{ marginLeft: 'auto' }}
        >
          <i className="fas fa-link" /> Copy link
        </button>
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
  return (
    <Field label={label}>
      <select
        className="form-control form-control-sm"
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        style={{ width: 180 }}
      >
        <option value="">— any —</option>
        {options.map((o) => (
          <option key={o[idKey]} value={o[idKey]}>
            {o[labelKey] || o[idKey]}
          </option>
        ))}
      </select>
    </Field>
  );
}
