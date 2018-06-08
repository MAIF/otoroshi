import React, { Component } from 'react';

import {
  ArrayInput,
  ObjectInput,
  BooleanInput,
  LinkDisplay,
  SelectInput,
  TextInput,
  NumberInput,
  FreeDomainInput,
  Help,
  Form,
} from './inputs';
import { Collapse } from './inputs/Collapse';

function getOrElse(value, f, def) {
  if (value) {
    return f(value);
  } else {
    return def;
  }
}

export class ChaosConfig extends Component {
  state = {
    config: { ...this.props.config },
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.config !== this.props.config) {
      const c = { ...nextProps.config };
      this.setState({ config: c });
    }
  }

  changeTheValue = (name, value) => {
    if (name.indexOf('.') > -1) {
      const [key1, key2] = name.split('.');
      const newConfig = {
        ...this.state.config,
        [key1]: { ...this.state.config[key1], [key2]: value },
      };
      this.setState(
        {
          config: newConfig,
        },
        () => {
          this.props.onChange(this.state.config);
        }
      );
    } else {
      const newConfig = { ...this.state.config, [name]: value };
      this.setState(
        {
          config: newConfig,
        },
        () => {
          this.props.onChange(this.state.config);
        }
      );
    }
  };

  changeFirstResponse = (name, value) => {
    const newConfig = { ...this.state.config };
    if (!newConfig.badResponsesFaultConfig.responses[0]) {
      newConfig.badResponsesFaultConfig.responses[0] = {
        status: 502,
        body: '{"error":true}',
        headers: {
          'Content-Type': 'application/json',
        },
      };
    }
    newConfig.badResponsesFaultConfig.responses[0][name] = value;
    this.setState(
      {
        config: newConfig,
      },
      () => {
        this.props.onChange(this.state.config);
      }
    );
  };

  render() {
    if (!this.state.config) return null;
    return [
      <Collapse initCollapsed={false} label="Large Request Fault">
        <NumberInput
          label="Ratio"
          help="..."
          step="0.1"
          min="0"
          max="1"
          value={this.state.config.largeRequestFaultConfig.ratio}
          onChange={v => this.changeTheValue('largeRequestFaultConfig.ratio', v)}
        />
        <NumberInput
          suffix="bytes"
          label="Additional size"
          help="..."
          value={this.state.config.largeRequestFaultConfig.additionalRequestSize}
          onChange={v => this.changeTheValue('largeRequestFaultConfig.additionalRequestSize', v)}
        />
      </Collapse>,
      <Collapse initCollapsed={false} label="Large Response Fault">
        <NumberInput
          label="Ratio"
          help="..."
          step="0.1"
          min="0"
          max="1"
          value={this.state.config.largeResponseFaultConfig.ratio}
          onChange={v => this.changeTheValue('largeResponseFaultConfig.ratio', v)}
        />
        <NumberInput
          suffix="bytes"
          label="Additional size"
          help="..."
          value={this.state.config.largeResponseFaultConfig.additionalResponseSize}
          onChange={v => this.changeTheValue('largeResponseFaultConfig.additionalResponseSize', v)}
        />
      </Collapse>,
      <Collapse initCollapsed={false} label="Latency injection Fault">
        <NumberInput
          label="Ratio"
          help="..."
          step="0.1"
          min="0"
          max="1"
          value={this.state.config.latencyInjectionFaultConfig.ratio}
          onChange={v => this.changeTheValue('latencyInjectionFaultConfig.ratio', v)}
        />
        <NumberInput
          suffix="ms."
          label="From"
          help="..."
          value={this.state.config.latencyInjectionFaultConfig.from}
          onChange={v => this.changeTheValue('latencyInjectionFaultConfig.from', v)}
        />
        <NumberInput
          suffix="ms."
          label="To"
          help="..."
          value={this.state.config.latencyInjectionFaultConfig.to}
          onChange={v => this.changeTheValue('latencyInjectionFaultConfig.to', v)}
        />
      </Collapse>,
      <Collapse initCollapsed={false} label="Bad response Fault">
        <NumberInput
          label="Ratio"
          help="..."
          step="0.1"
          min="0"
          max="1"
          value={this.state.config.badResponsesFaultConfig.ratio}
          onChange={v => this.changeTheValue('badResponsesFaultConfig.ratio', v)}
        />
        <TextInput
          label="Status"
          help="..."
          value={getOrElse(
            this.state.config.badResponsesFaultConfig.responses[0],
            i => i.status,
            502
          )}
          onChange={v => this.changeFirstResponse('status', v)}
        />
        <TextInput
          label="Body"
          help="..."
          value={getOrElse(
            this.state.config.badResponsesFaultConfig.responses[0],
            i => i.body,
            '{"error":true}'
          )}
          onChange={v => this.changeFirstResponse('body', v)}
        />
        <ObjectInput
          label="Headers"
          placeholderKey="Header name (ie.Access-Control-Allow-Origin)"
          placeholderValue="Header value (ie. *)"
          value={getOrElse(this.state.config.badResponsesFaultConfig.responses[0], i => i.headers, {
            'Content-Type': 'application/json',
          })}
          help="..."
          onChange={v => this.changeFirstResponse('headers', v)}
        />
      </Collapse>,
    ];
  }
}
