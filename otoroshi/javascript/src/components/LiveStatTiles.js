import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { converterBase2 } from 'byte-converter';
import { Sparklines, SparklinesLine, SparklinesSpots } from 'react-sparklines';

function init(size, value = 0) {
  const arr = [];
  for (let i = 0; i < size; i++) {
    arr.push(value);
  }
  return arr;
}

class Metric extends Component {
  size = 30;

  state = {
    values: init(this.size),
  };

  restrict(what, size) {
    if (what.length > size) {
      what.shift();
    }
    return what;
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.time !== this.props.time) {
      let value = nextProps.value;
      if (value.replace) {
        value = value
          .replace(' ', '')
          .replace('in', '')
          .replace('out', '')
          .replace('/sec', '')
          .replace(/Mb|Gb|Tb|Pb|Kb/, '');
        value = parseFloat(value);
      }
      this.setState({ values: this.restrict([...this.state.values, value], this.size) });
    }
  }

  render() {
    const props = this.props;
    return (
      <div
        className="metric"
        style={{
          width: props.width || 300,
        }}>
        <div className="metric-text">
          <span className="metric-text-value">{props.value}</span>
          <span className="metric-text-title">{props.legend}</span>
        </div>
        <div className="metric-box">
          <Sparklines data={this.state.values} limit={this.state.values.length} height={65}>
            <SparklinesLine color="rgb(249, 176, 0)" />
            <SparklinesSpots />
          </Sparklines>
        </div>
      </div>
    );
  }
}

export class LiveStatTiles extends Component {
  state = {
    firstDone: false,
    dataIn: '0 Kb in',
    dataOut: '0 Kb out',
    requests: 0,
    rate: 0.0,
    duration: 0.0,
    overhead: 0.0,
    dataRate: '0 Kb/sec',
    dataInRate: '0 Kb/sec in',
    dataOutRate: '0 Kb/sec out',
    concurrentProcessedRequests: 0,
    concurrentHandledRequests: 0,
  };

  componentDidMount() {
    this.evtSource = new EventSource(this.props.url);
    this.evtSource.onmessage = (e) => this.onMessage(e);
  }

  componentWillUnmount() {
    if (this.evtSource) {
      this.evtSource.close();
      delete this.evtSource;
    }
  }

  computeValue(value) {
    let unit = 'Mb';
    let computedValue = parseFloat(converterBase2(value, 'B', 'MB').toFixed(3));
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, 'B', 'GB').toFixed(3));
      unit = 'Gb';
    }
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, 'B', 'TB').toFixed(3));
      unit = 'Tb';
    }
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, 'B', 'PB').toFixed(3));
      unit = 'Pb';
    }
    return [computedValue, unit];
  }

  onMessage = (e) => {
    const data = JSON.parse(e.data);
    data.rate = data.rate || 0.0;
    data.duration = data.duration || 0.0;
    data.concurrentProcessedRequests = data.concurrentProcessedRequests || 0;
    data.concurrentHandledRequests = data.concurrentHandledRequests || 0;
    const [valueIn, unitIn] = this.computeValue(data.dataIn);
    const [valueOut, unitOut] = this.computeValue(data.dataOut);
    const [valueInRate, unitInRate] = this.computeValue(data.dataInRate);
    const [valueOutRate, unitOutRate] = this.computeValue(data.dataOutRate);
    this.setState({
      firstDone: true,
      dataIn: `${valueIn.prettify()} ${unitIn} in`,
      dataOut: `${valueOut.prettify()} ${unitOut} out`,
      requests: data.calls.prettify(),
      rate: parseFloat(data.rate.toFixed(2)).prettify(),
      duration: parseFloat(data.duration.toFixed(2)).prettify(),
      overhead: parseFloat(data.overhead.toFixed(2)).prettify(),
      concurrentProcessedRequests: data.concurrentProcessedRequests.prettify(),
      concurrentHandledRequests: data.concurrentHandledRequests.prettify(),
      dataInRate: `${valueInRate.prettify()} ${unitInRate}/sec in`,
      dataOutRate: `${valueOutRate.prettify()} ${unitOutRate}/sec out`,
      dataRate: `${parseFloat(
        (valueOutRate + valueInRate).toFixed(3)
      ).prettify()} ${unitOutRate}/sec`,
    });
  };

  render() {
    if (!this.state.firstDone) {
      return (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            width: '100%',
            height: 300,
          }}>
          <svg
            width="142px"
            height="142px"
            viewBox="0 0 100 100"
            preserveAspectRatio="xMidYMid"
            className="uil-ring-alt">
            <rect x="0" y="0" width="100" height="100" fill="none" className="bk" />
            <circle cx="50" cy="50" r="40" stroke="#222222" fill="none" strokeLinecap="round" />
            <circle cx="50" cy="50" r="40" stroke="#f9b000" fill="none" strokeLinecap="round">
              <animate
                attributeName="stroke-dashoffset"
                dur="2s"
                repeatCount="indefinite"
                from="0"
                to="502"
              />
              <animate
                attributeName="stroke-dasharray"
                dur="2s"
                repeatCount="indefinite"
                values="150.6 100.4;1 250;150.6 100.4"
              />
            </circle>
          </svg>
        </div>
      );
    }
    return (
      <div>
        <h4 className="live-title">LIVE METRICS</h4>
        <div className="rowMetrics">
          <Metric time={Date.now()} value={this.state.rate} legend="requests per second" />
          <Metric time={Date.now()} value={this.state.duration} legend="ms. per request" />
          <Metric time={Date.now()} value={this.state.overhead} legend="ms. overhead per request" />
        </div>
        <div className="rowMetrics">
          <Metric time={Date.now()} value={this.state.dataInRate} />
          <Metric time={Date.now()} value={this.state.dataOutRate} />
          <Metric
            time={Date.now()}
            value={this.state.concurrentHandledRequests}
            legend="concurrent requests"
          />
        </div>
        <h4 className="live-title">GLOBAL METRICS</h4>
        <div className="rowMetrics">
          <Metric time={Date.now()} value={this.state.requests} legend="requests served" />
          <Metric time={Date.now()} value={this.state.dataIn} />
          <Metric time={Date.now()} value={this.state.dataOut} />
        </div>
      </div>
    );
  }
}
