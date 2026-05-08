import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { alerts as alertsClient, alertEvents } from './service';

const SEVERITY_BADGE = {
  info: { label: 'info', color: '#2196f3' },
  warning: { label: 'warning', color: '#ff9800' },
  critical: { label: 'critical', color: '#f44336' },
};

function formatTs(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`;
}

export class UserAlertEventsPage extends Component {
  state = {
    alert: null,
    loading: true,
    filter: 'all', // all | unseen | seen
  };

  alertId() {
    return this.props.params.titem;
  }

  componentDidMount() {
    this.props.setTitle('Alert events');
    this.props.setSidebarContent(null);
    this.loadAlert();
  }

  componentDidUpdate(prev) {
    if (prev.params.titem !== this.props.params.titem) this.loadAlert();
  }

  loadAlert = () => {
    const id = this.alertId();
    if (!id) return;
    alertsClient
      .findById(id)
      .catch(() => null)
      .then((alert) => {
        if (alert && alert.name) this.props.setTitle(`Alert events — ${alert.name}`);
        this.setState({ alert, loading: false });
      });
  };

  fetchItems = () => {
    const id = this.alertId();
    if (!id) return Promise.resolve([]);
    const opts = { limit: 500 };
    if (this.state.filter === 'seen') opts.seen = true;
    if (this.state.filter === 'unseen') opts.seen = false;
    return alertEvents.list(id, opts).then((r) => (r && r.items) || []);
  };

  changeFilter = (filter) => {
    this.setState({ filter }, () => {
      if (this.table) this.table.update();
    });
  };

  toggleSeen = (event) => {
    const id = this.alertId();
    const op = event.seen_at ? alertEvents.markUnseen : alertEvents.markSeen;
    op(id, event.id).then(() => {
      if (this.table) this.table.update();
    });
  };

  showEventDetails = (item) => {
    const matched = (item.conditions || []).filter((c) => c.matched).length;
    const total = (item.conditions || []).length;
    const cfg = SEVERITY_BADGE[item.severity] || { label: item.severity || '—', color: '#888' };

    const codeStyle = {
      fontFamily: 'monospace',
      fontSize: '0.85rem',
      padding: '1px 6px',
      borderRadius: 3,
      background: 'var(--bg-color_level3)',
      border: '1px solid var(--border-color)',
      color: 'var(--text)',
    };
    const cellStyle = {
      padding: '6px 8px',
      borderTop: '1px solid var(--border-color)',
      verticalAlign: 'middle',
    };
    const headStyle = {
      ...cellStyle,
      borderBottom: '2px solid var(--border-color)',
      borderTop: 'none',
      color: 'var(--text-muted)',
      fontWeight: 600,
      textAlign: 'left',
    };

    const body = (
      <div style={{ color: 'var(--text)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <span
            className="badge"
            style={{ background: cfg.color, color: '#fff', padding: '4px 8px' }}
          >
            {cfg.label}
          </span>
          <span style={{ fontFamily: 'monospace', color: 'var(--text-muted)' }}>
            {formatTs(item.ts)}
          </span>
        </div>

        {item.message && (
          <div style={{ marginBottom: 12 }}>
            <strong>Message:</strong> {item.message}
          </div>
        )}

        <div style={{ marginBottom: 12 }}>
          <strong>
            {matched}/{total} matched ({(item.combine || 'AND').toUpperCase()})
          </strong>
        </div>

        <div
          style={{
            border: '1px solid var(--border-color)',
            borderRadius: 4,
            background: 'var(--bg-color_level2)',
            overflow: 'hidden',
          }}
        >
          <table
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              color: 'var(--text)',
            }}
          >
            <thead>
              <tr>
                <th style={headStyle}>Query</th>
                <th style={headStyle}>Reducer</th>
                <th style={headStyle}>Value</th>
                <th style={headStyle}>Op</th>
                <th style={headStyle}>Threshold</th>
                <th style={{ ...headStyle, textAlign: 'center' }}>Matched</th>
              </tr>
            </thead>
            <tbody>
              {(item.conditions || []).map((c, i) => (
                <tr key={i}>
                  <td style={cellStyle}>
                    <span style={codeStyle}>{c.query}</span>
                  </td>
                  <td style={cellStyle}>{c.reducer}</td>
                  <td style={{ ...cellStyle, fontFamily: 'monospace' }}>
                    {c.value != null ? c.value : <em style={{ color: 'var(--text-muted)' }}>—</em>}
                  </td>
                  <td style={cellStyle}>{c.operator}</td>
                  <td style={{ ...cellStyle, fontFamily: 'monospace' }}>{c.threshold}</td>
                  <td style={{ ...cellStyle, textAlign: 'center' }}>
                    {c.error ? (
                      <span style={{ color: 'var(--color-red)' }} title={c.error}>
                        <i className="fas fa-exclamation-triangle" /> err
                      </span>
                    ) : c.matched ? (
                      <span
                        style={{
                          background: 'var(--color-red)',
                          color: '#fff',
                          padding: '2px 8px',
                          borderRadius: 3,
                          fontSize: '0.8rem',
                        }}
                      >
                        yes
                      </span>
                    ) : (
                      <span
                        style={{
                          background: 'var(--bg-color_level3)',
                          color: 'var(--text-muted)',
                          padding: '2px 8px',
                          borderRadius: 3,
                          fontSize: '0.8rem',
                          border: '1px solid var(--border-color)',
                        }}
                      >
                        no
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {item.conditions && item.conditions.some((c) => c.error) && (
          <div
            style={{
              marginTop: 12,
              padding: 10,
              borderRadius: 4,
              background: 'var(--bg-color_level3)',
              border: '1px solid var(--color-red)',
              color: 'var(--text)',
            }}
          >
            <strong style={{ color: 'var(--color-red)' }}>
              <i className="fas fa-exclamation-triangle" /> Errors:
            </strong>
            <ul style={{ marginTop: 4, marginBottom: 0, paddingLeft: 20 }}>
              {item.conditions
                .filter((c) => c.error)
                .map((c, i) => (
                  <li key={i}>
                    <span style={codeStyle}>{c.query}</span>: {c.error}
                  </li>
                ))}
            </ul>
          </div>
        )}
      </div>
    );
    window.newAlert(
      body,
      `Alert event — ${item.alert_name || item.alert_id}`,
      undefined,
      { maxWidth: '90vw', width: 1100 }
    );
  };

  markAllSeen = () => {
    const id = this.alertId();
    if (!window.confirm('Mark every event of this alert as seen?')) return;
    alertEvents.markAllSeen(id).then(() => {
      if (this.table) this.table.update();
    });
  };

  columns = [
    {
      title: 'Date',
      filterId: 'ts',
      style: { width: 200 },
      content: (item) => item.ts,
      cell: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{formatTs(v)}</span>,
    },
    {
      title: 'Severity',
      filterId: 'severity',
      style: { width: 110, textAlign: 'center' },
      content: (item) => item.severity || '',
      cell: (v) => {
        const cfg = SEVERITY_BADGE[v] || { label: v || '—', color: '#888' };
        return (
          <span
            className="badge"
            style={{ background: cfg.color, color: '#fff', padding: '3px 8px' }}
          >
            {cfg.label}
          </span>
        );
      },
    },
    {
      title: 'Message',
      filterId: 'message',
      content: (item) => item.message || '',
    },
    {
      title: 'Conditions',
      style: { width: 200, textAlign: 'center' },
      notFilterable: true,
      content: (item) => item.id,
      cell: (_v, item) => {
        const matched = (item.conditions || []).filter((c) => c.matched).length;
        const total = (item.conditions || []).length;
        return (
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            onClick={(e) => {
              e.stopPropagation();
              this.showEventDetails(item);
            }}
            title="Show condition details"
          >
            <i className="fas fa-search" /> {matched}/{total} matched (
            {(item.combine || 'AND').toUpperCase()})
          </button>
        );
      },
    },
    {
      title: 'Seen',
      filterId: 'seen_at',
      style: { width: 160 },
      content: (item) => (item.seen_at ? formatTs(item.seen_at) : ''),
      cell: (_v, item) => {
        if (!item.seen_at) {
          return (
            <span
              title="Unseen"
              style={{
                display: 'inline-block',
                width: 10,
                height: 10,
                borderRadius: '50%',
                background: 'var(--color-red)',
              }}
            />
          );
        }
        return (
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
            <div>{formatTs(item.seen_at)}</div>
            {item.seen_by && <div>by {item.seen_by}</div>}
          </div>
        );
      },
    },
    {
      title: 'Action',
      style: { width: 80, textAlign: 'right' },
      notFilterable: true,
      content: (item) => item.id,
      cell: (_v, item) => (
        <button
          type="button"
          className={`btn btn-sm ${item.seen_at ? 'btn-outline-secondary' : 'btn-success'}`}
          onClick={(e) => {
            e.stopPropagation();
            this.toggleSeen(item);
          }}
          title={item.seen_at ? 'Mark as unseen' : 'Mark as seen'}
        >
          <i className={`fas ${item.seen_at ? 'fa-eye-slash' : 'fa-check'}`} />
        </button>
      ),
    },
  ];

  injectTopBar = () => (
    <div className="btn-group input-group-btn">
      {['all', 'unseen', 'seen'].map((f) => (
        <button
          key={f}
          type="button"
          style={{ marginRight: 5 }}
          className={`btn btn-sm btn-cta ${this.state.filter === f ? 'btn-primary' : 'btn-outline-secondary'}`}
          onClick={() => this.changeFilter(f)}
        >
          {f}
        </button>
      ))}
      <button
        type="button"
        className="btn btn-sm btn-cta btn-success"
        style={{ marginLeft: 5 }}
        onClick={this.markAllSeen}
        title="Mark every event of this alert as seen"
      >
        <i className="fas fa-check-double" /> Mark all seen
      </button>
    </div>
  );

  render() {
    const u = window.__user || {};
    if (!u.superAdmin && !u.tenantAdmin) return null;
    const { alert, loading } = this.state;

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
            <h3 style={{ marginBottom: 0 }}>
              {alert ? alert.name : 'Alert events'}
              {alert && alert.severity && (
                <span
                  className="badge"
                  style={{
                    marginLeft: 12,
                    background: (SEVERITY_BADGE[alert.severity] || {}).color || '#888',
                    color: '#fff',
                    fontSize: 12,
                    padding: '4px 8px',
                  }}
                >
                  {alert.severity}
                </span>
              )}
            </h3>
            {alert && alert.description && (
              <small style={{ color: 'var(--text-muted)' }}>{alert.description}</small>
            )}
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <Link to="/user-alerts" className="btn btn-sm btn-secondary">
              <i className="fas fa-th-list" /> All alerts
            </Link>
            {alert && (
              <Link
                to={`/user-alerts/edit/${alert.id}`}
                className="btn btn-sm btn-secondary"
              >
                <i className="fas fa-edit" /> Edit alert
              </Link>
            )}
          </div>
        </div>

        {loading ? (
          <div style={{ color: 'var(--text-muted)', padding: 16 }}>
            <i className="fas fa-spinner fa-spin" /> Loading…
          </div>
        ) : (
          <Table
            parentProps={this.props}
            selfUrl={`user-alert-events/${this.alertId()}`}
            defaultTitle={`Alert events`}
            defaultValue={() => ({})}
            defaultSort="Date"
            defaultSortDesc={true}
            itemName="Alert Event"
            formSchema={null}
            formFlow={null}
            columns={this.columns}
            fetchItems={this.fetchItems}
            updateItem={() => Promise.resolve({})}
            deleteItem={() => Promise.resolve({})}
            showActions={false}
            showLink={false}
            injectTable={(table) => (this.table = table)}
            injectTopBar={this.injectTopBar}
            extractKey={(item) => item.id}
          />
        )}
      </div>
    );
  }
}
