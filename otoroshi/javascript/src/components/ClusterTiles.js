import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
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
      <a href={this.props.link}>
        <div
          className="metric"
          style={{
            width: props.width || 300,
          }}>
          <div className="metric-text">
            {!this.props.hideValueText && <span className="metric-text-value">{props.value}</span>}
            <span className="metric-text-title">{props.legend}</span>
          </div>
          <div className="metric-box">
            <Sparklines data={this.state.values} limit={this.state.values.length} height={65}>
              <SparklinesLine color="rgb(249, 176, 0)" />
              <SparklinesSpots />
            </Sparklines>
          </div>
        </div>
      </a>
    );
  }
}

export class ClusterTiles extends Component {

  state = {
    show: false,
    firstDone: false,
    workers: 0,
    payloadInOut: '0 Kb / O Kb',
    health: 'grey'
  };

  componentDidMount() {
    BackOfficeServices.env().then(env => {
      if (env.clusterRole === 'Leader') {
        this.setState({ show: true });
        this.evtSource = new EventSource(this.props.url);
        this.evtSource.onmessage = e => this.onMessage(e);
      }
    })
  }

  componentWillUnmount() {
    if (this.evtSource) {
      this.evtSource.close();
      delete this.evtSource;
    }
  }

  computeValue(value) {
    let unit = 'b';
    let computedValue = parseFloat(converterBase2(value, 'B', 'B').toFixed(3));
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, 'B', 'KB').toFixed(3));
      unit = 'Kb';
    }
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, 'B', 'MB').toFixed(3));
      unit = 'Mb';
    }
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

  onMessage = e => {
    const data = JSON.parse(e.data);
    data.workers = data.workers || 0;
    data.health = data.health || 'grey';
    const [payloadIn, payloadInUnit] = this.computeValue(data.payloadIn);
    const [payloadOut, payloadOutUnit] = this.computeValue(data.payloadOut);
   
    this.setState({
      firstDone: true,
      payload: `${payloadIn.prettify()} ${payloadInUnit} / ${payloadOut.prettify()} ${payloadOutUnit} payload`,
      workers: `${data.workers} workers`,
      health: data.health,
    });
  };

  render() {
    if (!this.state.show) {
      return null;
    }
    if (!this.state.firstDone) {
      return null;
    }
    const health = this.state.health;
    let healthValue = 0;
    if (health === 'grey' ) healthValue = 0 
    if (health === 'red' ) healthValue = 0
    if (health === 'orange' ) healthValue = 1
    if (health === 'green' ) healthValue = 2
    return (
      <div>
        <h4 className="live-title">CLUSTER METRICS</h4>
        <div className="rowMetrics">
          <Metric time={Date.now()} link="/bo/dashboard/cluster" value={this.state.workers} legend="" />
          <Metric time={Date.now()} link="/bo/dashboard/cluster" value={this.state.payload} legend="" />
          <Metric time={Date.now()} link="/bo/dashboard/cluster" hideValueText value={healthValue} legend={<i className="fa fa-heartbeat" style={{ textShadow: 'none', fontSize: 60, color: this.state.health }} />} />
        </div>
      </div>
    );
  }
}
