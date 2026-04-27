import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { alerts } from './service';

const NEW_ALERT_TEMPLATE = () => ({
  _loc: { tenant: 'default', teams: ['default'] },
  id: '',
  name: 'New alert',
  description: '',
  enabled: true,
  tags: [],
  metadata: {},
  severity: 'warning',
  message: 'Alert triggered',
  windowSeconds: 300,
  evaluationIntervalSeconds: 60,
  cooldownSeconds: 600,
  combine: 'AND',
  conditions: [
    {
      query: 'error_rate_ts',
      params: {},
      filters: {},
      reducer: 'max',
      operator: '>',
      threshold: 0.05,
    },
  ],
});

const SEVERITY_BADGE = {
  info: { label: 'info', color: '#2196f3' },
  warning: { label: 'warning', color: '#ff9800' },
  critical: { label: 'critical', color: '#f44336' },
};

export class UserAlertsPage extends Component {
  state = {
    activeExporterId: null,
    exporters: [],
    notifierExporters: 0,
    loading: true,
  };

  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name', placeholder: 'High error rate on checkout' } },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Why this alert exists' },
    },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    severity: {
      type: 'select',
      props: {
        label: 'Severity',
        possibleValues: [
          { label: 'info', value: 'info' },
          { label: 'warning', value: 'warning' },
          { label: 'critical', value: 'critical' },
        ],
      },
    },
    message: {
      type: 'string',
      props: { label: 'Message', placeholder: 'Free-form text shown in the alert event' },
    },
    windowSeconds: {
      type: 'number',
      props: {
        label: 'Time window (s)',
        suffix: 'sec',
        help: 'How far back to look when evaluating each condition (e.g. 300 = last 5 min).',
      },
    },
    evaluationIntervalSeconds: {
      type: 'number',
      props: {
        label: 'Evaluation interval (s)',
        suffix: 'sec',
        help: 'Minimum time between two evaluations of this alert.',
      },
    },
    cooldownSeconds: {
      type: 'number',
      props: {
        label: 'Cooldown (s)',
        suffix: 'sec',
        help: 'Minimum time between two consecutive firings (avoid flooding).',
      },
    },
    combine: {
      type: 'select',
      props: {
        label: 'Combine conditions',
        possibleValues: [
          { label: 'AND (all conditions must match)', value: 'AND' },
          { label: 'OR (any condition matches)', value: 'OR' },
        ],
      },
    },
    conditions: {
      type: 'jsonobjectcode',
      props: {
        label: 'Conditions',
        height: '400px',
        help:
          'JSON array. Each condition: { query, params, filters, reducer, operator, threshold }. ' +
          'reducer: avg|max|min|sum|last. operator: >|>=|<|<=|==|!=. ' +
          'filters: { route_id, api_id, apikey_id, group_id, err }.',
      },
    },
    tags: { type: 'array', props: { label: 'Tags' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
  };

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    'enabled',
    '<<< Trigger',
    'severity',
    'message',
    'windowSeconds',
    'evaluationIntervalSeconds',
    'cooldownSeconds',
    '>>> Conditions',
    'combine',
    'conditions',
    '>>> Tags & Metadata',
    'tags',
    'metadata',
  ];

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    {
      title: 'Severity',
      filterId: 'severity',
      style: { width: 110, textAlign: 'center' },
      content: (item) => item.severity,
      cell: (v) => {
        const cfg = SEVERITY_BADGE[v] || { label: v, color: '#888' };
        return (
          <span
            className="badge"
            style={{ background: cfg.color, color: '#fff', padding: '4px 8px' }}
          >
            {cfg.label}
          </span>
        );
      },
    },
    {
      title: 'Window',
      filterId: 'windowSeconds',
      style: { width: 90, textAlign: 'center' },
      content: (item) => item.windowSeconds,
      cell: (v) => `${v}s`,
    },
    {
      title: 'Conditions',
      filterId: 'conditions',
      style: { width: 110, textAlign: 'center' },
      content: (item) =>
        `${(item.conditions || []).length} (${(item.combine || 'AND').toUpperCase()})`,
    },
    {
      title: 'Enabled',
      filterId: 'enabled',
      style: { width: 90, textAlign: 'center' },
      content: (item) => (item.enabled ? 'yes' : 'no'),
      cell: (v) =>
        v === 'yes' ? (
          <span className="badge bg-success">on</span>
        ) : (
          <span className="badge bg-secondary">off</span>
        ),
    },
  ];

  componentDidMount() {
    this.props.setTitle('User analytics alerts');
    this.props.setSidebarContent(null);
    this.checkSetup();
  }

  checkSetup = () => {
    BackOfficeServices.findAllDataExporterConfigs({ page: 1, pageSize: 1000 })
      .catch(() => null)
      .then((resp) => {
        const all = Array.isArray(resp) ? resp : (resp && resp.data) || [];
        const analyticsExporters = all.filter((e) => e.type === 'user-analytics');
        const active = analyticsExporters.find(
          (e) => e.metadata && e.metadata['otoroshi:user-analytics:active'] === 'true'
        );
        // Notifiers = exporters that can deliver alerts to humans. Heuristic.
        const notifierTypes = new Set([
          'mailer',
          'webhook',
          'http',
          'kafka',
          'pulsar',
          'elastic',
          'splunk',
          'datadog',
          'newrelic',
          'workflow',
          'jms',
          'syslog',
          'tcp',
          'udp',
          'wasm',
          'custom',
        ]);
        const notifierExporters = all.filter((e) => e.enabled && notifierTypes.has(e.type)).length;
        this.setState({
          exporters: analyticsExporters,
          activeExporterId: active ? active.id : null,
          notifierExporters,
          loading: false,
        });
      });
  };

  fetchItems = () =>
    alerts.findAll().then((items) => (Array.isArray(items) ? items : []));

  createItem = (item) => alerts.create(item);
  updateItem = (item) => alerts.update(item);
  deleteItem = (item) => alerts.delete(item);

  renderEnrollmentBanner() {
    const { notifierExporters } = this.state;
    if (notifierExporters > 0) return null;
    return (
      <div
        className="alert alert-info"
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
      >
        <div>
          <strong>
            <i className="fas fa-info-circle" /> No notification channel configured
          </strong>
          <div style={{ marginTop: 4 }}>
            Alerts are emitted as <code>AlertEvent</code> with{' '}
            <code>alertSubcategory: "user-analytics"</code>. To deliver them by email/Slack/webhook,
            create a <strong>Data Exporter</strong> (mailer, webhook, kafka, …) and add a filter on{' '}
            <code>{`{"alert": "UserAnalyticsAlert"}`}</code>.
          </div>
        </div>
        <Link
          to="/exporters/add?type=mailer&kind=alert-channel"
          className="btn btn-sm btn-success"
        >
          <i className="fas fa-plus" /> New data exporter
        </Link>
      </div>
    );
  }

  renderOnboarding() {
    const { exporters } = this.state;
    const hasExporters = exporters.length > 0;
    return (
      <div style={{ padding: 24, textAlign: 'center', maxWidth: 720, margin: '40px auto' }}>
        <h3>Configure user analytics first</h3>
        <p style={{ color: 'var(--text-muted)', marginBottom: 24 }}>
          {hasExporters
            ? 'A user-analytics exporter is configured but none is marked active. Pick one before creating alerts.'
            : 'No user-analytics PostgreSQL exporter is configured yet. Alerts evaluate against the analytics database, so you need to set one up first.'}
        </p>
        <Link to="/user-dashboards" className="btn btn-primary btn-lg">
          <i className="fas fa-arrow-right" /> Go to User Analytics setup
        </Link>
      </div>
    );
  }

  render() {
    const u = window.__user || {};
    const canAccess = u.superAdmin || u.tenantAdmin;
    if (!canAccess) return null;

    const { loading, activeExporterId } = this.state;

    if (loading) {
      return (
        <div style={{ padding: 16, color: 'var(--text-muted)' }}>
          <i className="fas fa-spinner fa-spin" /> Loading…
        </div>
      );
    }

    if (!activeExporterId) return this.renderOnboarding();

    return (
      <div>
        {this.renderEnrollmentBanner()}
        <Table
          parentProps={this.props}
          selfUrl="user-alerts"
          defaultTitle="User analytics alerts"
          defaultValue={NEW_ALERT_TEMPLATE}
          itemName="Alert"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={this.fetchItems}
          updateItem={this.updateItem}
          deleteItem={this.deleteItem}
          createItem={this.createItem}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          extractKey={(item) => item.id}
        />
      </div>
    );
  }
}
