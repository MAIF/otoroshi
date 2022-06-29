import _ from 'lodash';
import React, { Component } from 'react';

class Metric extends Component {
  render() {
    return (
      <div style={{ width: '100%', height: 600, outline: '1px solid red' }}>
        <pre>
          <code>
            {JSON.stringify(this.props.metric, null, 2)}
          </code>
        </pre>
      </div>
    );
  }
}

function HistogramMetric() {
  return null;
}

function TimerMetric() {
  return null;
}

class CounterMetric extends Component  {

  state = { fontSize: 'xx-large', value: null };

  componentDidMount() {
    this.adjust();
  }

  // componentDidUpdate() {
  //   this.adjust();
  // }

  adjust = () => {
    if (this.container && this.valueContainer) {
      const valueContainerInnterWidth = parseInt(this.valueContainer.innerWidth);
      const valueContainerWidth = parseInt(this.valueContainer.clientWidth);
      const valueContainerHeight = parseInt(this.valueContainer.clientHeight);
      const containerWidth = parseInt(this.container.clientWidth);
      const containerHeight = parseInt(this.container.clientHeight);
      if ((valueContainerWidth > containerWidth) || (valueContainerInnterWidth > containerWidth) || (valueContainerHeight > containerHeight)) {
        this.setState({ fontSize: 'large' })
      }
      if (_.isNumber(this.props.metric.value)) {
        const strValue = String(this.props.metric.value);
        if (strValue.indexOf('.') > -1) {
          const valueLength = strValue.length
          if (valueLength > 16) {
            this.setState({ value: this.props.metric.value.toFixed(10) })
          }
        }
      }
    }
  }

  render() {
    const size = 300;
    const metric = this.props.metric;
    return (
      <div ref={r => this.container = r} style={{ margin: 5, width: size, height: size, borderRadius: 3, backgroundColor: '#595956', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
        <div style={{ width: '100%', height: '80%', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <div ref={r => this.valueContainer = r} style={{ fontSize: this.state.fontSize, textSizeAdjust: 'auto', textAlign: 'center', maxWidth: '100%' }}>{this.state.value || metric.value}</div>
        </div>
        <span style={{ fontSize: '1.0em', textAlign: 'center' }}>{metric.name}</span>
      </div>
    );
  }
}

export class MetricsPage extends Component {

  state = { metrics: [] }

  possibleTypes = [ 'counters', 'gauges', 'histograms', 'timers' ]

  componentDidMount() {
    this.fetchMetrics();
    this.interval = setInterval(() => this.fetchMetrics(), 5000)
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval)
    }
  }

  fetchMetrics = () => {
    return fetch('/bo/api/metrics', {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accepts: 'application/json'
      }
    }).then(r => r.json()).then(metrics => {
      this.setState({ metrics })
    })
  }

  render() {
    return (
      <div style={{ marginTop: 20, width: '100vw', height: '100vh', display: 'flex', flexDirection: 'column', justifyContent: 'flex-start', alignItems: 'center' }}>
        <h3>metrics: {this.state.metrics.length}</h3>
        <div style={{ width: '100%', display: 'flex', flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-start', alignItems: 'flex-start' }}>
          {this.possibleTypes.map(type => {
            return this.state.metrics.filter(m => m.type === type).map(metric => {
              if (type === 'histograms') {
                return <HistogramMetric key={metric.name} metric={metric} />
              } else if (type === 'timers') {
                return <TimerMetric key={metric.name} metric={metric} />
              } else {
                return <CounterMetric key={metric.name} metric={metric} />
              }
            })
          })}
        </div>
      </div>
    );
  }
}