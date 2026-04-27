import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { dashboards, restoreDefaults } from './service';

export class UserDashboardsPage extends Component {
  state = {
    items: [],
    loading: true,
    error: null,
    exporters: [],
    activeExporterId: null,
    restoreInProgress: false,
    restoreResult: null,
  };

  componentDidMount() {
    this.props.setTitle('User analytics dashboards');
    this.props.setSidebarContent(null);
    this.refresh();
  }

  refresh = () => {
    this.setState({ loading: true });
    Promise.all([
      dashboards.findAll().catch(() => []),
      BackOfficeServices.findAllDataExporterConfigs({ page: 1, pageSize: 1000 }).catch(() => null),
    ]).then(([items, exportersResp]) => {
      const exporters = Array.isArray(exportersResp)
        ? exportersResp
        : (exportersResp && exportersResp.data) || [];
      const analyticsExporters = exporters.filter((e) => e.type === 'user-analytics');
      const active = analyticsExporters.find(
        (e) => e.metadata && e.metadata['otoroshi:user-analytics:active'] === 'true'
      );
      this.setState({
        items: Array.isArray(items) ? items : [],
        exporters: analyticsExporters,
        activeExporterId: active ? active.id : null,
        loading: false,
        error: null,
      });
    });
  };

  doRestoreDefaults = () => {
    if (this.state.restoreInProgress) return;
    if (!window.confirm('Recreate any missing default dashboards?')) return;
    this.setState({ restoreInProgress: true, restoreResult: null });
    restoreDefaults()
      .then((res) => {
        this.setState({ restoreInProgress: false, restoreResult: res });
        this.refresh();
      })
      .catch((e) => this.setState({ restoreInProgress: false, restoreResult: { error: e.message } }));
  };

  doDelete = (item) => {
    if (!window.confirm(`Delete dashboard "${item.name}"?`)) return;
    dashboards.delete(item).then(() => this.refresh());
  };

  renderOnboarding() {
    const { exporters } = this.state;
    const hasExporters = exporters.length > 0;
    return (
      <div style={{ padding: 24, textAlign: 'center', maxWidth: 720, margin: '40px auto' }}>
        <h3>Configure user analytics</h3>
        <p style={{ color: '#aaa', marginBottom: 24 }}>
          {hasExporters
            ? 'A user-analytics exporter is configured but none is marked active. Pick one to start collecting analytics.'
            : 'No user-analytics PostgreSQL exporter is configured yet. Create one to start collecting analytics.'}
        </p>
        {hasExporters ? (
          <div className="alert alert-info" style={{ textAlign: 'left' }}>
            Go to <Link to="/exporters">Data Exporters</Link>, open one of the{' '}
            <code>user-analytics</code> exporters, and click <strong>Set as active analytics exporter</strong>.
          </div>
        ) : (
          <Link to="/exporters/add" className="btn btn-primary btn-lg">
            <i className="fas fa-plus" /> Create a user-analytics exporter
          </Link>
        )}
      </div>
    );
  }

  render() {
    const { items, loading, error, activeExporterId, restoreInProgress, restoreResult } = this.state;

    if (loading) {
      return (
        <div style={{ padding: 16, color: '#888' }}>
          <i className="fas fa-spinner fa-spin" /> Loading…
        </div>
      );
    }

    if (!activeExporterId) {
      return this.renderOnboarding();
    }

    return (
      <div style={{ padding: '0 8px' }}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <div>
            <h3 style={{ marginBottom: 0 }}>User analytics dashboards</h3>
            <small style={{ color: '#888' }}>
              Active analytics exporter: <code>{activeExporterId}</code>
            </small>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={this.doRestoreDefaults}
              disabled={restoreInProgress}
            >
              {restoreInProgress ? (
                <><i className="fas fa-spinner fa-spin" /> Restoring…</>
              ) : (
                <><i className="fas fa-undo" /> Restore default dashboards</>
              )}
            </button>
            <Link to="/user-dashboards/add" className="btn btn-primary">
              <i className="fas fa-plus" /> New dashboard
            </Link>
          </div>
        </div>

        {restoreResult && restoreResult.created && restoreResult.created.length > 0 && (
          <div className="alert alert-success">
            Restored {restoreResult.count} default dashboard(s): {restoreResult.created.join(', ')}
          </div>
        )}
        {restoreResult && restoreResult.error && (
          <div className="alert alert-danger">Restore failed: {restoreResult.error}</div>
        )}
        {error && <div className="alert alert-danger">{error}</div>}

        <table className="table table-sm" style={{ color: '#ddd' }}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Widgets</th>
              <th>Default?</th>
              <th style={{ textAlign: 'right' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 && (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', color: '#666', padding: 24 }}>
                  No dashboards yet.{' '}
                  <button
                    type="button"
                    className="btn btn-link p-0"
                    onClick={this.doRestoreDefaults}
                  >
                    Restore default dashboards
                  </button>
                </td>
              </tr>
            )}
            {items.map((d) => {
              const isDefault = d.metadata && d.metadata['otoroshi-default-id'];
              return (
                <tr key={d.id}>
                  <td>
                    <Link to={`/user-dashboards/${d.id}`}>{d.name || d.id}</Link>
                  </td>
                  <td style={{ color: '#888' }}>{d.description || ''}</td>
                  <td>{(d.widgets || []).length}</td>
                  <td>
                    {isDefault ? <span className="badge badge-info">{isDefault}</span> : ''}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <Link to={`/user-dashboards/${d.id}`} className="btn btn-sm btn-secondary mr-1">
                      <i className="fas fa-chart-line" /> View
                    </Link>
                    <Link to={`/user-dashboards/edit/${d.id}`} className="btn btn-sm btn-secondary mr-1">
                      <i className="fas fa-edit" /> Edit
                    </Link>
                    <button
                      type="button"
                      className="btn btn-sm btn-danger"
                      onClick={() => this.doDelete(d)}
                    >
                      <i className="fas fa-trash" />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }
}
