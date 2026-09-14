import React from 'react';
import {
  AreaChart,
  Area,
  LineChart,
  Line,
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';

// ============================================================================
// Formatting helpers
// ============================================================================

const MS = 1000;

function formatCurrency(v, decimals, currency) {
  const code = currency || 'USD';
  const abs = Math.abs(v);
  try {
    if (v === 0) {
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: code,
        maximumFractionDigits: 0,
      }).format(0);
    }
    // per-call prices live far below a dollar: two significant digits rather than two decimals, or
    // amounts round to 0 and neighbouring axis ticks all read "0.01"
    if (abs < 1 && decimals == null) {
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: code,
        maximumSignificantDigits: 2,
      }).format(v);
    }
    if (abs >= 1000000) {
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: code,
        notation: 'compact',
        maximumFractionDigits: 1,
      }).format(v);
    }
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: code,
      minimumFractionDigits: 0,
      maximumFractionDigits: decimals != null ? decimals : 2,
    }).format(v);
  } catch (e) {
    // an unknown currency code must not break the widget
    return `${v.toLocaleString(undefined, {
      maximumFractionDigits: decimals != null ? decimals : 2,
    })} ${code}`;
  }
}

/** Fixed decimals when asked; otherwise as many as the magnitude deserves: 1234, 56.19, 0.0389. */
function numberPrecision(n, decimals) {
  if (decimals != null) return { maximumFractionDigits: decimals };
  const abs = Math.abs(n);
  if (abs === 0 || abs >= 100) return { maximumFractionDigits: 0 };
  if (abs >= 1) return { maximumFractionDigits: 2 };
  return { maximumSignificantDigits: 3 };
}

/**
 * Wide enough for the longest tick label. Recharts gives the value axis a fixed 60px and clips
 * anything longer, which currencies and units routinely are.
 */
function axisWidth(fmt, values) {
  const finite = values.filter((v) => typeof v === 'number' && isFinite(v));
  if (!finite.length) return 60;
  const candidates = [0, Math.max(...finite), Math.min(...finite), Math.max(...finite) / 3];
  const longest = Math.max(...candidates.map((v) => String(fmt(v)).length));
  return Math.min(140, Math.max(40, longest * 7 + 14));
}

/**
 * `decimals` is the maximum number of fraction digits, `unit` a suffix appended to plain numbers
 * (count, number, compact) — "Wh", "gCO2eq", "tokens/s" — and `currency` an ISO code for the
 * currency format.
 */
function formatValue(format, decimals, unit, currency) {
  const withUnit = (s) => (unit ? `${s} ${unit}` : s);
  return (v) => {
    if (v == null || isNaN(v)) return '-';
    const n = Number(v);
    switch (format) {
      case 'count':
        return withUnit(
          n.toLocaleString(
            undefined,
            decimals != null ? { maximumFractionDigits: decimals } : undefined
          )
        );
      case 'number':
        return withUnit(n.toLocaleString(undefined, numberPrecision(n, decimals)));
      case 'compact':
        return withUnit(
          new Intl.NumberFormat(undefined, {
            notation: 'compact',
            maximumFractionDigits: decimals != null ? decimals : 1,
          }).format(n)
        );
      case 'currency':
        return formatCurrency(n, decimals, currency);
      case 'rps':
        return `${n.toFixed(decimals || 2)} req/s`;
      case 'ms':
        return `${Math.round(n)} ms`;
      case 'percent':
        return `${(n * 100).toFixed(decimals || 1)} %`;
      case 'bytes':
        return formatBytes(n);
      default:
        return withUnit(n.toLocaleString());
    }
  };
}

const formatterOf = (options = {}) =>
  formatValue(options.format || 'count', options.decimals, options.unit, options.currency);

function formatBytes(b) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
  return `${(b / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function formatTs(ts) {
  const d = new Date(ts);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`;
}

function formatTsShort(ts) {
  const d = new Date(ts);
  return d.toISOString().slice(11, 16);
}

const PALETTE = [
  '#2196f3',
  '#ff9800',
  '#f44336',
  '#4caf50',
  '#9c27b0',
  '#00bcd4',
  '#cddc39',
  '#795548',
  '#607d8b',
  '#e91e63',
];

// ============================================================================
// Timeseries → unified series shape used by Line/Area
// ============================================================================

function toSeries(data) {
  if (!data) return [];
  if (data.series && Array.isArray(data.series)) return data.series;
  if (data.points && Array.isArray(data.points)) return [{ name: 'value', points: data.points }];
  return [];
}

/** Pivot list of series ({name, points: [{ts, value}]}) into a flat array of
 *  rows for Recharts (one entry per timestamp, one key per series).
 *
 *  Rows are keyed by series index, never by series name: Recharts reads a string dataKey as a
 *  lookup path, so a series named `gpt-4.1` would be looked up as `gpt-4` → `1` and draw nothing.
 *  The name is only ever used as a label.
 */
const seriesKey = (i) => `s${i}`;
const prevSeriesKey = (i) => `s${i}_prev`;

function pivotForRecharts(series, compareSeries) {
  const allTs = new Set();
  series.forEach((s) => s.points.forEach((p) => allTs.add(p.ts)));
  const sorted = Array.from(allTs).sort((a, b) => a - b);
  const map = sorted.map((ts) => ({ ts }));
  const index = new Map(sorted.map((ts, i) => [ts, i]));
  series.forEach((s, i) => {
    s.points.forEach((p) => {
      map[index.get(p.ts)][seriesKey(i)] = p.value;
    });
  });
  if (compareSeries && compareSeries.length) {
    compareSeries.forEach((s, i) => {
      s.points.forEach((p, j) => {
        // Align previous period points by index, not by absolute timestamp.
        if (map[j]) map[j][prevSeriesKey(i)] = p.value;
      });
    });
  }
  return map;
}

// ============================================================================
// Line / Area
// ============================================================================

function TimeseriesChart({ data, compare, options = {}, height, ChartCmp, AreaOrLine }) {
  const series = toSeries(data);
  const compareSeries = compare ? toSeries(compare.data) : null;
  const flat = pivotForRecharts(series, compareSeries);
  const fmt = formatterOf(options);
  const yWidth = axisWidth(
    fmt,
    flat.flatMap((row) =>
      Object.keys(row)
        .filter((k) => k !== 'ts')
        .map((k) => row[k])
    )
  );
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <ChartCmp data={flat}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(127,127,127,0.25)" />
        <XAxis
          dataKey="ts"
          tickFormatter={formatTsShort}
          stroke="currentColor"
          tick={{ fill: 'currentColor', opacity: 0.7 }}
        />
        <YAxis
          tickFormatter={fmt}
          width={yWidth}
          stroke="currentColor"
          tick={{ fill: 'currentColor', opacity: 0.7 }}
        />
        <Tooltip
          labelFormatter={formatTs}
          formatter={(v) => fmt(v)}
          contentStyle={{
            background: 'var(--bg-color_level3)',
            border: '1px solid var(--border-color)',
            color: 'var(--text)',
          }}
        />
        {options.legend !== false && <Legend />}
        {series.map((s, i) => (
          <AreaOrLine
            key={seriesKey(i)}
            type="monotone"
            dataKey={seriesKey(i)}
            name={s.name}
            stackId={options.stacked ? 'stack' : undefined}
            stroke={PALETTE[i % PALETTE.length]}
            fill={PALETTE[i % PALETTE.length]}
            fillOpacity={0.3}
            isAnimationActive={false}
          />
        ))}
        {compareSeries &&
          compareSeries.map((s, i) => (
            <AreaOrLine
              key={prevSeriesKey(i)}
              type="monotone"
              dataKey={prevSeriesKey(i)}
              name={`${s.name} (previous period)`}
              stackId={options.stacked ? 'stack_prev' : undefined}
              stroke={PALETTE[i % PALETTE.length]}
              strokeDasharray="3 3"
              fill="none"
              isAnimationActive={false}
            />
          ))}
      </ChartCmp>
    </ResponsiveContainer>
  );
}

export function LineWidget(props) {
  return <TimeseriesChart {...props} ChartCmp={LineChart} AreaOrLine={Line} />;
}

export function AreaWidget(props) {
  return <TimeseriesChart {...props} ChartCmp={AreaChart} AreaOrLine={Area} />;
}

// ============================================================================
// Bar (TopN horizontal-ish)
// ============================================================================

export function BarWidget({ data, options = {}, height, onItemClick }) {
  const items = (data && data.items) || [];
  const fmt = formatterOf(options);
  const onClick = onItemClick
    ? (e) => {
        if (e && e.activePayload && e.activePayload[0]) {
          onItemClick(e.activePayload[0].payload);
        }
      }
    : undefined;
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <BarChart
        data={items}
        layout="vertical"
        margin={{ left: 60 }}
        onClick={onClick}
        style={onItemClick ? { cursor: 'pointer' } : undefined}
      >
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(127,127,127,0.25)" />
        <XAxis
          type="number"
          tickFormatter={fmt}
          stroke="currentColor"
          tick={{ fill: 'currentColor', opacity: 0.7 }}
        />
        <YAxis
          type="category"
          dataKey="label"
          width={150}
          stroke="currentColor"
          tick={{ fontSize: 11, fill: 'currentColor', opacity: 0.7 }}
        />
        <Tooltip
          formatter={(v) => fmt(v)}
          contentStyle={{
            background: 'var(--bg-color_level3)',
            border: '1px solid var(--border-color)',
            color: 'var(--text)',
          }}
        />
        <Bar dataKey="value" fill={PALETTE[0]} isAnimationActive={false}>
          {items.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

// ============================================================================
// Pie / Donut
// ============================================================================

function PieBase({ data, options = {}, height, innerRadius, onItemClick }) {
  const items = (data && data.items) || [];
  const fmt = formatterOf(options);
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <PieChart style={onItemClick ? { cursor: 'pointer' } : undefined}>
        <Pie
          data={items}
          dataKey="value"
          nameKey="key"
          outerRadius="68%"
          innerRadius={innerRadius || 0}
          isAnimationActive={false}
          label={(e) => e.key}
          onClick={onItemClick ? (payload) => onItemClick(payload) : undefined}
        >
          {items.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Pie>
        <Tooltip
          formatter={(v) => fmt(v)}
          contentStyle={{
            background: 'var(--bg-color_level3)',
            border: '1px solid var(--border-color)',
            color: 'var(--text)',
          }}
        />
        {options.legend !== false && <Legend />}
      </PieChart>
    </ResponsiveContainer>
  );
}

export function PieWidget(props) {
  return <PieBase {...props} />;
}

export function DonutWidget(props) {
  return <PieBase {...props} innerRadius="50%" />;
}

// ============================================================================
// Scalar (big number with optional sparkline)
// ============================================================================

export function ScalarWidget({ data, options = {}, height }) {
  const value = data && (data.value != null ? data.value : 0);
  const fmt = formatterOf(options);
  const color = pickThresholdColor(value, options.thresholds);
  return (
    <div
      style={{
        height: height || '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 12,
      }}
    >
      <div style={{ fontSize: '2.5rem', color: color || 'var(--text)', fontWeight: 600 }}>
        {fmt(value)}
      </div>
      {data && data.label && (
        <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: 4 }}>
          {data.label}
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Metric (text + value)
// ============================================================================

export function MetricWidget({ data, options = {}, height }) {
  const value = data && data.value;
  const label = (data && data.label) || options.title || '';
  const fmt = formatterOf(options);
  const color = pickThresholdColor(value, options.thresholds);
  return (
    <div
      style={{
        height: height || '100%',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        padding: 16,
      }}
    >
      <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', display: 'none' }}>
        {label}
      </div>
      <div style={{ fontSize: '2rem', color: color || 'var(--text)', fontWeight: 600 }}>
        {value == null ? '-' : fmt(value)}
      </div>
    </div>
  );
}

function pickThresholdColor(value, thresholds) {
  if (!thresholds || !Array.isArray(thresholds) || value == null) return null;
  const sorted = [...thresholds].sort((a, b) => a.value - b.value);
  let color = null;
  for (const t of sorted) {
    if (value >= t.value) color = t.color;
  }
  return color;
}

// ============================================================================
// Table
// ============================================================================

// bootstrap paints table cells with its own body background and emphasis color, which are the light
// theme's whatever the backoffice theme is: rebind them to the backoffice's variables
const TABLE_THEME = {
  '--bs-table-bg': 'transparent',
  '--bs-table-color': 'var(--text)',
  '--bs-table-border-color': 'var(--border-color)',
  '--bs-table-hover-bg': 'var(--bg-color_level3)',
  '--bs-table-hover-color': 'var(--text)',
  color: 'var(--text)',
  marginBottom: 0,
};

const TABLE_HEADER = {
  position: 'sticky',
  top: 0,
  zIndex: 1,
  background: 'var(--bg-color_level2)',
  color: 'var(--text-muted)',
  fontWeight: 600,
  whiteSpace: 'nowrap',
  borderBottom: '1px solid var(--border-color-strong)',
};

function formatCell(v) {
  if (v == null) return '-';
  if (typeof v === 'number') return v.toLocaleString(undefined, { maximumFractionDigits: 6 });
  return String(v);
}

export function TableWidget({ data, options = {}, height, onItemClick }) {
  const items = (data && (data.items || data.rows)) || [];
  const fmt = formatterOf(options);
  const cols = items.length > 0 ? Object.keys(items[0]).filter((k) => k !== 'key') : [];
  // numbers read down a column only when they line up on the right
  const numeric = cols.filter(
    (c) => c === 'value' || items.every((row) => row[c] == null || typeof row[c] === 'number')
  );
  const align = (c) => (numeric.includes(c) ? { textAlign: 'right' } : undefined);
  return (
    <div style={{ height: height || 220, overflow: 'auto' }}>
      <table className={`table table-sm${onItemClick ? ' table-hover' : ''}`} style={TABLE_THEME}>
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c} style={{ ...TABLE_HEADER, ...align(c) }}>
                {c.replace(/_/g, ' ')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((row, i) => (
            <tr
              key={i}
              onClick={onItemClick ? () => onItemClick(row) : undefined}
              style={onItemClick ? { cursor: 'pointer' } : undefined}
            >
              {cols.map((c) => (
                <td key={c} style={align(c)}>
                  {c === 'value' ? fmt(row[c]) : formatCell(row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ============================================================================
// Heatmap (custom SVG)
// ============================================================================

export function HeatmapWidget({ data, options = {}, height }) {
  // columns are time buckets by default; a query can name them instead (`xLabels`) when they are
  // not instants — hours of the day, days of the week, latency bands…
  const xLabels = (data && Array.isArray(data.xLabels) && data.xLabels) || null;
  const xBuckets = xLabels || (data && data.xBuckets) || [];
  const yBuckets = (data && data.yBuckets) || [];
  const values = (data && data.values) || [];
  const h = height || 260;
  const fmt = formatterOf(options);
  const columnLabel = (i) => (xLabels ? String(xLabels[i]) : formatTsShort(xBuckets[i]));
  const columnTitle = (i) => (xLabels ? String(xLabels[i]) : formatTs(xBuckets[i]));

  const rows = yBuckets.length;
  const cols = xBuckets.length;
  if (!rows || !cols) {
    return <div style={{ color: 'var(--text-muted)', padding: 16 }}>No data</div>;
  }

  // Find max for color scaling
  let max = 0;
  values.forEach((row) =>
    row.forEach((v) => {
      if (v > max) max = v;
    })
  );

  const cellH = Math.max(8, Math.floor((h - 30) / rows));
  return (
    <div style={{ height: h, overflow: 'auto', padding: 8 }}>
      <table
        style={{
          borderCollapse: 'collapse',
          fontSize: 11,
          color: 'var(--text)',
          width: '100%',
          tableLayout: 'fixed',
        }}
      >
        <thead>
          <tr>
            <th style={{ padding: 2, width: 72 }}></th>
            {xBuckets.map((_, i) => (
              <th
                key={i}
                style={{
                  padding: 2,
                  fontWeight: 'normal',
                  color: 'var(--text-muted)',
                  whiteSpace: 'nowrap',
                  overflow: 'visible',
                }}
              >
                {i % Math.ceil(cols / 8) === 0 ? columnLabel(i) : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {yBuckets.map((label, y) => (
            <tr key={y}>
              <td style={{ padding: 2, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                {label}
              </td>
              {xBuckets.map((_, x) => {
                const v = (values[y] && values[y][x]) || 0;
                const intensity = max > 0 ? v / max : 0;
                const bg = `rgba(33, 150, 243, ${intensity.toFixed(2)})`;
                return (
                  <td
                    key={x}
                    title={`${label} @ ${columnTitle(x)}: ${fmt(v)}`}
                    style={{
                      height: cellH,
                      background: bg,
                      border: '1px solid var(--bg-color_level1)',
                    }}
                  />
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ============================================================================
// Public registry
// ============================================================================

export const WIDGETS = {
  line: LineWidget,
  area: AreaWidget,
  bar: BarWidget,
  pie: PieWidget,
  donut: DonutWidget,
  scalar: ScalarWidget,
  metric: MetricWidget,
  table: TableWidget,
  heatmap: HeatmapWidget,
};

export function renderWidget(type, props) {
  const Cmp = WIDGETS[type] || WIDGETS.line;
  return <Cmp {...props} />;
}
