import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import { Filters } from './Filters';
import { Grid } from './Grid';
import { dashboards, queryToFilters, filtersToQuery } from './service';

export class UserDashboardViewPage extends Component {
  state = {
    dashboard: null,
    loading: true,
    error: null,
    filters: queryToFilters(this.props.location.query || {}),
    refreshKey: 0,
    lastRefreshAt: null,
  };

  componentDidMount() {
    this.props.setTitle('User analytics');
    this.props.setSidebarContent(null);
    this.loadDashboard();
    this.setupAutoRefresh();
  }

  componentDidUpdate(prev, prevState) {
    if (prev.params.titem !== this.props.params.titem) this.loadDashboard();
    if (prev.location.search !== this.props.location.search) {
      this.setState({ filters: queryToFilters(this.props.location.query || {}) });
    }
    const prevInterval = (prevState.filters || {}).refresh || 0;
    const currInterval = (this.state.filters || {}).refresh || 0;
    if (prevInterval !== currInterval) {
      this.setupAutoRefresh();
    }
  }

  componentWillUnmount() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
  }

  setupAutoRefresh = () => {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    const ms = (this.state.filters.refresh || 0) * 1000;
    if (ms > 0) {
      this.refreshTimer = setInterval(() => {
        if (document.visibilityState !== 'hidden') this.bumpRefresh();
      }, ms);
    }
  };

  bumpRefresh = () => {
    this.setState((s) => ({ refreshKey: s.refreshKey + 1 }));
  };

  onWidgetFetched = () => {
    this.setState({ lastRefreshAt: Date.now() });
  };

  loadDashboard = () => {
    const id = this.props.params.titem;
    if (!id) {
      this.setState({ error: 'no dashboard id', loading: false });
      return;
    }
    this.setState({ loading: true });
    dashboards
      .findById(id)
      .then((d) => {
        if (!d || d.error) {
          this.setState({ error: (d && d.error) || 'not found', loading: false });
        } else {
          this.props.setTitle(`Analytics — ${d.name || id}`);
          this.setState({ dashboard: d, loading: false, error: null });
        }
      })
      .catch((e) => this.setState({ error: e.message, loading: false }));
  };

  onFiltersChange = (filters) => {
    this.setState({ filters });
    const q = filtersToQuery(filters);
    const search =
      '?' +
      Object.entries(q)
        .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
        .join('&');
    this.props.history.replace({
      pathname: this.props.location.pathname,
      search,
    });
  };

  render() {
    const { dashboard, loading, error, filters, refreshKey, lastRefreshAt } = this.state;
    if (loading) {
      return (
        <div style={{ padding: 16, color: '#888' }}>
          <i className="fas fa-spinner fa-spin" /> Loading dashboard…
        </div>
      );
    }
    if (error) {
      return (
        <div style={{ padding: 16 }}>
          <div className="alert alert-warning">
            Could not load dashboard: <strong>{error}</strong>
          </div>
          <Link to="/user-dashboards" className="btn btn-secondary">
            Back to dashboards
          </Link>
        </div>
      );
    }
    return (
      <div className="user-analytics-view" style={{ padding: '0 8px' }}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 8,
          }}
        >
          <div>
            <h3 style={{ marginBottom: 0, display: 'none' }}>{dashboard.name}</h3>
            {dashboard.description && (
              <small style={{ color: '#888' }}>{dashboard.description}</small>
            )}
          </div>
          <div>
            <Link to={`/user-dashboards/edit/${dashboard.id}`} className="btn btn-sm btn-secondary">
              <i className="fas fa-edit" /> Edit
            </Link>
            <Link to="/user-dashboards" className="btn btn-sm btn-secondary" style={{ marginLeft: 5 }}>
              All dashboards
            </Link>
          </div>
        </div>
        <Filters
          value={filters}
          onChange={this.onFiltersChange}
          onRefresh={this.bumpRefresh}
          lastRefreshAt={lastRefreshAt}
        />
        <Grid
          widgets={dashboard.widgets || []}
          filters={filters}
          compare={!!filters.compare}
          refreshKey={refreshKey}
          onFetched={this.onWidgetFetched}
        />
      </div>
    );
  }
}
