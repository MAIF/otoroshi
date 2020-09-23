import React, { Component } from 'react';
import { Table, Form } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class TeamsPage extends Component {
  columns = [
    {
      title: 'Name',
      content: item => item.name,
    },
    {
      title: 'Description',
      content: item => item.descripiton,
    },
  ];

  deleteTeam = (team, table) => {
    window
      .newConfirm('Are you sure you want to delete team "' + team.name + '"')
      .then(confirmed => {
        if (confirmed) {
          BackOfficeServices.deleteTeam(team).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle('All Teams');
  }

  gotoTeam = team => {
    this.props.history.push({
      pathname: `/teams/edit/${team.id}`,
    });
  };

  formFlow = [
    'id',
    'tenant',
    'name',
    'description',
    'metadata',
  ];

  formSchema = {
    id: { type: 'string', props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'Nice team' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'A nice team to do whatever you want' },
    },
    tenant: {
      type: 'select',
      props: {
        label: 'Organization',
        valuesFrom: '/bo/api/proxy/api/tenants',
        transformer: a => ({
          value: a.id,
          label: a.name + " - " + a.description,
        })
      }
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
  };

  render() {
    if (!window.__user.tenantAdmin) {
      return null;
    }
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="teams"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          defaultTitle={this.title}
          defaultValue={BackOfficeServices.createNewTeam}
          itemName="Team"
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllTeams}
          updateItem={BackOfficeServices.updateTeam}
          deleteItem={BackOfficeServices.deleteTeam}
          createItem={BackOfficeServices.createTeam}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoTeam}
          firstSort={0}
          extractKey={item => {
            return item.id;
          }}
          itemUrl={i => `/bo/dashboard/teams/edit/${i.id}`}
        />
      </div>
    );
  }
}
