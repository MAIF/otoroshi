import React, { Component } from 'react';
import MonacoEditor from '@monaco-editor/react';
import moment from 'moment';

const FLUSH_INTERVAL_MS = 250;
const DEFAULT_BUFFER_SIZE = 10000;

// Expression grammar:
//   expr   := or
//   or     := and ('||' and)*
//   and    := unary ( ('&&')? unary )*       // && is optional → implicit AND
//   unary  := '!' unary | primary
//   primary:= '(' expr ')' | STRING
// STRING is a bare word (no whitespace, no ( ) ! " and no && ||) or a "double-quoted" string with \" and \\ escapes.
// Predicate input is the JSON line (already lowercased by the caller).

function tokenize(input) {
  const tokens = [];
  let i = 0;
  const n = input.length;
  const isWs = (c) => c === ' ' || c === '\t' || c === '\n' || c === '\r';
  while (i < n) {
    const c = input[i];
    if (isWs(c)) { i++; continue; }
    if (c === '(') { tokens.push({ type: 'LPAREN' }); i++; continue; }
    if (c === ')') { tokens.push({ type: 'RPAREN' }); i++; continue; }
    if (c === '!') { tokens.push({ type: 'NOT' }); i++; continue; }
    if (c === '&' && input[i + 1] === '&') { tokens.push({ type: 'AND' }); i += 2; continue; }
    if (c === '|' && input[i + 1] === '|') { tokens.push({ type: 'OR' }); i += 2; continue; }
    if (c === '"') {
      let s = '';
      i++;
      while (i < n && input[i] !== '"') {
        if (input[i] === '\\' && i + 1 < n) { s += input[i + 1]; i += 2; }
        else { s += input[i]; i++; }
      }
      if (i >= n) throw new Error('Unterminated string literal');
      i++;
      tokens.push({ type: 'STR', value: s });
      continue;
    }
    let s = '';
    while (i < n) {
      const ch = input[i];
      if (isWs(ch) || ch === '(' || ch === ')' || ch === '!' || ch === '"') break;
      if (ch === '&' && input[i + 1] === '&') break;
      if (ch === '|' && input[i + 1] === '|') break;
      s += ch;
      i++;
    }
    if (s.length === 0) throw new Error(`Unexpected character at ${i}`);
    tokens.push({ type: 'STR', value: s });
  }
  return tokens;
}

function parseExpression(input) {
  const tokens = tokenize(input);
  let pos = 0;
  const peek = () => tokens[pos];
  const eat = (type) => {
    const t = tokens[pos];
    if (!t || t.type !== type) throw new Error(`Expected ${type}`);
    pos++;
    return t;
  };
  const parsePrimary = () => {
    const t = peek();
    if (!t) throw new Error('Unexpected end of input');
    if (t.type === 'LPAREN') {
      pos++;
      const node = parseOr();
      eat('RPAREN');
      return node;
    }
    if (t.type === 'STR') {
      pos++;
      const needle = t.value.toLowerCase();
      return (line) => line.includes(needle);
    }
    throw new Error(`Unexpected token ${t.type}`);
  };
  const parseUnary = () => {
    if (peek() && peek().type === 'NOT') {
      pos++;
      const inner = parseUnary();
      return (line) => !inner(line);
    }
    return parsePrimary();
  };
  const parseAnd = () => {
    let left = parseUnary();
    while (true) {
      const t = peek();
      if (!t) break;
      if (t.type === 'AND') { pos++; const right = parseUnary(); const l = left; left = (line) => l(line) && right(line); continue; }
      if (t.type === 'STR' || t.type === 'NOT' || t.type === 'LPAREN') {
        const right = parseUnary();
        const l = left;
        left = (line) => l(line) && right(line);
        continue;
      }
      break;
    }
    return left;
  };
  const parseOr = () => {
    let left = parseAnd();
    while (peek() && peek().type === 'OR') {
      pos++;
      const right = parseAnd();
      const l = left;
      left = (line) => l(line) || right(line);
    }
    return left;
  };
  const predicate = parseOr();
  if (pos !== tokens.length) throw new Error(`Unexpected token ${tokens[pos].type}`);
  return predicate;
}

function compileFilter(input) {
  const trimmed = (input || '').trim();
  if (!trimmed) return { predicate: null, error: null };
  try {
    return { predicate: parseExpression(trimmed), error: null };
  } catch (err) {
    return { predicate: null, error: err.message || String(err) };
  }
}

export class GlobalNodeEventStreamPage extends Component {
  state = {
    events: [],
    filter: '',
    notFilter: '',
    bufferSize: DEFAULT_BUFFER_SIZE,
    selectedId: null,
    autoScroll: true,
  };

  buffer = [];
  nextId = 0;
  scrollerRef = React.createRef();
  exprCache = { match: { input: null, compiled: null }, exclude: { input: null, compiled: null } };

  getCompiledFilter = (kind, input) => {
    const cache = this.exprCache[kind];
    if (cache.input === input) return cache.compiled;
    const compiled = compileFilter(input);
    this.exprCache[kind] = { input, compiled };
    return compiled;
  };

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
    this.setState((prev) => {
      const next = prev.events.concat(batch);
      const cap = prev.bufferSize;
      const trimmed = next.length > cap ? next.slice(next.length - cap) : next;
      return { events: trimmed };
    });
  };

  onBufferSizeChange = (e) => {
    const raw = parseInt(e.target.value, 10);
    const size = Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_BUFFER_SIZE;
    this.setState((prev) => ({
      bufferSize: size,
      events: prev.events.length > size ? prev.events.slice(prev.events.length - size) : prev.events,
    }));
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

  stepSelected = (events, delta) => {
    if (!events || events.length === 0) return;
    const idx = events.findIndex((e) => e.id === this.state.selectedId);
    if (idx < 0) return;
    const nextIdx = idx + delta;
    if (nextIdx < 0 || nextIdx >= events.length) return;
    this.setState({ selectedId: events[nextIdx].id });
  };

  filteredEvents = (matchPred, excludePred) => {
    if (!matchPred && !excludePred) return this.state.events;
    return this.state.events.filter((e) => {
      const line = e.line.toLowerCase();
      if (matchPred && !matchPred(line)) return false;
      if (excludePred && excludePred(line)) return false;
      return true;
    });
  };

  render() {
    const matchCompiled = this.getCompiledFilter('match', this.state.filter);
    const excludeCompiled = this.getCompiledFilter('exclude', this.state.notFilter);
    const events = this.filteredEvents(matchCompiled.predicate, excludeCompiled.predicate);
    const total = this.state.events.length;
    const shown = events.length;
    const matchError = matchCompiled.error;
    const excludeError = excludeCompiled.error;
    const inputStyle = (err) => ({
      maxWidth: 320,
      ...(err ? { borderColor: '#d9534f', boxShadow: '0 0 0 1px #d9534f' } : {}),
    });
    const syntaxHint = 'Syntax: foo && bar, foo || bar, !foo, ( ), "quoted str"';
    const selected =
      this.state.selectedId !== null
        ? this.state.events.find((e) => e.id === this.state.selectedId)
        : null;
    const selectedIndex = selected ? events.findIndex((e) => e.id === selected.id) : -1;
    const hasPrev = selectedIndex > 0;
    const hasNext = selectedIndex >= 0 && selectedIndex < events.length - 1;

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
            placeholder='Match: foo && (bar || !baz)'
            value={this.state.filter}
            onChange={(e) => this.setState({ filter: e.target.value })}
            style={inputStyle(matchError)}
            title={matchError ? `Match: ${matchError}` : syntaxHint}
          />
          <input
            type="text"
            className="form-control"
            placeholder='Exclude: foo || bar'
            value={this.state.notFilter}
            onChange={(e) => this.setState({ notFilter: e.target.value })}
            style={inputStyle(excludeError)}
            title={excludeError ? `Exclude: ${excludeError}` : syntaxHint}
          />
          <div
            style={{
              flex: 1,
              color: 'var(--text, #ccc)',
              fontFamily: 'monospace',
              fontSize: 13,
            }}
          >
            shown <strong>{shown}</strong> / in memory <strong>{total}</strong> / cap{' '}
            <strong>{this.state.bufferSize}</strong>
            {!this.state.autoScroll && (
              <span style={{ marginLeft: 12, color: '#f1b850' }}>auto-scroll paused</span>
            )}
          </div>
          <label
            style={{
              color: 'var(--text, #ccc)',
              fontFamily: 'monospace',
              fontSize: 13,
              marginBottom: 0,
            }}
          >
            buffer
          </label>
          <input
            type="number"
            min={1}
            step={100}
            className="form-control"
            value={this.state.bufferSize}
            onChange={this.onBufferSizeChange}
            style={{ width: 110 }}
            title="Maximum events kept in memory; oldest get dropped when exceeded"
          />
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
                <span style={{ color: '#888', fontSize: 12 }}>
                  {selectedIndex >= 0 ? `${selectedIndex + 1} / ${events.length}` : '— / —'}
                </span>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => this.stepSelected(events, -1)}
                  disabled={!hasPrev}
                  title="Previous event (in current filter view)"
                >
                  <i className="fas fa-chevron-up" />
                </button>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => this.stepSelected(events, 1)}
                  disabled={!hasNext}
                  title="Next event (in current filter view)"
                >
                  <i className="fas fa-chevron-down" />
                </button>
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
