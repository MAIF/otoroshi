import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import { Filters } from './Filters';
import { Grid } from './Grid';
import { WidgetWizard } from './WidgetWizard';
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

  openAddWidget = () => {
    const { dashboard } = this.state;
    if (!dashboard) return;
    window
      .wizard(
        'Add a widget',
        (ok, cancel, state, setState) => (
          <WidgetWizard onChange={(widget) => setState(widget)} />
        ),
        { additionalClass: 'modal-lg', okLabel: 'Add widget' }
      )
      .then((widget) => {
        if (!widget || !widget.query) return;
        const updated = {
          ...dashboard,
          widgets: [...(dashboard.widgets || []), widget],
        };
        dashboards
          .update(updated)
          .then((res) => {
            if (res && res.error) {
              window.newAlert(`Failed to add widget: ${res.error}`, 'Error');
              return;
            }
            this.setState({ dashboard: updated });
          })
          .catch((e) => window.newAlert(`Failed to add widget: ${e.message}`, 'Error'));
      });
  };

  editWidget = (widgetId) => {
    const { dashboard } = this.state;
    if (!dashboard) return;
    const existing = (dashboard.widgets || []).find((w) => w.id === widgetId);
    if (!existing) return;
    window
      .wizard(
        'Edit widget',
        (ok, cancel, state, setState) => (
          <WidgetWizard initial={existing} onChange={(widget) => setState(widget)} />
        ),
        { additionalClass: 'modal-lg', okLabel: 'Save' }
      )
      .then((widget) => {
        if (!widget || !widget.query) return;
        const updated = {
          ...dashboard,
          widgets: (dashboard.widgets || []).map((w) => (w.id === widgetId ? widget : w)),
        };
        dashboards
          .update(updated)
          .then((res) => {
            if (res && res.error) {
              window.newAlert(`Failed to save widget: ${res.error}`, 'Error');
              return;
            }
            this.setState({ dashboard: updated });
          })
          .catch((e) => window.newAlert(`Failed to save widget: ${e.message}`, 'Error'));
      });
  };

  moveWidget = (widgetId, direction) => {
    const { dashboard } = this.state;
    if (!dashboard) return;
    const widgets = [...(dashboard.widgets || [])];
    const idx = widgets.findIndex((w) => w.id === widgetId);
    if (idx < 0) return;
    const newIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (newIdx < 0 || newIdx >= widgets.length) return;
    [widgets[idx], widgets[newIdx]] = [widgets[newIdx], widgets[idx]];
    const updated = { ...dashboard, widgets };
    dashboards
      .update(updated)
      .then((res) => {
        if (res && res.error) {
          window.newAlert(`Failed to move widget: ${res.error}`, 'Error');
          return;
        }
        this.setState({ dashboard: updated });
      })
      .catch((e) => window.newAlert(`Failed to move widget: ${e.message}`, 'Error'));
  };

  removeWidget = (widgetId) => {
    const { dashboard } = this.state;
    if (!dashboard) return;
    if (!window.confirm('Remove this widget from the dashboard?')) return;
    const updated = {
      ...dashboard,
      widgets: (dashboard.widgets || []).filter((w) => w.id !== widgetId),
    };
    dashboards
      .update(updated)
      .then((res) => {
        if (res && res.error) {
          window.newAlert(`Failed to remove widget: ${res.error}`, 'Error');
          return;
        }
        this.setState({ dashboard: updated });
      })
      .catch((e) => window.newAlert(`Failed to remove widget: ${e.message}`, 'Error'));
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
            <Link to="/user-dashboards" className="btn btn-sm btn-secondary">
              <i className="fas fa-th-list" /> All dashboards
            </Link>
            <button
              type="button"
              className="btn btn-sm btn-success"
              style={{ marginLeft: 5 }}
              onClick={this.openAddWidget}
            >
              <i className="fas fa-plus-circle" /> Add widget
            </button>
            <Link
              to={`/user-dashboards/edit/${dashboard.id}`}
              className="btn btn-sm btn-secondary"
              style={{ marginLeft: 5 }}
            >
              <i className="fas fa-edit" /> Edit
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
          onRemoveWidget={this.removeWidget}
          onEditWidget={this.editWidget}
          onMoveWidget={this.moveWidget}
        />
      </div>
    );
  }
}
