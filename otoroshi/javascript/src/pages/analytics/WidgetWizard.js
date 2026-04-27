import React, { Component } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { ReactSelectOverride } from '../../components/inputs/ReactSelectOverride';
import { fetchSchema } from './service';

const FORMAT_OPTIONS = [
  { value: 'count', label: 'count' },
  { value: 'rps', label: 'requests/sec' },
  { value: 'ms', label: 'milliseconds' },
  { value: 'bytes', label: 'bytes' },
  { value: 'percent', label: 'percent (0–1)' },
];

const SHAPE_TO_TYPES = {
  timeseries: ['line', 'area'],
  topN: ['bar', 'pie', 'donut', 'table'],
  pie: ['pie', 'donut', 'bar'],
  scalar: ['scalar', 'metric'],
  metric: ['metric', 'scalar'],
  table: ['table'],
  heatmap: ['heatmap'],
};

const WIDGET_TYPE_LABELS = {
  line: 'Line',
  area: 'Area (filled)',
  bar: 'Bar',
  pie: 'Pie',
  donut: 'Donut',
  scalar: 'Big number',
  metric: 'Metric (label + value)',
  table: 'Table',
  heatmap: 'Heatmap',
};

function inferDefaultFormat(queryId) {
  if (queryId === 'requests_per_second') return 'rps';
  if (queryId === 'error_rate_ts') return 'percent';
  if (queryId === 'traffic_in_out_ts') return 'bytes';
  if (queryId.includes('duration') || queryId.includes('overhead')) return 'ms';
  return 'count';
}

function uid(prefix = 'widget') {
  return `${prefix}_${uuidv4()}`;
}

export class WidgetWizard extends Component {
  constructor(props) {
    super(props);
    const init = props.initial || {};
    this.state = {
      schema: null,
      loading: true,
      widgetId: init.id || null,
      title: init.title || '',
      queryId: init.query || '',
      type: init.type || 'line',
      width: init.width || 4,
      height: init.height || 2,
      format: (init.options && init.options.format) || 'count',
      paramValues: { ...(init.params || {}) },
    };
  }

  componentDidMount() {
    fetchSchema()
      .then((schema) => {
        this.setState({ schema, loading: false });
        this.bubble(this.state);
      })
      .catch(() => this.setState({ loading: false }));
    // Initial bubble (in case the user clicks OK with defaults)
    this.bubble(this.state);
  }

  bubble = (s = this.state) => {
    if (!this.props.onChange) return;
    const widget = {
      id: s.widgetId || uid(),
      title: s.title || '',
      query: s.queryId || '',
      type: s.type || 'line',
      width: s.width,
      height: s.height,
      params: { ...(s.paramValues || {}) },
      options: { format: s.format || 'count' },
    };
    this.props.onChange(widget);
  };

  set = (patch) => {
    this.setState(
      (s) => ({ ...s, ...patch }),
      () => this.bubble()
    );
  };

  selectQuery = (queryId) => {
    if (queryId === this.state.queryId) return;
    const q = this.findQuery(queryId);
    if (!q) {
      this.set({ queryId });
      return;
    }
    const allowedTypes = SHAPE_TO_TYPES[q.shape] || ['line'];
    const newType = allowedTypes.includes(this.state.type)
      ? this.state.type
      : q.default_widget || allowedTypes[0];
    const paramValues = {};
    (q.params || []).forEach((p) => {
      paramValues[p.name] = p.default;
    });
    this.set({
      queryId,
      type: newType,
      title: this.state.title || q.name || '',
      format: inferDefaultFormat(queryId),
      paramValues,
    });
  };

  findQuery(id) {
    const queries = (this.state.schema && this.state.schema.queries) || [];
    return queries.find((q) => q.id === id);
  }

  render() {
    const { schema, loading, title, queryId, type, width, height, format, paramValues } = this.state;
    if (loading) {
      return (
        <div style={{ padding: 24, color: 'var(--text-muted)' }}>
          <i className="fas fa-spinner fa-spin" /> Loading query catalogue…
        </div>
      );
    }
    const queries = (schema && schema.queries) || [];
    const queryOpts = queries.map((q) => ({
      value: q.id,
      label: `${q.name} — ${q.id} (${q.shape})`,
    }));
    const selectedQuery = this.findQuery(queryId);
    const allowedTypes = selectedQuery ? SHAPE_TO_TYPES[selectedQuery.shape] || ['line'] : [];
    const typeOpts = allowedTypes.map((t) => ({ value: t, label: WIDGET_TYPE_LABELS[t] || t }));

    return (
      <div style={{ padding: '12px 0', minWidth: 600 }}>
        <Field label="Title">
          <input
            type="text"
            className="form-control"
            value={title}
            placeholder="(uses query name if empty)"
            onChange={(e) => this.set({ title: e.target.value })}
          />
        </Field>

        <Field label="Query">
          <ReactSelectOverride
            placeholder="Select a query…"
            value={queryId || ''}
            options={queryOpts}
            onChange={(val) => this.selectQuery(val || '')}
          />
          {selectedQuery && selectedQuery.description && (
            <small style={{ color: 'var(--text-muted)', display: 'block', marginTop: 4 }}>
              {selectedQuery.description}
            </small>
          )}
        </Field>

        {selectedQuery && (
          <>
            <Field label="Widget type">
              <ReactSelectOverride
                value={type}
                options={typeOpts}
                onChange={(val) => this.set({ type: val || 'line' })}
              />
            </Field>

            <Field label="Width (columns)">
              <select
                className="form-control"
                value={width}
                onChange={(e) => this.set({ width: Number(e.target.value) })}
              >
                {[1, 2, 3, 4].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Height (rows)">
              <select
                className="form-control"
                value={height}
                onChange={(e) => this.set({ height: Number(e.target.value) })}
              >
                {[1, 2, 3, 4, 5, 6].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Value format">
              <ReactSelectOverride
                value={format}
                options={FORMAT_OPTIONS}
                onChange={(val) => this.set({ format: val || 'count' })}
              />
            </Field>

            {(selectedQuery.params || []).map((p) => (
              <Field key={p.name} label={`${p.name} (${p.kind})`} hint={p.description}>
                {p.kind === 'int' || p.kind === 'number' ? (
                  <input
                    type="number"
                    className="form-control"
                    value={paramValues[p.name] != null ? paramValues[p.name] : ''}
                    onChange={(e) =>
                      this.set({
                        paramValues: { ...paramValues, [p.name]: Number(e.target.value) },
                      })
                    }
                  />
                ) : (
                  <input
                    type="text"
                    className="form-control"
                    value={paramValues[p.name] != null ? paramValues[p.name] : ''}
                    onChange={(e) =>
                      this.set({
                        paramValues: { ...paramValues, [p.name]: e.target.value },
                      })
                    }
                  />
                )}
              </Field>
            ))}
          </>
        )}
      </div>
    );
  }
}

function Field({ label, hint, children }) {
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={{ display: 'block', color: 'var(--text)', fontSize: 13, marginBottom: 4 }}>
        {label}
      </label>
      {children}
      {hint && <small style={{ color: 'var(--text-muted)', display: 'block', marginTop: 2 }}>{hint}</small>}
    </div>
  );
}
