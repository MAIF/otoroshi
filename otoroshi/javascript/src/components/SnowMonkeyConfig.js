import React, { Component } from 'react';

import { ChaosConfigWithSkin } from './ChaosConfig';

import {
  ArrayInput,
  BooleanInput,
  NumberInput,
  SelectInput,
  TextInput,
  NumberRangeInput,
  RangeTextInput,
} from './inputs';

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
        <BooleanInput
          label="Include user facing apps."
          value={this.state.config.includeUserFacingDescriptors}
          help="Include services descriptors with public access or 'user facing app' flag"
          onChange={v => this.changeTheValue('includeUserFacingDescriptors', v)}
        />
        <BooleanInput
          label="Dry run"
          value={this.state.config.dryRun}
          help="Produces outages and events about it but without impacting performances"
          onChange={v => this.changeTheValue('dryRun', v)}
        />
        <NumberInput
          suffix="times"
          label="Outages per day"
          help="How many outage per work day"
          value={this.state.config.timesPerDay}
          onChange={v => this.changeTheValue('timesPerDay', v)}
        />
        <SelectInput
          label="Outage strategy"
          placeholder="The strategy used for outage creattion"
          value={this.state.config.outageStrategy}
          onChange={e => this.changeTheValue('outageStrategy', e)}
          help="The strategy used by the monkey"
          possibleValues={['OneServicePerGroup', 'AllServicesPerGroup']}
          transformer={v => ({ value: v, label: v })}
        />
        <RangeTextInput
          label="Working period"
          help="The start time and stop time of the monkey, each day"
          placeholderFrom="Outage period start in the work day"
          valueFrom={this.state.config.startTime}
          onChangeFrom={e => this.changeTheValue('startTime', e)}
          placeholderTo="Outage period stop in the work day"
          valueTo={this.state.config.stopTime}
          onChangeTo={e => this.changeTheValue('stopTime', e)}
        />
        <NumberRangeInput
          label="Outage duration"
          help="The range value for the duration of the outages"
          suffixFrom=".ms"
          placeholderFrom="Outage duration range start"
          valueFrom={this.state.config.outageDurationFrom}
          onChangeFrom={e => this.changeTheValue('outageDurationFrom', e)}
          suffixTo=".ms"
          placeholderTo="Outage duration range stop"
          valueTo={this.state.config.outageDurationTo}
          onChangeTo={e => this.changeTheValue('outageDurationTo', e)}
        />
        <ArrayInput
          label="Impacted groups"
          placeholder="Groups"
          value={this.state.config.targetGroups}
          valuesFrom="/bo/api/proxy/api/groups"
          transformer={a => ({ value: a.id, label: a.name })}
          help="The end value for the duration of the outages"
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
