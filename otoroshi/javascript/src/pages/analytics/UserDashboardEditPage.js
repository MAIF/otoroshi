import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import { dashboards, fetchSchema } from './service';

const NEW_DASHBOARD = {
  name: 'New dashboard',
  description: '',
  enabled: true,
  tags: [],
  metadata: {},
  widgets: [
    {
      id: 'w1',
      title: 'Requests per second',
      query: 'requests_per_second',
      type: 'line',
      width: 4,
      height: 2,
      params: {},
      options: { format: 'rps' },
    },
  ],
};

export class UserDashboardEditPage extends Component {
  state = {
    dashboard: null,
    loading: true,
    saving: false,
    error: null,
    schema: null,
    mode: 'edit',
  };

  componentDidMount() {
    const titem = this.props.params.titem;
    const isAdd = !titem || this.props.params.taction === 'add';
    this.props.setTitle(isAdd ? 'New dashboard' : 'Edit dashboard');
    this.props.setSidebarContent(null);
    this.setState({ mode: isAdd ? 'add' : 'edit' });
    fetchSchema().then((s) => this.setState({ schema: s })).catch(() => {});
    if (isAdd) {
      this.setState({ dashboard: { ...NEW_DASHBOARD }, loading: false });
    } else {
      this.loadDashboard(titem);
    }
  }

  loadDashboard = (id) => {
    this.setState({ loading: true });
    dashboards
      .findById(id)
      .then((d) => {
        if (!d || d.error) {
          this.setState({ error: (d && d.error) || 'not found', loading: false });
        } else {
          this.setState({ dashboard: d, loading: false, error: null });
        }
      })
      .catch((e) => this.setState({ error: e.message, loading: false }));
  };

  save = () => {
    const { dashboard, mode } = this.state;
    if (!dashboard) return;
    this.setState({ saving: true, error: null });
    const op = mode === 'add' ? dashboards.create(dashboard) : dashboards.update(dashboard);
    op.then((res) => {
      if (res && res.error) {
        this.setState({ saving: false, error: res.error });
        return;
      }
      this.setState({ saving: false });
      this.props.history.push(`/user-dashboards/${res.id || dashboard.id}`);
    }).catch((e) => this.setState({ saving: false, error: e.message }));
  };

  doDelete = () => {
    const { dashboard } = this.state;
    if (!dashboard) return;
    if (!window.confirm(`Delete dashboard "${dashboard.name}"?`)) return;
    dashboards.delete(dashboard).then(() => {
      this.props.history.push('/user-dashboards');
    });
  };

  renderSchemaHelp() {
    const { schema } = this.state;
    if (!schema) return null;
    const queries = schema.queries || [];
    const widgets = schema.widget_types || [];
    return (
      <details style={{ marginTop: 12, color: '#aaa' }}>
        <summary style={{ cursor: 'pointer' }}>
          Available queries ({queries.length}) and widget types ({widgets.length})
        </summary>
        <div style={{ display: 'flex', gap: 24, marginTop: 12 }}>
          <div>
            <strong style={{ color: '#fff' }}>Queries</strong>
            <ul style={{ marginTop: 4 }}>
              {queries.map((q) => (
                <li key={q.id}>
                  <code>{q.id}</code> <small>({q.shape})</small> — {q.name}
                </li>
              ))}
            </ul>
          </div>
          <div>
            <strong style={{ color: '#fff' }}>Widget types</strong>
            <ul style={{ marginTop: 4 }}>
              {widgets.map((w) => (
                <li key={w}>
                  <code>{w}</code>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </details>
    );
  }

  render() {
    const { dashboard, loading, saving, error, mode } = this.state;
    if (loading) {
      return (
        <div style={{ padding: 16, color: '#888' }}>
          <i className="fas fa-spinner fa-spin" /> Loading…
        </div>
      );
    }
    if (!dashboard) {
      return (
        <div style={{ padding: 16 }}>
          <div className="alert alert-warning">{error || 'Dashboard not found'}</div>
          <Link to="/user-dashboards" className="btn btn-secondary">
            Back to dashboards
          </Link>
        </div>
      );
    }
    return (
      <div style={{ padding: '0 8px' }}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 8,
          }}
        >
          <div>
            <h3 style={{ marginBottom: 0 }}>
              {mode === 'add' ? 'New dashboard' : `Edit — ${dashboard.name}`}
            </h3>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            {mode === 'edit' && (
              <button type="button" className="btn btn-danger" onClick={this.doDelete}>
                <i className="fas fa-trash" /> Delete
              </button>
            )}
            <Link to="/user-dashboards" className="btn btn-secondary">
              Cancel
            </Link>
            <button type="button" className="btn btn-primary" onClick={this.save} disabled={saving}>
              {saving ? <><i className="fas fa-spinner fa-spin" /> Saving…</> : <><i className="fas fa-save" /> Save</>}
            </button>
          </div>
        </div>

        {error && <div className="alert alert-danger">{error}</div>}

        <JsonObjectAsCodeInput
          label="Dashboard JSON"
          value={dashboard}
          onChange={(d) => this.setState({ dashboard: d })}
          height="500px"
        />

        {this.renderSchemaHelp()}
      </div>
    );
  }
}
