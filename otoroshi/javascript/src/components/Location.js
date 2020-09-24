import React, { Component } from 'react';
import {
  SelectInput,
  ArrayInput,
} from './inputs';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Separator } from './Separator';

export class Location extends Component {

  state = { possibleTeams: [], tenant: 'default' };

  componentDidMount() {
    let tenant = window.localStorage.getItem("Otoroshi-Tenant") || "default";
    if (window.__user.superAdmin) {
      tenant = this.props.tenant || window.localStorage.getItem("Otoroshi-Tenant") || "default";
    }
    this.setState({ tenant })
    this.props.onChangeTenant(tenant);
    BackOfficeServices.env().then(() => this.forceUpdate());
    BackOfficeServices.findAllTeams().then(teams => {
      const possibleTeams = [ { id: '*', name: 'All', description: 'All teams' }, ...teams.filter(t => t.tenant === tenant) ];
      this.setState({ possibleTeams: [ ...possibleTeams ] });
      const availableTeams = possibleTeams.length > 0;
      const noTeams = !(this.props.teams && this.props.teams.length > 0);
      const badTeams = (this.props.teams || []).filter(team => possibleTeams.map(pt => pt.id).indexOf(team) > -1).length === 0;
      if (availableTeams && (noTeams || badTeams)) {
        console.log(possibleTeams)
        const all = possibleTeams.filter(t => t.id === '*')[0]
        const first = possibleTeams[0].id;
        const team = all ? all : first;
        this.props.onChangeTeams([team]);
      }
    });
  }
  onChangeTenant = (tenant) => {
    this.props.onChangeTenant(tenant);
    BackOfficeServices.findAllTeams().then(teams => {
      const possibleTeams = [ { id: '*', name: 'All', description: 'All teams' }, ...teams.filter(t => t.tenant === tenant) ];
      this.setState({ possibleTeams }, () => {
        this.props.onChangeTeams(possibleTeams[0] ? [possibleTeams[0]] : ["default"]);
      })
    });
  }
  render() {
    if (window.__otoroshi__env__latest.bypassUserRightsCheck) {
      return null;
    }
    // if (!(window.__user.superAdmin || window.__user.tenantAdmin)) {
    //   return null;
    // }
    return (
      <>
        {/*window.__otoroshi__env__latest.userAdmin*/window.__user.superAdmin && <SelectInput
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
        {/* TODO: only show to tenant admins ????? */}
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
            {window.__user.superAdmin && <a className="btn btn-xs btn-info pull-right" href="/bo/dashboard/organizations">
              <i className="glyphicon glyphicon-edit"></i> Manage organizations
            </a>}
            {window.__user.tenantAdmin && <a className="btn btn-xs btn-info pull-right" href="/bo/dashboard/teams">
              <i className="glyphicon glyphicon-edit"></i> Manage teams
            </a>}
          </div>
        </div>
        <hr />
      </>
    );
  }
}