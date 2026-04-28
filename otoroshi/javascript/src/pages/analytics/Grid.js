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
          background: 'var(--bg-color_level2)',
          border: '1px solid var(--border-color)',
          color: 'var(--text)',
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
            borderBottom: '1px solid var(--border-color)',
            marginBottom: 6,
          }}
        >
          <div
            style={{
              color: 'var(--text)',
              fontSize: '0.9rem',
              fontWeight: 600,
              display: 'flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            {this.props.draggable && (
              <span
                title="Drag to reorder"
                style={{ cursor: 'grab', color: 'var(--text-muted)', fontSize: 12 }}
              >
                <i className="fas fa-arrows-alt" />
              </span>
            )}
            {widget.title || widget.query}
          </div>
          <div style={{ display: 'flex', gap: 4, fontSize: 11, color: 'var(--text-muted)' }}>
            {executionMs != null && status === 'ok' && (
              <span style={{ marginRight: 4 }}>{executionMs} ms</span>
            )}
            {this.props.onMove && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: 'var(--text-muted)', background: 'transparent', border: 'none' }}
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
                style={{ padding: '0 4px', color: 'var(--text-muted)', background: 'transparent', border: 'none' }}
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
              style={{ padding: '0 4px', color: 'var(--text-muted)', background: 'transparent', border: 'none' }}
              title="Refresh"
              onClick={this.fetchData}
            >
              <i className="fas fa-sync" />
            </button>
            {this.props.onEdit && (
              <button
                type="button"
                className="btn btn-sm"
                style={{ padding: '0 4px', color: 'var(--text-muted)', background: 'transparent', border: 'none' }}
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
                style={{ padding: '0 4px', color: 'var(--text-muted)', background: 'transparent', border: 'none' }}
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
            <div style={{ color: 'var(--text-muted)', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              <i className="fas fa-spinner fa-spin" />
            </div>
          )}
          {status === 'empty' && (
            <div style={{ color: 'var(--text-muted)', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              No data for this range
            </div>
          )}
          {status === 'error' && (
            <div style={{ color: 'var(--color-red)', padding: 8 }}>
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
  state = { draggedId: null, hoverId: null };

  onDragStart = (id) => (e) => {
    try {
      e.dataTransfer.setData('text/plain', id);
      e.dataTransfer.effectAllowed = 'move';
    } catch (_) {}
    this.setState({ draggedId: id });
  };

  onDragOver = (id) => (e) => {
    if (!this.state.draggedId) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (this.state.hoverId !== id) this.setState({ hoverId: id });
  };

  onDragLeave = () => {
    this.setState({ hoverId: null });
  };

  onDrop = (id) => (e) => {
    e.preventDefault();
    const src = this.state.draggedId || e.dataTransfer.getData('text/plain');
    this.setState({ draggedId: null, hoverId: null });
    if (!src || !id || src === id) return;
    if (this.props.onReorderWidget) this.props.onReorderWidget(src, id);
  };

  onDragEnd = () => {
    this.setState({ draggedId: null, hoverId: null });
  };

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
      onReorderWidget,
      onDrillDown,
    } = this.props;
    const dndEnabled = !!onReorderWidget;
    const { draggedId, hoverId } = this.state;
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
        {widgets.map((w, i) => {
          const isDragged = draggedId === w.id;
          const isHovered = hoverId === w.id && draggedId && draggedId !== w.id;
          return (
            <div
              key={w.id}
              draggable={dndEnabled}
              onDragStart={dndEnabled ? this.onDragStart(w.id) : undefined}
              onDragOver={dndEnabled ? this.onDragOver(w.id) : undefined}
              onDragLeave={dndEnabled ? this.onDragLeave : undefined}
              onDrop={dndEnabled ? this.onDrop(w.id) : undefined}
              onDragEnd={dndEnabled ? this.onDragEnd : undefined}
              style={{
                gridColumn: `span ${Math.max(1, Math.min(4, w.width || 4))}`,
                gridRow: `span ${Math.max(1, w.height || 2)}`,
                minHeight: 0,
                opacity: isDragged ? 0.4 : 1,
                outline: isHovered ? '2px dashed var(--color-primary)' : 'none',
                outlineOffset: -4,
                transition: 'outline 0.1s, opacity 0.1s',
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
                draggable={dndEnabled}
                isFirst={i === 0}
                isLast={i === widgets.length - 1}
              />
            </div>
          );
        })}
      </div>
    );
  }
}
