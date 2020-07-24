import React, { Component } from 'react';
import {
  SelectInput,
  ArrayInput,
} from './inputs';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Separator } from './Separator';

export class Location extends Component {

  state = { possibleTeams: [] };

  componentDidMount() {
    const tenant = this.props.tenant || window.localStorage.getItem("Otoroshi-Tenant") || "default";
    BackOfficeServices.env().then(() => this.forceUpdate());
    BackOfficeServices.findAllTeams().then(teams => {
      const possibleTeams = teams.filter(t => t.tenant == tenant);
      this.setState({ possibleTeams })
    });
  }
  onChangeTenant = (tenant) => {
    this.props.onChangeTenant(tenant);
    BackOfficeServices.findAllTeams().then(teams => {
      const possibleTeams = teams.filter(t => t.tenant == tenant);
      this.setState({ possibleTeams }, () => {
        this.props.onChangeTeams(possibleTeams[0] ? [possibleTeams[0]] : ["default"]);
      })
    });
  }
  render() {
    if (window.__otoroshi__env__latest.bypassUserRightsCheck) {
      return null;
    }
    return (
      <>
        {window.__otoroshi__env__latest.userAdmin && <SelectInput
          label="Organization"
          value={this.props.tenant || window.localStorage.getItem("Otoroshi-Tenant") || "default"}
          onChange={this.onChangeTenant}
          valuesFrom="/bo/api/proxy/api/tenants"
          transformer={a => ({
            value: a.id,
            label: a.name + " - " + a.description,
          })}
          help="The organization where this entity will belong"
        />}
        <ArrayInput 
          label="Teams"
          value={this.props.teams}
          onChange={this.props.onChangeTeams}
          possibleValues={this.state.possibleTeams.map(a => {
            return {
              value: a.id,
              label: a.name + " - " + a.description,
            }
          })}
          help="The teams where this entity will belong"
        />
        <div className="form-group">
          <label className="col-xs-12 col-sm-2 control-label"></label>
          <div className="col-sm-10">
            <a className="btn btn-xs btn-info pull-right" href="/bo/dashboard/organizations">
              <i className="glyphicon glyphicon-edit"></i> Manage organizations
            </a>
            <a className="btn btn-xs btn-info pull-right" href="/bo/dashboard/teams">
              <i className="glyphicon glyphicon-edit"></i> Manage teams
            </a>
          </div>
        </div>
        <hr />
      </>
    );
  }
}