import React, { Component } from 'react';
import MonacoEditor from '@monaco-editor/react';
import moment from 'moment';

const FLUSH_INTERVAL_MS = 250;

export class GlobalNodeEventStreamPage extends Component {
  state = {
    events: [],
    filter: '',
    notFilter: '',
    selectedId: null,
    autoScroll: true,
  };

  buffer = [];
  nextId = 0;
  scrollerRef = React.createRef();

  componentDidMount() {
    this.props.setTitle('Node Event Stream');
    this.lockParentScroll();
    this.evtSource = new EventSource('/bo/api/proxy/api/node/eventstream');
    this.evtSource.onmessage = this.onMessage;
    this.evtSource.onerror = (e) => console.error('node eventstream error', e);
    this.flushTimer = setInterval(this.flush, FLUSH_INTERVAL_MS);
  }

  componentWillUnmount() {
    this.restoreParentScroll();
    if (this.evtSource) {
      this.evtSource.close();
      this.evtSource = null;
    }
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
    }
  }

  lockParentScroll = () => {
    const container = document.getElementById('content-scroll-container');
    if (!container) return;
    this.savedParentStyles = {
      overflow: container.style.overflow,
      paddingBottom: container.style.paddingBottom,
      height: container.style.height,
    };
    container.style.overflow = 'hidden';
    container.style.paddingBottom = '0';
    container.style.height = 'calc(100vh - 52px)';
  };

  restoreParentScroll = () => {
    const container = document.getElementById('content-scroll-container');
    if (!container || !this.savedParentStyles) return;
    container.style.overflow = this.savedParentStyles.overflow;
    container.style.paddingBottom = this.savedParentStyles.paddingBottom;
    container.style.height = this.savedParentStyles.height;
  };

  componentDidUpdate(prevProps, prevState) {
    if (this.state.autoScroll && prevState.events.length !== this.state.events.length) {
      const el = this.scrollerRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    }
  }

  onMessage = (e) => {
    try {
      const data = JSON.parse(e.data);
      const ts = data['@timestamp'] || data.timestamp || Date.now();
      const id = this.nextId++;
      this.buffer.push({
        id,
        ts: typeof ts === 'number' ? ts : new Date(ts).getTime(),
        type: data['@type'] || 'event',
        raw: data,
        line: JSON.stringify(data),
      });
    } catch (err) {
      console.error('failed to parse event', err, e.data);
    }
  };

  flush = () => {
    if (this.buffer.length === 0) return;
    const batch = this.buffer;
    this.buffer = [];
    this.setState((prev) => ({ events: prev.events.concat(batch) }));
  };

  onScroll = () => {
    const el = this.scrollerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 8;
    if (atBottom !== this.state.autoScroll) {
      this.setState({ autoScroll: atBottom });
    }
  };

  clear = () => {
    this.buffer = [];
    this.setState({ events: [], selectedId: null });
  };

  selectEvent = (id) => {
    this.setState({ selectedId: id });
  };

  closePanel = () => {
    this.setState({ selectedId: null });
  };

  filteredEvents = () => {
    const f = this.state.filter.trim().toLowerCase();
    const nf = this.state.notFilter.trim().toLowerCase();
    if (!f && !nf) return this.state.events;
    return this.state.events.filter((e) => {
      const line = e.line.toLowerCase();
      if (f && !line.includes(f)) return false;
      if (nf && line.includes(nf)) return false;
      return true;
    });
  };

  render() {
    const events = this.filteredEvents();
    const total = this.state.events.length;
    const shown = events.length;
    const selected =
      this.state.selectedId !== null
        ? this.state.events.find((e) => e.id === this.state.selectedId)
        : null;

    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            padding: '8px 12px',
            background: 'var(--bg-color_level2, #2a2a2a)',
            borderBottom: '1px solid var(--bg-color_level3, #444)',
          }}
        >
          <input
            type="text"
            className="form-control"
            placeholder="Match (substring on JSON)…"
            value={this.state.filter}
            onChange={(e) => this.setState({ filter: e.target.value })}
            style={{ maxWidth: 320 }}
          />
          <input
            type="text"
            className="form-control"
            placeholder="Exclude (substring on JSON)…"
            value={this.state.notFilter}
            onChange={(e) => this.setState({ notFilter: e.target.value })}
            style={{ maxWidth: 320 }}
          />
          <div
            style={{
              flex: 1,
              color: 'var(--text, #ccc)',
              fontFamily: 'monospace',
              fontSize: 13,
            }}
          >
            shown <strong>{shown}</strong> / in memory <strong>{total}</strong>
            {!this.state.autoScroll && (
              <span style={{ marginLeft: 12, color: '#f1b850' }}>auto-scroll paused</span>
            )}
          </div>
          <button type="button" className="btn btn-sm btn-danger" onClick={this.clear}>
            <i className="fas fa-trash" /> Clear
          </button>
        </div>

        <div
          ref={this.scrollerRef}
          onScroll={this.onScroll}
          style={{
            flex: 1,
            overflowY: 'auto',
            background: '#111',
            color: '#d4d4d4',
            fontFamily: 'Menlo, Consolas, monospace',
            fontSize: 12,
            padding: '4px 0',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
            <colgroup>
              <col style={{ width: 200 }} />
              <col />
            </colgroup>
            <tbody>
              {events.map((evt) => {
                const isSelected = evt.id === this.state.selectedId;
                return (
                  <tr
                    key={evt.id}
                    onClick={() => this.selectEvent(evt.id)}
                    style={{
                      cursor: 'pointer',
                      background: isSelected ? '#264f78' : 'transparent',
                    }}
                  >
                    <td
                      style={{
                        padding: '2px 12px',
                        color: '#7fbf7f',
                        whiteSpace: 'nowrap',
                        verticalAlign: 'top',
                      }}
                    >
                      {moment(evt.ts).format('YYYY-MM-DD HH:mm:ss.SSS')}
                    </td>
                    <td
                      style={{
                        padding: '2px 12px',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      <span style={{ color: '#569cd6' }}>{evt.type}</span>
                      <span style={{ color: '#666' }}> · </span>
                      <span>{evt.line}</span>
                    </td>
                  </tr>
                );
              })}
              {events.length === 0 && (
                <tr>
                  <td colSpan={2} style={{ padding: 16, color: '#666', textAlign: 'center' }}>
                    {total === 0 ? 'Waiting for events…' : 'No events match the current filter.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {selected && (
          <>
            <div
              onClick={this.closePanel}
              style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(0,0,0,0.4)',
                zIndex: 1040,
              }}
            />
            <div
              style={{
                position: 'fixed',
                top: 0,
                right: 0,
                bottom: 0,
                width: 'min(720px, 70vw)',
                background: '#1e1e1e',
                boxShadow: '-4px 0 16px rgba(0,0,0,0.4)',
                zIndex: 1050,
                display: 'flex',
                flexDirection: 'column',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  padding: '10px 14px',
                  borderBottom: '1px solid #333',
                  color: '#d4d4d4',
                  fontFamily: 'monospace',
                }}
              >
                <div
                  style={{
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <span style={{ color: '#569cd6' }}>{selected.type}</span>
                  <span style={{ color: '#666', margin: '0 8px' }}>·</span>
                  <span style={{ color: '#7fbf7f' }}>
                    {moment(selected.ts).format('YYYY-MM-DD HH:mm:ss.SSS')}
                  </span>
                </div>
                <button type="button" className="btn btn-sm btn-secondary" onClick={this.closePanel}>
                  <i className="fas fa-times" /> Close
                </button>
              </div>
              <div style={{ flex: 1, minHeight: 0 }}>
                <MonacoEditor
                  height="100%"
                  width="100%"
                  theme="vs-dark"
                  defaultLanguage="json"
                  value={JSON.stringify(selected.raw, null, 2)}
                  options={{
                    readOnly: true,
                    automaticLayout: true,
                    minimap: { enabled: false },
                    wordWrap: 'on',
                    scrollBeyondLastLine: false,
                  }}
                />
              </div>
            </div>
          </>
        )}
      </div>
    );
  }
}
