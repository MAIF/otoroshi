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
  };

  componentDidMount() {
    this.props.setTitle('User analytics');
    this.props.setSidebarContent(null);
    this.loadDashboard();
  }

  componentDidUpdate(prev) {
    if (prev.params.titem !== this.props.params.titem) this.loadDashboard();
    if (prev.location.search !== this.props.location.search) {
      this.setState({ filters: queryToFilters(this.props.location.query || {}) });
    }
  }

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
    const { dashboard, loading, error, filters } = this.state;
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
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <div>
            <h3 style={{ marginBottom: 0 }}>{dashboard.name}</h3>
            {dashboard.description && (
              <small style={{ color: '#888' }}>{dashboard.description}</small>
            )}
          </div>
          <div>
            <Link to={`/user-dashboards/edit/${dashboard.id}`} className="btn btn-sm btn-secondary">
              <i className="fas fa-edit" /> Edit
            </Link>
            <Link to="/user-dashboards" className="btn btn-sm btn-link">
              All dashboards
            </Link>
          </div>
        </div>
        <Filters value={filters} onChange={this.onFiltersChange} />
        <Grid
          widgets={dashboard.widgets || []}
          filters={filters}
          compare={!!filters.compare}
          refreshInterval={filters.refresh || 0}
        />
      </div>
    );
  }
}
