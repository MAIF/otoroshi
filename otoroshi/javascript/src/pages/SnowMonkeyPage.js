import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { SnowMonkeyConfig } from '../components/SnowMonkeyConfig';
import _ from 'lodash';
import { Table } from '../components/inputs';
import moment from 'moment/moment';

function shallowDiffers(a, b) {
  return !_.isEqual(a, b);
}

function enrichConfig(config) {
  if (
    config.chaosConfig.largeRequestFaultConfig == null &&
    config.chaosConfig.largeResponseFaultConfig == null &&
    config.chaosConfig.latencyInjectionFaultConfig == null &&
    config.chaosConfig.badResponsesFaultConfig == null
  ) {
    const c = { enabled: true };
    if (!c.largeRequestFaultConfig) {
      c.largeRequestFaultConfig = {
        ratio: 0.2,
        additionalRequestSize: 0,
      };
    }
    if (!c.largeResponseFaultConfig) {
      c.largeResponseFaultConfig = {
        ratio: 0.2,
        additionalResponseSize: 0,
      };
    }
    if (!c.latencyInjectionFaultConfig) {
      c.latencyInjectionFaultConfig = {
        ratio: 0.2,
        from: 500,
        to: 1000,
      };
    }
    if (!c.badResponsesFaultConfig) {
      c.badResponsesFaultConfig = {
        ratio: 0.2,
        responses: [
          {
            status: 502,
            body: '{"error":true}',
            headers: {
              'Content-Type': 'application/json',
            },
          },
        ],
      };
    }
    config.chaosConfig = c;
    return config;
  } else {
    return config;
  }
}

export class SnowMonkeyPage extends Component {
  state = {
    originalConfig: null,
    config: null,
    started: false,
    changed: false,
    outages: [],
  };

  columns = [
    { title: 'Service Name', content: item => item.descriptorName },
    { title: 'Outage duration', content: item => moment.duration(item.duration, 'ms').humanize() },
    {
      title: 'Outage until',
      content: item => {
        const parts = item.until.split('.')[0].split(':');
        return `${parts[0]}:${parts[1]}:${parts[2]} today`;
      },
    },
    {
      title: 'Action',
      style: { textAlign: 'center', width: 200 },
      notSortable: true,
      notFilterable: true,
      content: item => item,
      cell: (v, item, table) => {
        return (
          <button
            type="button"
            className="btn btn-success btn-xs"
            onClick={e =>
              (window.location = `/bo/dashboard/lines/prod/services/${item.descriptorId}`)
            }>
            <i className="glyphicon glyphicon-link" /> Go to service descriptor
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Nihonzaru, Snow Monkey and Lord of Chaos 🐒`);
    this.updateStateConfig(true);
    this.mountShortcuts();
  }

  componentWillUnmount() {
    this.unmountShortcuts();
  }

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  saveShortcut = e => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.changed) {
        this.saveChanges();
      }
    }
  };

  saveChanges = e => {
    // console.log('Save', this.state.config);
    BackOfficeServices.updateSnowMonkeyConfig(this.state.config).then(() => {
      this.updateStateConfig(true);
    });
  };

  updateStateConfig = first => {
    BackOfficeServices.fetchSnowMonkeyConfig().then(_config => {
      const config = enrichConfig(_config);
      this.setState({ config, started: config.enabled });
      if (first) {
        this.setState({ originalConfig: config });
      }
    });
  };

  toggle = e => {
    if (this.state.started) {
      BackOfficeServices.stopSnowMonkey().then(() => {
        this.setState({ started: false });
        setTimeout(() => this.updateStateConfig(), 5000);
      });
    } else {
      BackOfficeServices.startSnowMonkey().then(() => {
        this.setState({ started: true });
        setTimeout(() => this.updateStateConfig(), 5000);
      });
    }
  };

  render() {
    const moreProps = {};
    if (!this.state.changed) {
      moreProps.disabled = true;
    }
    return (
      <div>
        <div className="row">
          <div className="col-md-8">
            <button
              type="button"
              className={`btn btn-${this.state.started ? 'danger' : 'success'}`}
              onClick={this.toggle}>
              <i className={`glyphicon glyphicon-${this.state.started ? 'stop' : 'play'}`} />
              {this.state.started ? ' Stop that damn monkey ...' : ' Unleash the monkey !'}
            </button>
            <button
              type="button"
              className={`btn btn-success`}
              {...moreProps}
              onClick={this.saveChanges}>
              <i className={`glyphicon glyphicon-hdd`} /> Save
            </button>
          </div>
          <div className="col-md-4">
            <img className="pull-right" src="/assets/images/snowmonkey-2.png" width={200} />
          </div>
        </div>
        <div className="row" style={{ marginTop: 20 }}>
          <SnowMonkeyConfig
            config={this.state.config}
            onChange={config => {
              this.setState({
                config,
                changed: shallowDiffers(this.state.originalConfig, config),
              });
            }}
          />
        </div>
        <hr />
        <h3>Current outages</h3>
        <Table
          parentProps={this.props}
          selfUrl="snowmonkey"
          defaultTitle="Current outages"
          defaultValue={() => ({})}
          itemName="outage"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchSnowMonkeyOutages}
          showActions={false}
          showLink={false}
          extractKey={item => item.descriptorId}
        />
      </div>
    );
  }
}
