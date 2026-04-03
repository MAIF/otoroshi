import React, { Component, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { converterBase2 } from 'byte-converter';
import { Sparklines, SparklinesLine, SparklinesSpots } from 'react-sparklines';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { dynamicTitleContent } from '../components/DynamicTitleSignal';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function init(size) {
  const arr = [];
  for (let i = 0; i < size; i++) arr.push(0);
  return arr;
}

function computeBytes(value) {
  let unit = 'Mb';
  let v = parseFloat(converterBase2(value, 'B', 'MB').toFixed(3));
  if (v > 1024) { v = parseFloat(converterBase2(value, 'B', 'GB').toFixed(3)); unit = 'Gb'; }
  if (v > 1024) { v = parseFloat(converterBase2(value, 'B', 'TB').toFixed(3)); unit = 'Tb'; }
  if (v > 1024) { v = parseFloat(converterBase2(value, 'B', 'PB').toFixed(3)); unit = 'Pb'; }
  return [v, unit];
}

// ---------------------------------------------------------------------------
// Entity tile
// ---------------------------------------------------------------------------

function EntityTile({ icon, label, count, to, color }) {
  return (
    <Link to={to} className="hp-entity-tile">
      <div className="hp-entity-tile-icon" style={{ backgroundColor: color }}>
        <i className={icon} />
      </div>
      {/* <a href="https://www.otoroshi.io/docs/"
        className='btn btn-primary ms-auto d-flex hp-entity-link'
        target='_blank'>
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="size-6">
          <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 19.5 15-15m0 0H8.25m11.25 0v11.25" />
        </svg>
      </a> */}
      <div className="hp-entity-tile-body">
        <span className="hp-entity-tile-count">{count ?? '–'}</span>
        <span className="hp-entity-tile-label">{label}</span>
      </div>
    </Link>
  );
}

function EntitiesOverview() {
  const [counts, setCounts] = useState({});

  const ENTITIES = [
    { key: 'apis', url: '/bo/api/proxy/apis/apis.otoroshi.io/v1/apis', label: 'APIs', icon: 'fas fa-brush', to: '/apis', color: 'hsla(210,70%,55%,.15)' },
    { key: 'routes', url: '/bo/api/proxy/api/routes', label: 'HTTP Routes', icon: 'fas fa-road', to: '/routes', color: 'hsla(210,70%,55%,.15)' },
    { key: 'apikeys', url: '/bo/api/proxy/api/apikeys', label: 'API Keys', icon: 'fas fa-key', to: '/apikeys', color: 'hsla(40,94%,58%,.15)' },
    { key: 'certificates', url: '/bo/api/proxy/api/certificates', label: 'Certificates', icon: 'fas fa-certificate', to: '/certificates', color: 'hsla(150,52%,51%,.15)' },
    { key: 'auths', url: '/bo/api/proxy/api/auths', label: 'Auth. modules', icon: 'fas fa-lock', to: '/auth-configs', color: 'hsla(280,60%,55%,.15)' },
    { key: 'groups', url: '/bo/api/proxy/api/groups', label: 'Groups', icon: 'fas fa-folder', to: '/groups', color: 'hsla(20,70%,55%,.15)' },
    { key: 'backends', url: '/bo/api/proxy/api/backends', label: 'Backends', icon: 'fas fa-microchip', to: '/backends', color: 'hsla(330,60%,55%,.15)' },
    { key: 'data-exporters', url: '/bo/api/proxy/api/data-exporter-configs', label: 'Exporters', icon: 'fas fa-paper-plane', to: '/exporters', color: 'hsla(60,50%,45%,.15)' },
    { key: 'workflows', url: '/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows', label: 'Workflows', icon: 'fas fa-cubes', to: '/workflows', color: 'hsla(60,50%,45%,.15)' },
    { key: 'WAF', url: '/bo/api/proxy/apis/coraza-waf.extensions.otoroshi.io/v1/coraza-configs', label: 'WAF', icon: 'fas fa-cubes', to: '/extensions/coraza-waf/coraza-configs', color: 'hsla(60,50%,45%,.15)' },
    { key: 'wasmplugins', url: '/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins', label: 'WASM plugins', icon: 'fa fa-plug', to: '/wasm-plugins', color: 'hsla(60,50%,45%,.15)' },
    { key: 'verifiers', url: '/bo/api/proxy/api/verifiers', label: 'JWT Verifiers', icon: 'fa fa-circle-check', to: '/jwt-verifiers', color: 'hsla(60,50%,45%,.15)' },
  ];

  useEffect(() => {
    ENTITIES.forEach(({ key, url }) => {
      fetch(url, { method: 'GET', credentials: 'include', headers: { Accept: 'application/json' } })
        .then((r) => r.json())
        .then((data) => {
          const count = Array.isArray(data) ? data.length : data?.total ?? '?';
          setCounts((prev) => ({ ...prev, [key]: count }));
        })
        .catch(() => setCounts((prev) => ({ ...prev, [key]: '?' })));
    });
  }, []);

  return (
    <div className="hp-section">
      <div className="hp-section-title">Entities</div>
      <div className="hp-entity-grid">
        <div className="otoroshi-tile">
          <img src="/assets/images/otoroshi-logo-color.png" width={64} />
          <div className='otoroshi-tile-header'>
            {/* <h3>Otoroshi</h3> */}

            <div>
              The Cloud Native API & AI Gateway documentation
            </div>
            <a href="https://www.otoroshi.io/docs/"
              className='btn btn-primary ms-auto d-flex'
              target='_blank'>
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="size-6">
                <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 19.5 15-15m0 0H8.25m11.25 0v11.25" />
              </svg>
            </a>
          </div>
        </div>
        {ENTITIES.map((t) => (
          <EntityTile key={t.key} count={counts[t.key]} {...t} />
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// API States pie chart
// ---------------------------------------------------------------------------

const API_STATE_COLORS = {
  published: '#4caf50',
  staging: '#7e8c9a',
  deprecated: '#ff9800',
  removed: '#f44336',
};

const API_STATE_LABELS = {
  published: 'Published',
  staging: 'Staging',
  deprecated: 'Deprecated',
  removed: 'Removed',
};

function ApiStatesPieChart({ children }) {
  const [stateCounts, setStateCounts] = useState({});

  useEffect(() => {
    fetch('/bo/api/proxy/apis/apis.otoroshi.io/v1/apis', {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
      .then((r) => r.json())
      .then((apis) => {
        if (!Array.isArray(apis)) return;
        const counts = { published: 0, staging: 0, deprecated: 0, removed: 0 };
        apis.forEach((api) => {
          const s = api.state || 'staging';
          if (counts[s] !== undefined) counts[s]++;
        });
        setStateCounts(counts);
      })
      .catch(() => { });
  }, []);

  // if (!stateCounts) return null;

  const total = Object.values(stateCounts).reduce((a, b) => a + b, 0);
  if (total === 0) return null;

  const data = Object.entries(stateCounts)
    .filter(([, v]) => v > 0)
    .map(([key, value]) => ({ name: API_STATE_LABELS[key], value, key }));

  return <div className='d-flex mt-3' style={{ gap: '1rem' }}>
    <div>
      <div className="hp-section-title">APIs Status</div>
      <div className="hp-api-states-card">
        <div className="hp-api-states-chart">
          <ResponsiveContainer width="100%" height={280}>
            <PieChart>
              <Pie
                data={data}
                dataKey="value"
                nameKey="name"
                cx="50%"
                cy="50%"
                innerRadius={35}
                outerRadius={65}
                paddingAngle={2}
                strokeWidth={0}
                labelLine={false}
                label={({ name, value, percent }) =>
                  `${name} (${Math.round(percent * 100)}%)`
                }
              >
                {data.map((entry) => (
                  <Cell key={entry.key} fill={API_STATE_COLORS[entry.key]} />
                ))}
              </Pie>
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
    {children}
  </div>
}

// ---------------------------------------------------------------------------
// Metric card (text on top, sparkline below)
// ---------------------------------------------------------------------------

const SPARK_SIZE = 30;

function MetricCard({ value, label, unit, tick }) {
  const [history, setHistory] = useState(init(SPARK_SIZE));

  useEffect(() => {
    let num = value;
    if (typeof num === 'string') {
      num = parseFloat(
        num.replace(/ /g, '').replace('in', '').replace('out', '').replace('/sec', '').replace(/Mb|Gb|Tb|Pb|Kb/g, '')
      ) || 0;
    }
    setHistory((prev) => {
      const next = [...prev, num];
      if (next.length > SPARK_SIZE) next.shift();
      return next;
    });
  }, [tick]);

  const mode = window.localStorage.getItem('otoroshi-dark-light-mode') || 'dark';

  return (
    <div className="hp-metric">
      <div className="hp-metric-header">
        <span className="hp-metric-value">
          {value}
          {unit && <span className="hp-metric-unit">{unit}</span>}
        </span>
        {label && <span className="hp-metric-label">{label}</span>}
      </div>
      <div className="hp-metric-graph">
        <Sparklines data={history} limit={SPARK_SIZE} height={50} width={250}>
          <SparklinesLine color={mode === 'dark' ? 'var(--color-primary)' : '#333'} />
          <SparklinesSpots size={2} />
        </Sparklines>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Live metrics section (SSE)
// ---------------------------------------------------------------------------

class LiveMetrics extends Component {
  state = {
    ok: false, tick: 0,
    rate: 0, duration: 0, overhead: 0, requests: 0,
    dataInRate: '', dataOutRate: '',
    concurrentHandled: 0,
    dataIn: '', dataOut: '',
  };

  componentDidMount() {
    this.sse = new EventSource('/bo/api/proxy/api/live/global?every=2000');
    this.sse.onmessage = (e) => {
      const d = JSON.parse(e.data);
      d.rate = d.rate || 0;
      d.duration = d.duration || 0;
      d.concurrentHandledRequests = d.concurrentHandledRequests || 0;
      const [vIn, uIn] = computeBytes(d.dataIn);
      const [vOut, uOut] = computeBytes(d.dataOut);
      const [vInR, uInR] = computeBytes(d.dataInRate);
      const [vOutR, uOutR] = computeBytes(d.dataOutRate);
      this.setState((prev) => ({
        ok: true,
        tick: prev.tick + 1,
        rate: parseFloat(d.rate.toFixed(2)).prettify(),
        duration: parseFloat(d.duration.toFixed(2)).prettify(),
        overhead: parseFloat(d.overhead.toFixed(2)).prettify(),
        requests: d.calls.prettify(),
        concurrentHandled: d.concurrentHandledRequests.prettify(),
        dataInRate: `${vInR.prettify()} ${uInR}/s`,
        dataOutRate: `${vOutR.prettify()} ${uOutR}/s`,
        dataIn: `${vIn.prettify()} ${uIn}`,
        dataOut: `${vOut.prettify()} ${uOut}`,
      }));
    };
  }

  componentWillUnmount() {
    if (this.sse) { this.sse.close(); delete this.sse; }
  }

  render() {
    if (!this.state.ok) return <div className="hp-loading"><i className="fas fa-spinner fa-spin" /></div>;

    const t = this.state.tick;
    return (
      <div className="hp-section">
        <div className="hp-section-title">Live</div>
        <div className="hp-metrics-grid">
          <MetricCard value={this.state.rate} label="req/s" tick={t} />
          <MetricCard value={this.state.duration} label="latency" unit="ms" tick={t} />
          <MetricCard value={this.state.overhead} label="overhead" unit="ms" tick={t} />
          <MetricCard value={this.state.concurrentHandled} label="concurrent" tick={t} />
          <MetricCard value={this.state.dataInRate} label="data in" tick={t} />
          <MetricCard value={this.state.dataOutRate} label="data out" tick={t} />
        </div>
        <div className="hp-section-title" style={{ marginTop: '1rem' }}>Totals</div>
        <div className="hp-metrics-grid">
          <MetricCard value={this.state.requests} label="requests served" tick={t} />
          <MetricCard value={this.state.dataIn} label="total in" tick={t} />
          <MetricCard value={this.state.dataOut} label="total out" tick={t} />
        </div>
      </div>
    );
  }
}

// ---------------------------------------------------------------------------
// Cluster section (SSE)
// ---------------------------------------------------------------------------

class ClusterMetrics extends Component {
  state = { ok: false, workers: '', payload: '', health: 'grey' };

  componentDidMount() {
    if (this.props.env?.clusterRole !== 'Leader') return;
    this.sse = new EventSource('/bo/api/proxy/api/cluster/live?every=2000');
    this.sse.onmessage = (e) => {
      const d = JSON.parse(e.data);
      d.workers = d.workers || 0;
      d.health = d.health || 'grey';
      const [pIn, pInU] = computeBytes(d.payloadIn);
      const [pOut, pOutU] = computeBytes(d.payloadOut);
      this.setState({
        ok: true,
        workers: d.workers === 1 ? '1 member' : `${d.workers} members`,
        payload: `${pIn.prettify()} ${pInU} / ${pOut.prettify()} ${pOutU}`,
        health: d.health,
      });
    };
  }

  componentWillUnmount() {
    if (this.sse) { this.sse.close(); delete this.sse; }
  }

  render() {
    if (this.props.env?.clusterRole !== 'Leader' || !this.state.ok) return null;

    const healthColor = { green: '#4caf50', orange: '#ff9800', red: '#f44336', grey: '#9e9e9e' }[this.state.health] || '#9e9e9e';

    return <div>
      <div className="hp-section-title">Cluster</div>
      <div className="hp-cluster-grid">
        <Link to="/cluster" className="hp-cluster-tile">
          <i className="fas fa-server hp-cluster-icon" />
          <span className="hp-cluster-value">{this.state.workers}</span>
        </Link>
        <Link to="/cluster" className="hp-cluster-tile">
          <i className="fas fa-exchange-alt hp-cluster-icon" />
          <span className="hp-cluster-value">{this.state.payload}</span>
        </Link>
        <Link to="/cluster" className="hp-cluster-tile">
          <i className="fas fa-heartbeat hp-cluster-icon" style={{ color: healthColor, fontSize: '1.8rem' }} />
          <span className="hp-cluster-value" style={{ color: healthColor }}>{this.state.health}</span>
        </Link>
      </div>
    </div>
  }
}

// ---------------------------------------------------------------------------
// Home page
// ---------------------------------------------------------------------------

export class HomePage extends Component {
  componentDidMount() {
    dynamicTitleContent.value = 'Home Page';
  }

  render() {
    return (
      <div className="hp-root">
        {!this.props.usedNewEngine && (
          <div className="alert alert-warning mb-3 d-flex align-items-center" role="alert">
            You are using the legacy Otoroshi engine. The new engine is now ready for production.
            <a
              href="https://maif.github.io/otoroshi/manual/next/engine.html"
              target="_blank"
              className="btn btn-sm btn-warning ms-auto"
            >
              Documentation
            </a>
          </div>
        )}
        <EntitiesOverview />
        <ApiStatesPieChart >
          <ClusterMetrics env={this.props.env} />
        </ApiStatesPieChart>
        <LiveMetrics />
      </div>
    );
  }
}
