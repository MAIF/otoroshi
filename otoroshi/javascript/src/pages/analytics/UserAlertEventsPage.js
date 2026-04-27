import React, { Component } from 'react';
import { Link } from 'react-router-dom';
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
    items: [],
    loading: true,
    error: null,
    filter: 'all', // all | unseen | seen
  };

  componentDidMount() {
    this.props.setTitle('Alert events');
    this.props.setSidebarContent(null);
    this.refresh();
  }

  componentDidUpdate(prev) {
    if (prev.params.titem !== this.props.params.titem) this.refresh();
  }

  alertId() {
    return this.props.params.titem;
  }

  refresh = () => {
    const id = this.alertId();
    if (!id) return;
    this.setState({ loading: true });
    Promise.all([
      alertsClient.findById(id).catch(() => null),
      this.fetchEvents(id, this.state.filter),
    ]).then(([alert, events]) => {
      const items = (events && events.items) || [];
      this.setState({
        alert,
        items,
        loading: false,
        error: events && events.error ? events.error : null,
      });
      if (alert && alert.name) this.props.setTitle(`Alert events — ${alert.name}`);
    });
  };

  fetchEvents = (id, filter) => {
    const opts = { limit: 500 };
    if (filter === 'seen') opts.seen = true;
    if (filter === 'unseen') opts.seen = false;
    return alertEvents.list(id, opts);
  };

  changeFilter = (filter) => {
    this.setState({ filter, loading: true }, this.refresh);
  };

  toggleSeen = (event) => {
    const id = this.alertId();
    const op = event.seen_at ? alertEvents.markUnseen : alertEvents.markSeen;
    op(id, event.id).then(() => this.refresh());
  };

  markAllSeen = () => {
    const id = this.alertId();
    if (!window.confirm('Mark every event of this alert as seen?')) return;
    alertEvents.markAllSeen(id).then(() => this.refresh());
  };

  render() {
    const u = window.__user || {};
    if (!u.superAdmin && !u.tenantAdmin) return null;
    const { alert, items, loading, error, filter } = this.state;

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
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={this.refresh}
              title="Reload"
            >
              <i className="fas fa-sync" />
            </button>
            <button
              type="button"
              className="btn btn-sm btn-success"
              onClick={this.markAllSeen}
              disabled={items.length === 0}
              title="Mark every event of this alert as seen"
            >
              <i className="fas fa-check-double" /> Mark all seen
            </button>
          </div>
        </div>

        <div className="btn-group btn-group-sm" role="group" style={{ marginBottom: 12 }}>
          {['all', 'unseen', 'seen'].map((f) => (
            <button
              key={f}
              type="button"
              className={`btn btn-sm ${filter === f ? 'btn-primary' : 'btn-outline-secondary'}`}
              onClick={() => this.changeFilter(f)}
            >
              {f}
            </button>
          ))}
        </div>

        {loading && (
          <div style={{ color: 'var(--text-muted)', padding: 16 }}>
            <i className="fas fa-spinner fa-spin" /> Loading…
          </div>
        )}

        {error && (
          <div className="alert alert-danger" role="alert">
            {error}
          </div>
        )}

        {!loading && !error && items.length === 0 && (
          <div
            style={{
              color: 'var(--text-muted)',
              padding: 24,
              textAlign: 'center',
              border: '1px dashed var(--border-color)',
              borderRadius: 4,
            }}
          >
            No events yet.
          </div>
        )}

        {!loading && items.length > 0 && (
          <table className="table table-sm" style={{ color: 'var(--text)' }}>
            <thead>
              <tr>
                <th style={{ width: 30 }}></th>
                <th>Date</th>
                <th>Severity</th>
                <th>Message</th>
                <th>Conditions</th>
                <th style={{ width: 130 }}>Seen</th>
                <th style={{ width: 100, textAlign: 'right' }}></th>
              </tr>
            </thead>
            <tbody>
              {items.map((ev) => {
                const cfg = SEVERITY_BADGE[ev.severity] || { label: ev.severity, color: '#888' };
                const matched = (ev.conditions || []).filter((c) => c.matched).length;
                const total = (ev.conditions || []).length;
                return (
                  <tr key={ev.id} style={{ opacity: ev.seen_at ? 0.6 : 1 }}>
                    <td style={{ textAlign: 'center' }}>
                      {!ev.seen_at && (
                        <span
                          title="Unseen"
                          style={{
                            display: 'inline-block',
                            width: 10,
                            height: 10,
                            background: cfg.color,
                            borderRadius: '50%',
                          }}
                        />
                      )}
                    </td>
                    <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{formatTs(ev.ts)}</td>
                    <td>
                      <span
                        className="badge"
                        style={{ background: cfg.color, color: '#fff', padding: '3px 8px' }}
                      >
                        {ev.severity || '—'}
                      </span>
                    </td>
                    <td>{ev.message || ''}</td>
                    <td style={{ fontSize: 12 }}>
                      <details>
                        <summary>
                          {matched}/{total} matched ({(ev.combine || 'AND').toUpperCase()})
                        </summary>
                        <ul style={{ marginTop: 4, paddingLeft: 18 }}>
                          {(ev.conditions || []).map((c, i) => (
                            <li key={i} style={{ color: c.matched ? 'var(--color-red)' : 'var(--text-muted)' }}>
                              <code>{c.query}</code>: {c.reducer}({(c.value != null ? c.value : '?')}) {c.operator}{' '}
                              {c.threshold}
                              {c.error ? <em style={{ color: 'var(--color-red)' }}> — {c.error}</em> : null}
                            </li>
                          ))}
                        </ul>
                      </details>
                    </td>
                    <td style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                      {ev.seen_at ? (
                        <>
                          <div>{formatTs(ev.seen_at)}</div>
                          {ev.seen_by && <div>by {ev.seen_by}</div>}
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        type="button"
                        className={`btn btn-sm ${ev.seen_at ? 'btn-outline-secondary' : 'btn-success'}`}
                        onClick={() => this.toggleSeen(ev)}
                        title={ev.seen_at ? 'Mark as unseen' : 'Mark as seen'}
                      >
                        <i className={`fas ${ev.seen_at ? 'fa-eye-slash' : 'fa-check'}`} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    );
  }
}
