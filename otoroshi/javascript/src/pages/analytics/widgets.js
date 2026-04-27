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

function formatValue(format, decimals = 0) {
  return (v) => {
    if (v == null || isNaN(v)) return '-';
    switch (format) {
      case 'count':
        return Number(v).toLocaleString();
      case 'rps':
        return `${Number(v).toFixed(decimals || 2)} req/s`;
      case 'ms':
        return `${Math.round(Number(v))} ms`;
      case 'percent':
        return `${(Number(v) * 100).toFixed(decimals || 1)} %`;
      case 'bytes':
        return formatBytes(Number(v));
      default:
        return Number(v).toLocaleString();
    }
  };
}

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
 */
function pivotForRecharts(series, compareSeries) {
  const allTs = new Set();
  series.forEach((s) => s.points.forEach((p) => allTs.add(p.ts)));
  const sorted = Array.from(allTs).sort((a, b) => a - b);
  const map = sorted.map((ts) => ({ ts }));
  const idx = (ts) => sorted.indexOf(ts);
  series.forEach((s) => {
    s.points.forEach((p) => {
      map[idx(p.ts)][s.name] = p.value;
    });
  });
  if (compareSeries && compareSeries.length) {
    compareSeries.forEach((s) => {
      s.points.forEach((p, i) => {
        // Align previous period points by index, not by absolute timestamp.
        if (map[i]) map[i][`${s.name}_prev`] = p.value;
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
  const fmt = formatValue(options.format || 'count', options.decimals);
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <ChartCmp data={flat}>
        <CartesianGrid strokeDasharray="3 3" stroke="#444" />
        <XAxis dataKey="ts" tickFormatter={formatTsShort} stroke="#aaa" />
        <YAxis tickFormatter={fmt} stroke="#aaa" />
        <Tooltip
          labelFormatter={formatTs}
          formatter={(v) => fmt(v)}
          contentStyle={{ background: '#222', border: '1px solid #444' }}
        />
        {options.legend !== false && <Legend />}
        {series.map((s, i) => (
          <AreaOrLine
            key={s.name}
            type="monotone"
            dataKey={s.name}
            stroke={PALETTE[i % PALETTE.length]}
            fill={PALETTE[i % PALETTE.length]}
            fillOpacity={0.3}
            isAnimationActive={false}
          />
        ))}
        {compareSeries &&
          compareSeries.map((s, i) => (
            <AreaOrLine
              key={`${s.name}_prev`}
              type="monotone"
              dataKey={`${s.name}_prev`}
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

export function BarWidget({ data, options = {}, height }) {
  const items = (data && data.items) || [];
  const fmt = formatValue(options.format || 'count', options.decimals);
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <BarChart data={items} layout="vertical" margin={{ left: 60 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#444" />
        <XAxis type="number" tickFormatter={fmt} stroke="#aaa" />
        <YAxis
          type="category"
          dataKey="label"
          width={150}
          stroke="#aaa"
          tick={{ fontSize: 11 }}
        />
        <Tooltip
          formatter={(v) => fmt(v)}
          contentStyle={{ background: '#222', border: '1px solid #444' }}
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

function PieBase({ data, options = {}, height, innerRadius }) {
  const items = (data && data.items) || [];
  const fmt = formatValue(options.format || 'count', options.decimals);
  return (
    <ResponsiveContainer width="100%" height={height || 220}>
      <PieChart>
        <Pie
          data={items}
          dataKey="value"
          nameKey="key"
          outerRadius="80%"
          innerRadius={innerRadius || 0}
          isAnimationActive={false}
          label={(e) => e.key}
        >
          {items.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Pie>
        <Tooltip
          formatter={(v) => fmt(v)}
          contentStyle={{ background: '#222', border: '1px solid #444' }}
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
  const fmt = formatValue(options.format || 'count', options.decimals);
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
      <div style={{ fontSize: '2.5rem', color: color || '#fff', fontWeight: 600 }}>
        {fmt(value)}
      </div>
      {data && data.label && (
        <div style={{ fontSize: '0.85rem', color: '#aaa', marginTop: 4 }}>{data.label}</div>
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
  const fmt = formatValue(options.format || 'count', options.decimals);
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
      <div style={{ fontSize: '0.85rem', color: '#aaa', display: 'none' }}>{label}</div>
      <div style={{ fontSize: '2rem', color: color || '#fff', fontWeight: 600 }}>
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

export function TableWidget({ data, options = {}, height }) {
  const items = (data && (data.items || data.rows)) || [];
  const fmt = formatValue(options.format || 'count', options.decimals);
  const cols = items.length > 0 ? Object.keys(items[0]).filter((k) => k !== 'key') : [];
  return (
    <div style={{ height: height || 220, overflow: 'auto' }}>
      <table className="table table-sm" style={{ color: '#ddd' }}>
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((row, i) => (
            <tr key={i}>
              {cols.map((c) => (
                <td key={c}>{c === 'value' ? fmt(row[c]) : String(row[c])}</td>
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
  const xBuckets = (data && data.xBuckets) || [];
  const yBuckets = (data && data.yBuckets) || [];
  const values = (data && data.values) || [];
  const h = height || 260;

  const rows = yBuckets.length;
  const cols = xBuckets.length;
  if (!rows || !cols) {
    return <div style={{ color: '#999', padding: 16 }}>No data</div>;
  }

  // Find max for color scaling
  let max = 0;
  values.forEach((row) => row.forEach((v) => { if (v > max) max = v; }));

  const cellH = Math.max(8, Math.floor((h - 30) / rows));
  return (
    <div style={{ height: h, overflow: 'auto', padding: 8 }}>
      <table style={{ borderCollapse: 'collapse', fontSize: 11, color: '#ddd' }}>
        <thead>
          <tr>
            <th style={{ padding: 2 }}></th>
            {xBuckets.map((ts, i) => (
              <th key={i} style={{ padding: 2, fontWeight: 'normal', color: '#888' }}>
                {i % Math.ceil(cols / 8) === 0 ? formatTsShort(ts) : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {yBuckets.map((label, y) => (
            <tr key={y}>
              <td style={{ padding: 2, color: '#aaa', whiteSpace: 'nowrap' }}>{label}</td>
              {xBuckets.map((_, x) => {
                const v = (values[y] && values[y][x]) || 0;
                const intensity = max > 0 ? v / max : 0;
                const bg = `rgba(33, 150, 243, ${intensity.toFixed(2)})`;
                return (
                  <td
                    key={x}
                    title={`${label} @ ${formatTs(xBuckets[x])}: ${v}`}
                    style={{
                      width: 12,
                      height: cellH,
                      background: bg,
                      border: '1px solid #1a1a1a',
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
