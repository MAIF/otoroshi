import React, { Component } from 'react';

import { ChaosConfig, ChaosConfigWithSkin } from './ChaosConfig';

import { ArrayInput, BooleanInput, NumberInput, SelectInput, TextInput } from './inputs';

export class SnowMonkeyConfig extends Component {
  state = {
    config: this.props.config,
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.config !== this.props.config) {
      this.setState({ config: nextProps.config });
    }
  }

  changeTheValue = (name, value) => {
    const newConfig = { ...this.state.config, [name]: value };
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
      <form className="form-horizontal" style={{ marginRight: 15 }}>
        <NumberInput
          suffix="times"
          label="Outages per day"
          help="How many outage per work day"
          value={this.state.config.timesPerDay}
          onChange={v => this.changeTheValue('timesPerDay', v)}
        />
        <BooleanInput
          label="Include user facing apps."
          value={this.state.config.includeUserFacingDescriptors}
          help="..."
          onChange={v => this.changeTheValue('includeUserFacingDescriptors', v)}
        />
        <SelectInput
          label="Outage strategy"
          placeholder="The strategy used for outage creattion"
          value={this.state.config.outageStrategy}
          onChange={e => this.changeTheValue('outageStrategy', e)}
          help="..."
          possibleValues={['OneServicePerGroup', 'AllServicesPerGroup']}
          transformer={v => ({ value: v, label: v })}
        />
        <TextInput
          label="Start time"
          placeholder="Outage period start in the work day"
          value={this.state.config.startTime}
          help="..."
          onChange={e => this.changeTheValue('startTime', e)}
        />
        <TextInput
          label="Stop time"
          placeholder="Outage period stio in the work day"
          value={this.state.config.stopTime}
          help="..."
          onChange={e => this.changeTheValue('stopTime', e)}
        />
        <NumberInput
          suffix=".ms"
          label="Outage duration (from)"
          placeholder="Outage duration range start"
          value={this.state.config.outageDurationFrom}
          help="..."
          onChange={e => this.changeTheValue('outageDurationFrom', e)}
        />
        <NumberInput
          suffix=".ms"
          label="Outage duration (to)"
          placeholder="Outage duration range stop"
          value={this.state.config.outageDurationTo}
          help="..."
          onChange={e => this.changeTheValue('outageDurationTo', e)}
        />
        <ArrayInput
          label="Impacted groups"
          placeholder="Groups"
          value={this.state.config.targetGroups}
          valuesFrom="/bo/api/proxy/api/groups"
          transformer={a => ({ value: a.id, label: a.name })}
          help="..."
          onChange={e => this.changeTheValue('targetGroups', e)}
        />
        <ChaosConfigWithSkin
          initCollapsed={false}
          config={this.state.config.chaosConfig}
          onChange={c => {
            this.setState({ config: { ...this.state.config, chaosConfig: c } }, () => {
              this.props.onChange(this.state.config);
            });
          }}
        />
      </form>,
    ];
  }
}
