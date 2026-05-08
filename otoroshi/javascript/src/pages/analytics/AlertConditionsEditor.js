import React, { Component } from 'react';
import { ReactSelectOverride } from '../../components/inputs/ReactSelectOverride';
import { MonacoInput } from '../../components/inputs/MonacoInput';
import { nextClient } from '../../services/BackOfficeServices';
import { fetchSchema } from './service';

const REDUCER_OPTIONS = [
  { value: 'avg', label: 'avg' },
  { value: 'max', label: 'max' },
  { value: 'min', label: 'min' },
  { value: 'sum', label: 'sum' },
  { value: 'last', label: 'last' },
];

const OPERATOR_OPTIONS = [
  { value: '>', label: '>' },
  { value: '>=', label: '≥' },
  { value: '<', label: '<' },
  { value: '<=', label: '≤' },
  { value: '==', label: '==' },
  { value: '!=', label: '≠' },
];

const EMPTY_CONDITION = {
  query: 'error_rate_ts',
  params: {},
  filters: {},
  reducer: 'max',
  operator: '>',
  threshold: 0.05,
};

export class AlertConditionsEditor extends Component {
  state = {
    mode: 'visual', // 'visual' | 'json'
    queries: [],
    routes: [],
    apis: [],
    apikeys: [],
    groups: [],
    schemaLoaded: false,
  };

  componentDidMount() {
    this.loadCatalog();
    this.loadEntities();
  }

  loadCatalog = () => {
    fetchSchema()
      .then((s) => {
        const qs = (s && s.queries) || [];
        this.setState({ queries: qs, schemaLoaded: true });
      })
      .catch(() => this.setState({ schemaLoaded: true }));
  };

  loadEntities = () => {
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

  // ---- helpers -----------------------------------------------------------

  conditions() {
    const v = this.props.value;
    return Array.isArray(v) ? v : [];
  }

  updateCondition = (idx, patch) => {
    const next = [...this.conditions()];
    next[idx] = { ...next[idx], ...patch };
    this.props.onChange(next);
  };

  updateFilters = (idx, patch) => {
    const next = [...this.conditions()];
    const cur = next[idx] || {};
    const cleaned = { ...(cur.filters || {}), ...patch };
    Object.keys(cleaned).forEach((k) => {
      if (cleaned[k] === '' || cleaned[k] == null) delete cleaned[k];
    });
    next[idx] = { ...cur, filters: cleaned };
    this.props.onChange(next);
  };

  add = () => {
    this.props.onChange([...this.conditions(), { ...EMPTY_CONDITION }]);
  };

  remove = (idx) => {
    const next = this.conditions().filter((_, i) => i !== idx);
    this.props.onChange(next);
  };

  // ---- subcomponents -----------------------------------------------------

  renderQuerySelect(cond, idx) {
    const opts = this.state.queries.map((q) => ({
      value: q.id,
      label: `${q.name} — ${q.id} (${q.shape})`,
    }));
    return (
      <Field label="Query" wide>
        <ReactSelectOverride
          placeholder="Select a query…"
          value={cond.query || ''}
          options={opts}
          onChange={(val) => this.updateCondition(idx, { query: val || '' })}
        />
      </Field>
    );
  }

  renderFilters(cond, idx) {
    const f = cond.filters || {};
    const routeOpts = this.state.routes.map((r) => ({ value: r.id, label: r.name || r.id }));
    const apiOpts = this.state.apis.map((a) => ({ value: a.id, label: a.name || a.id }));
    const apikeyOpts = this.state.apikeys.map((a) => ({
      value: a.clientId,
      label: a.clientName || a.clientId,
    }));
    const groupOpts = this.state.groups.map((g) => ({ value: g.id, label: g.name || g.id }));
    return (
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <Field label="Route">
          <div style={{ width: 200 }}>
            <ReactSelectOverride
              isClearable
              placeholder="any"
              value={f.route_id || ''}
              options={routeOpts}
              onChange={(val) => this.updateFilters(idx, { route_id: val || '' })}
            />
          </div>
        </Field>
        <Field label="API">
          <div style={{ width: 200 }}>
            <ReactSelectOverride
              isClearable
              placeholder="any"
              value={f.api_id || ''}
              options={apiOpts}
              onChange={(val) => this.updateFilters(idx, { api_id: val || '' })}
            />
          </div>
        </Field>
        <Field label="API key">
          <div style={{ width: 200 }}>
            <ReactSelectOverride
              isClearable
              placeholder="any"
              value={f.apikey_id || ''}
              options={apikeyOpts}
              onChange={(val) => this.updateFilters(idx, { apikey_id: val || '' })}
            />
          </div>
        </Field>
        <Field label="Group">
          <div style={{ width: 200 }}>
            <ReactSelectOverride
              isClearable
              placeholder="any"
              value={f.group_id || ''}
              options={groupOpts}
              onChange={(val) => this.updateFilters(idx, { group_id: val || '' })}
            />
          </div>
        </Field>
        <Field label="Errors only">
          <select
            className="form-control form-control-sm"
            value={f.err == null ? '' : String(f.err)}
            onChange={(e) => {
              const v = e.target.value;
              this.updateFilters(idx, { err: v === '' ? undefined : v === 'true' });
            }}
            style={{ width: 110 }}
          >
            <option value="">no filter</option>
            <option value="true">errors only</option>
            <option value="false">success only</option>
          </select>
        </Field>
      </div>
    );
  }

  renderRule(cond, idx) {
    const queryDef = this.state.queries.find((q) => q.id === cond.query);
    const showTopN = queryDef && (queryDef.params || []).some((p) => p.name === 'top_n');
    return (
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <Field label="Reducer">
          <div style={{ width: 110 }}>
            <ReactSelectOverride
              value={cond.reducer || 'avg'}
              options={REDUCER_OPTIONS}
              onChange={(val) => this.updateCondition(idx, { reducer: val || 'avg' })}
            />
          </div>
        </Field>
        <Field label="Operator">
          <div style={{ width: 90 }}>
            <ReactSelectOverride
              value={cond.operator || '>'}
              options={OPERATOR_OPTIONS}
              onChange={(val) => this.updateCondition(idx, { operator: val || '>' })}
            />
          </div>
        </Field>
        <Field label="Threshold">
          <input
            type="number"
            className="form-control form-control-sm"
            value={cond.threshold == null ? '' : cond.threshold}
            onChange={(e) =>
              this.updateCondition(idx, { threshold: parseFloat(e.target.value) || 0 })
            }
            style={{ width: 130 }}
          />
        </Field>
        {showTopN && (
          <Field label="top_n">
            <input
              type="number"
              className="form-control form-control-sm"
              value={(cond.params && cond.params.top_n) || 10}
              onChange={(e) =>
                this.updateCondition(idx, {
                  params: { ...(cond.params || {}), top_n: parseInt(e.target.value, 10) || 10 },
                })
              }
              style={{ width: 90 }}
            />
          </Field>
        )}
      </div>
    );
  }

  renderConditionCard(cond, idx) {
    return (
      <div
        key={idx}
        style={{
          background: 'var(--bg-color_level2)',
          border: '1px solid var(--border-color)',
          borderRadius: 4,
          padding: 12,
          marginBottom: 12,
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 8,
          }}
        >
          <strong style={{ color: 'var(--text)' }}>Condition #{idx + 1}</strong>
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => this.remove(idx)}
            title="Remove this condition"
          >
            <i className="fas fa-trash" />
          </button>
        </div>
        {this.renderQuerySelect(cond, idx)}
        {this.renderRule(cond, idx)}
        <div style={{ marginTop: 8 }}>
          <small style={{ color: 'var(--text-muted)' }}>Filters (optional)</small>
          {this.renderFilters(cond, idx)}
        </div>
      </div>
    );
  }

  // ---- render ------------------------------------------------------------

  render() {
    const conditions = this.conditions();
    const { mode, schemaLoaded } = this.state;

    return (
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label">Conditions</label>
        <div className="col-sm-10">
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: 8,
            }}
          >
            <small style={{ color: 'var(--text-muted)' }}>
              Each condition reduces an analytics query to a single number, then compares it to a
              threshold. Conditions are combined with the AND/OR set above.
            </small>
            <div className="btn-group btn-group-sm">
              <button
                type="button"
                className={`btn btn-sm ${mode === 'visual' ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => this.setState({ mode: 'visual' })}
              >
                Visual
              </button>
              <button
                type="button"
                className={`btn btn-sm ${mode === 'json' ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => this.setState({ mode: 'json' })}
              >
                JSON
              </button>
            </div>
          </div>

          {mode === 'visual' && (
            <div>
              {!schemaLoaded && (
                <div style={{ color: 'var(--text-muted)', padding: 12 }}>
                  <i className="fas fa-spinner fa-spin" /> Loading query catalogue…
                </div>
              )}
              {schemaLoaded &&
                conditions.length === 0 && (
                  <div
                    style={{
                      color: 'var(--text-muted)',
                      padding: 24,
                      textAlign: 'center',
                      border: '1px dashed var(--border-color)',
                      borderRadius: 4,
                      marginBottom: 8,
                    }}
                  >
                    No condition yet — add one below.
                  </div>
                )}
              {schemaLoaded && conditions.map((c, i) => this.renderConditionCard(c, i))}
              <button
                type="button"
                className="btn btn-sm btn-success"
                onClick={this.add}
                disabled={!schemaLoaded}
              >
                <i className="fas fa-plus-circle" /> Add condition
              </button>
            </div>
          )}

          {mode === 'json' && (
            <MonacoInput
              value={JSON.stringify(conditions, null, 2)}
              mode="json"
              height="400px"
              onChange={(v) => {
                try {
                  const parsed = JSON.parse(v);
                  this.props.onChange(Array.isArray(parsed) ? parsed : []);
                } catch (e) {}
              }}
            />
          )}
        </div>
      </div>
    );
  }
}

function Field({ label, wide, children }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', marginBottom: wide ? 8 : 0 }}>
      <small style={{ color: 'var(--text-muted)', marginBottom: 2 }}>{label}</small>
      {children}
    </div>
  );
}
