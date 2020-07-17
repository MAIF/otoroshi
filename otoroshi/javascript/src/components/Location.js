import React, { Component } from 'react';
import {
  SelectInput,
  ArrayInput,
} from './inputs';

export class Location extends Component {
  render() {
    return (
      <>
        <SelectInput
          label="Organization"
          value={this.props.tenant}
          onChange={this.props.onChangeTenant}
          valuesFrom="/bo/api/proxy/api/tenants"
          transformer={a => ({
            value: a.id,
            label: a.name + " - " + a.description,
          })}
          help="The organization where this entity will belong"
        />
        <ArrayInput 
          label="Teams"
          value={this.props.teams}
          onChange={this.props.onChangeTeams}
          valuesFrom="/bo/api/proxy/api/teams"
          transformer={a => ({
            value: a.id,
            label: a.name + " - " + a.description,
          })}
          help="The teams where this entity will belong"
        />
      </>
    );
  }
}