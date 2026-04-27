import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { dashboards, restoreDefaults } from './service';

const NEW_DASHBOARD_TEMPLATE = () => ({
  _loc: { tenant: 'default', teams: ['default'] },
  id: '',
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
});

function View({ rawValue }) {
  return (
    <div className="row mb-3">
      <label className="col-xs-12 col-sm-2 col-form-label"></label>
      <div className="col-sm-10">
        <Link className="btn btn-secondary btn-sm" href={`/user-dashboards/show/${rawValue.id}`}>
          <i className="fas fa-chart-line"></i> view dashboard
        </Link>
      </div>
    </div>
  )
}

export class UserDashboardsPage extends Component {
  state = {
    activeExporterId: null,
    exporters: [],
    loading: true,
    restoreInProgress: false,
  };

  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My dashboard' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'What this dashboard is about' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    widgets: {
      type: 'monaco-json',
      props: {
        label: 'Widgets',
        height: '500px',
        help:
          'JSON array of widgets. Each widget needs: id, title, query, type (line/area/bar/pie/donut/scalar/metric/table/heatmap), width (1-4), height (rows). See /api/analytics/_schema for available queries.',
      },
    },
    view: {
      type: View,

    }
  };

  formFlow = ['_loc', 'id', 'name', 'description', '<<< Widgets', 'enabled', 'widgets', 'view', '>>> Tags & Metadata','tags', 'metadata', ];

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description || '',
    },
    {
      title: 'Widgets',
      filterId: 'widgets',
      style: { textAlign: 'center', width: 90 },
      content: (item) => (item.widgets || []).length,
    },
    {
      title: 'Default',
      filterId: 'default',
      style: { width: 220 },
      content: (item) => (item.metadata && item.metadata['otoroshi-default-id']) || '',
      cell: (v) =>
        v ? <span className="badge bg-info">{v}</span> : <span style={{ color: '#666' }}>—</span>,
    },
    {
      title: 'View',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      content: (item) => item.id,
      cell: (_v, item) => (
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={(e) => {
            e.stopPropagation();
            this.props.history.push(`/user-dashboards/show/${item.id}`);
          }}
          title="Open dashboard view"
        >
          <i className="fas fa-chart-line" />
        </button>
      ),
    },
  ];

  componentDidMount() {
    this.props.setTitle('User analytics dashboards');
    this.props.setSidebarContent(null);
    this.checkExporter();
  }

  checkExporter = () => {
    BackOfficeServices.findAllDataExporterConfigs({ page: 1, pageSize: 1000 })
      .catch(() => null)
      .then((resp) => {
        const exporters = Array.isArray(resp) ? resp : (resp && resp.data) || [];
        const analyticsExporters = exporters.filter((e) => e.type === 'user-analytics');
        const active = analyticsExporters.find(
          (e) => e.metadata && e.metadata['otoroshi:user-analytics:active'] === 'true'
        );
        this.setState({
          exporters: analyticsExporters,
          activeExporterId: active ? active.id : null,
          loading: false,
        });
      });
  };

  fetchItems = () =>
    dashboards.findAll().then((items) => (Array.isArray(items) ? items : []));

  createItem = (item) => dashboards.create(item);
  updateItem = (item) => dashboards.update(item);
  deleteItem = (item) => dashboards.delete(item);

  doRestoreDefaults = () => {
    if (this.state.restoreInProgress) return;
    if (!window.confirm('Recreate any missing default dashboards?')) return;
    this.setState({ restoreInProgress: true });
    restoreDefaults()
      .then((res) => {
        this.setState({ restoreInProgress: false });
        if (res && res.created && res.created.length > 0) {
          window.newAlert(
            `Restored ${res.count} dashboard(s): ${res.created.join(', ')}`,
            'Success'
          );
        } else {
          window.newAlert('No missing default dashboards to restore.', 'Info');
        }
        if (this.table) this.table.update();
      })
      .catch((e) => {
        this.setState({ restoreInProgress: false });
        window.newAlert(`Restore failed: ${e.message}`, 'Error');
      });
  };

  injectTopBar = () => (
    <div className="btn-group input-group-btn">
      <button
        type="button"
        className="btn btn-secondary btn-sm btn-cta"
        onClick={this.doRestoreDefaults}
        disabled={this.state.restoreInProgress}
        title="Recreate any missing default dashboards"
      >
        {this.state.restoreInProgress ? (
          <>
            <i className="fas fa-spinner fa-spin" /> Restoring…
          </>
        ) : (
          <>
            <i className="fas fa-undo" /> Restore defaults
          </>
        )}
      </button>
    </div>
  );

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
            <code>user-analytics</code> exporters, and click{' '}
            <strong>Set as active analytics exporter</strong>.
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
    if (!window.__user.superAdmin) return null;

    const { loading, activeExporterId } = this.state;

    if (loading) {
      return (
        <div style={{ padding: 16, color: '#888' }}>
          <i className="fas fa-spinner fa-spin" /> Loading…
        </div>
      );
    }

    if (!activeExporterId) return this.renderOnboarding();

    return (
      <Table
        parentProps={this.props}
        selfUrl="user-dashboards"
        defaultTitle="User analytics dashboards"
        defaultValue={NEW_DASHBOARD_TEMPLATE}
        itemName="Dashboard"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={this.fetchItems}
        updateItem={this.updateItem}
        deleteItem={this.deleteItem}
        createItem={this.createItem}
        injectTable={(table) => (this.table = table)}
        injectTopBar={this.injectTopBar}
        showActions={true}
        showLink={false}
        rowNavigation={true}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/user-dashboards/edit/${item.id}`;
        }}
        itemUrl={(item) => `/bo/dashboard/user-dashboards/edit/${item.id}`}
        export
        kubernetesKind="analytics.otoroshi.io/UserDashboard"
        extractKey={(item) => item.id}
      />
    );
  }
}
