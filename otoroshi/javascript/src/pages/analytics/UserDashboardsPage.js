import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { dashboards, restoreDefaults } from './service';

export class UserDashboardsPage extends Component {
  state = {
    activeExporterId: null,
    exporters: [],
    loading: true,
    restoreInProgress: false,
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
      cell: (v, item) => <Link to={`/user-dashboards/${item.id}`}>{v || item.id}</Link>,
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
        <Link to={`/user-dashboards/${item.id}`} className="btn btn-sm btn-success">
          <i className="fas fa-chart-line" />
        </Link>
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

  fetchItems = () => dashboards.findAll().then((items) => (Array.isArray(items) ? items : []));

  deleteItem = (item) => dashboards.delete(item).then(() => ({}));

  gotoEdit = (item) => {
    this.props.history.push(`/user-dashboards/edit/${item.id}`);
  };

  doRestoreDefaults = () => {
    if (this.state.restoreInProgress) return;
    if (!window.confirm('Recreate any missing default dashboards?')) return;
    this.setState({ restoreInProgress: true });
    restoreDefaults()
      .then((res) => {
        this.setState({ restoreInProgress: false });
        if (res && res.created && res.created.length > 0) {
          window.newAlert(`Restored ${res.count} dashboard(s): ${res.created.join(', ')}`, 'Success');
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
      <Link className="btn btn-primary btn-sm btn-cta" to="/user-dashboards/add">
        <i className="fas fa-plus-circle" /> New dashboard
      </Link>
      <button
        type="button"
        className="btn btn-secondary btn-sm btn-cta"
        onClick={this.doRestoreDefaults}
        disabled={this.state.restoreInProgress}
        title="Recreate any missing default dashboards"
        style={{ marginLeft: 5 }}
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
      <div>
        <Table
          parentProps={this.props}
          selfUrl="user-dashboards"
          defaultTitle="User analytics dashboards"
          defaultValue={() => ({})}
          itemName="Dashboard"
          formSchema={null}
          formFlow={null}
          columns={this.columns}
          fetchItems={this.fetchItems}
          deleteItem={this.deleteItem}
          showActions={true}
          showLink={false}
          hideAddItemAction={true}
          navigateOnEdit={this.gotoEdit}
          injectTable={(table) => (this.table = table)}
          injectTopBar={this.injectTopBar}
          extractKey={(item) => item.id}
          rowNavigation={true}
          itemUrl={(item) => `/bo/dashboard/user-dashboards/${item.id}`}
        />
      </div>
    );
  }
}
