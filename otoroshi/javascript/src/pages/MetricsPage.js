import _ from 'lodash';
import React, { Component } from 'react';

export class MetricsPage extends Component {
  state = { metrics: [] };

  possibleTypes = ['counters', 'gauges', 'histograms', 'timers'];

  componentDidMount() {
    this.fetchMetrics();
    this.interval = setInterval(() => this.fetchMetrics(), 5000);
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  fetchMetrics = () => {
    return fetch('/bo/api/metrics', {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accepts: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((metrics) => {
        this.setState({ metrics });
      });
  };

  clean = (v) => {
    if (v) {
      if (_.isNumber(v)) {
        if (String(v).indexOf('.') > -1) {
          const x = v.toFixed(5);
          const parts = x.toString().split('.');
          parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
          return parts.join('.');
        } else {
          const parts = v.toString().split('.');
          parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
          return parts.join('.');
        }
      } else if (_.isString(v)) {
        if (v.length > 20) {
          return <span title={v}>{v.substring(0, 19)}...</span>;
        } else {
          return v;
        }
      } else {
        return v;
      }
    } else {
      return v;
    }
  };

  render() {
    const clean = this.clean;
    const w_units = this.state.metrics.find((m) => m.rate_units) || {
      rate_units: 'calls/second',
      duration_units: 'milliseconds',
    };
    const metrics = _.uniqBy(
      _.sortBy(this.state.metrics, (metric) => metric.name).filter((m) => m.type !== 'metrics'),
      (m) => m.name
    ).filter((m) => (this.state.search ? m.name.indexOf(this.state.search) > -1 : true));
    return (
      <div
        style={{
          marginTop: 20,
          width: '100vw',
          height: '90vh',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'flex-start',
          alignItems: 'center',
        }}>
        <h3>
          {metrics.length} metrics - {w_units.rate_units} - {w_units.duration_units}
        </h3>
        <input
          style={{ marginBottom: 10 }}
          className="form-control"
          type="text"
          value={this.state.search}
          onChange={(e) => this.setState({ search: e.target.value })}
          placeholder="search metric name"
        />
        <div
          style={{
            width: '100%',
            display: 'flex',
            flexDirection: 'row',
            flexWrap: 'wrap',
            justifyContent: 'flex-start',
            alignItems: 'flex-start',
          }}>
          <table className="table table-striped table-hover table-sm">
            <thead>
              <tr>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>name</th>
                {/*<th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>kind</th>*/}
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>value</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>count</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>min</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>mean</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>max</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>stddev</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>m_rate</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>m1_rate</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>m5_rate</th>
                <th style={{ textAlign: 'center', position: 'sticky', top: 10 }}>m15_rate</th>
              </tr>
            </thead>
            <tbody>
              {this.possibleTypes.map((type) => {
                return metrics
                  .filter((m) => m.type === type)
                  .map((metric) => (
                    <tr key={metric.name}>
                      <td>{metric.name}</td>
                      {/*<td>{metric.type}</td>*/}
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`value: ${clean(metric.value) || ''}`}>
                          {clean(metric.value) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`count: ${clean(metric.count) || ''} calls`}>
                          {clean(metric.count) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`min: ${clean(metric.min) || ''} ${metric.duration_units}`}>
                          {clean(metric.min) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`mean: ${clean(metric.mean) || ''} ${metric.duration_units}`}>
                          {clean(metric.mean) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`max: ${clean(metric.max) || ''} ${metric.duration_units}`}>
                          {clean(metric.max) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`stddev: ${clean(metric.stddev) || ''}`}>
                          {clean(metric.stddev) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`mean_rate: ${clean(metric.mean_rate) || ''} ${
                            metric.rate_units
                          }`}>
                          {clean(metric.mean_rate) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`m1_rate: ${clean(metric.m1_rate) || ''} ${metric.rate_units}`}>
                          {clean(metric.m1_rate) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`m5_rate: ${clean(metric.m5_rate) || ''} ${metric.rate_units}`}>
                          {clean(metric.m5_rate) || ''}
                        </span>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span
                          style={{ cursor: 'pointer' }}
                          title={`m15_rate: ${clean(metric.m15_rate) || ''} ${metric.rate_units}`}>
                          {clean(metric.m15_rate) || ''}
                        </span>
                      </td>
                    </tr>
                  ));
              })}
            </tbody>
          </table>
        </div>
      </div>
    );
  }
}
