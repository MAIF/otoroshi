import React, { Component } from 'react';
import { renderWidget } from './widgets';
import { runQuery, buildFiltersPayload } from './service';

// Maps each drillable query to the filter key it sets when clicked.
const QUERY_TO_FILTER = {
  requests_by_route: 'route',
  top_error_routes: 'route',
  requests_by_api: 'api',
  requests_by_apikey: 'apikey',
};

// ============================================================================
// WidgetWrapper — handles fetch lifecycle + states
// ============================================================================

export class WidgetWrapper extends Component {
  constructor(props) {
    super(props);
    this.state = { status: 'idle', data: null, compare: null, error: null, executionMs: null };
    this.abortRef = null;
    this.refreshTimer = null;
  }

  componentDidMount() {
    this.fetchData();
  }

  componentDidUpdate(prevProps) {
    const filtersChanged = JSON.stringify(prevProps.filters) !== JSON.stringify(this.props.filters);
    const compareChanged = prevProps.compare !== this.props.compare;
    const widgetChanged = JSON.stringify(prevProps.widget) !== JSON.stringify(this.props.widget);
    const refreshKeyChanged = prevProps.refreshKey !== this.props.refreshKey;
    if (filtersChanged || compareChanged || widgetChanged || refreshKeyChanged) {
      this.fetchData();
    }
  }

  componentWillUnmount() {
    this.abort();
  }

  abort() {
    if (this.abortRef) {
      try {
        this.abortRef.abort();
      } catch (_) {}
      this.abortRef = null;
    }
  }

  handleItemClick = (item) => {
    if (!item || !this.props.onDrillDown) return;
    const filterKey = QUERY_TO_FILTER[this.props.widget && this.props.widget.query];
    if (!filterKey) return;
    const value = item.key != null ? item.key : item.value;
    if (value == null) return;
    this.props.onDrillDown(filterKey, String(value));
  };

  fetchData = () => {
    const { widget, filters, compare } = this.props;
    if (!widget || !widget.query) return;
    this.abort();
    const ctl = new AbortController();
    this.abortRef = ctl;
    this.setState({ status: 'loading', error: null });
    const payload = {
      query: widget.query,
      params: widget.params || {},
      filters: buildFiltersPayload(filters),
      compare: !!compare,
      bucket: filters.bucket || null,
    };
    runQuery(payload, ctl.signal)
      .then((res) => {
        if (ctl.signal.aborted) return;
        if (!res.ok) {
          this.setState({
            status: 'error',
            error: (res.body && res.body.error) || `HTTP ${res.status}`,
          });
        } else if (!res.body || !res.body.data) {
          this.setState({ status: 'empty' });
        } else {
          this.setState({
            status: 'ok',
            data: res.body.data,
            compare: res.body.compare || null,
            executionMs: res.body.meta && res.body.meta.execution_ms,
          });
          if (this.props.onFetched) this.props.onFetched();
        }
      })
      .catch((e) => {
        if (e.name === 'AbortError') return;
        this.setState({ status: 'error', error: e.message });
      });
  };

  render() {
    const { widget } = this.props;
    const { status, data, compare, error, executionMs } = this.state;

    return (
      <div
        className="user-analytics-widget"
        style={{
          background: '#1c1c1c',
          border: '1px solid #333',
          borderRadius: 4,
          padding: 8,
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          minHeight: (widget.height || 2) * 130,
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            paddingBottom: 6,
            borderBottom: '1px solid #2a2a2a',
            marginBottom: 6,
          }}
        >
          <div style={{ color: '#fff', fontSize: '0.9rem', fontWeight: 600 }}>
            {widget.title || widget.query}
          </div>
          <div style={{ display: 'flex', gap: 4, fontSize: 11, color: '#666' }}>
            {executionMs != null && status === 'ok' && (
              <span style={{ marginRight: 4 }}>{executionMs} ms</span>
            )}
            {this.props.onMove && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: '#888', background: 'transparent', border: 'none' }}
                title="Move up"
                disabled={this.props.isFirst}
                onClick={() => this.props.onMove(widget.id, 'up')}
              >
                <i className="fas fa-arrow-up" />
              </button>
            )}
            {this.props.onMove && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: '#888', background: 'transparent', border: 'none' }}
                title="Move down"
                disabled={this.props.isLast}
                onClick={() => this.props.onMove(widget.id, 'down')}
              >
                <i className="fas fa-arrow-down" />
              </button>
            )}
            <button
              type="button"
              className="btn btn-sm"
              style={{ padding: '0 4px', color: '#888', background: 'transparent', border: 'none' }}
              title="Refresh"
              onClick={this.fetchData}
            >
              <i className="fas fa-sync" />
            </button>
            {this.props.onEdit && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: '#888', background: 'transparent', border: 'none' }}
                title="Edit widget"
                onClick={() => this.props.onEdit(widget.id)}
              >
                <i className="fas fa-edit" />
              </button>
            )}
            {this.props.onRemove && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: '#888', background: 'transparent', border: 'none' }}
                title="Remove widget"
                onClick={() => this.props.onRemove(widget.id)}
              >
                <i className="fas fa-trash" />
              </button>
            )}
          </div>
        </div>
        <div style={{ flex: 1, position: 'relative' }}>
          {status === 'loading' && (
            <div style={{ color: '#888', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              <i className="fas fa-spinner fa-spin" />
            </div>
          )}
          {status === 'empty' && (
            <div style={{ color: '#666', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              No data for this range
            </div>
          )}
          {status === 'error' && (
            <div style={{ color: '#f44336', padding: 8 }}>
              <div>{error}</div>
              <button type="button" className="btn btn-sm btn-secondary" onClick={this.fetchData}>
                Retry
              </button>
            </div>
          )}
          {status === 'ok' &&
            renderWidget(widget.type || 'line', {
              data,
              compare: this.state.compare ? { data: this.state.compare.data } : null,
              options: widget.options || {},
              height: '100%',
              onItemClick: this.handleItemClick,
            })}
        </div>
      </div>
    );
  }
}

// ============================================================================
// Grid — 4-column auto-flow layout
// ============================================================================

export class Grid extends Component {
  render() {
    const {
      widgets = [],
      filters,
      compare,
      refreshKey,
      onFetched,
      onRemoveWidget,
      onEditWidget,
      onMoveWidget,
      onDrillDown,
    } = this.props;
    return (
      <div
        className="user-analytics-grid"
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, 1fr)',
          gridAutoRows: '130px',
          gap: 12,
          marginTop: 8,
        }}
      >
        {widgets.map((w, i) => (
          <div
            key={w.id}
            style={{
              gridColumn: `span ${Math.max(1, Math.min(4, w.width || 4))}`,
              gridRow: `span ${Math.max(1, w.height || 2)}`,
              minHeight: 0,
            }}
          >
            <WidgetWrapper
              widget={w}
              filters={filters}
              compare={compare}
              refreshKey={refreshKey}
              onFetched={onFetched}
              onRemove={onRemoveWidget}
              onEdit={onEditWidget}
              onMove={onMoveWidget}
              onDrillDown={onDrillDown}
              isFirst={i === 0}
              isLast={i === widgets.length - 1}
            />
          </div>
        ))}
      </div>
    );
  }
}
