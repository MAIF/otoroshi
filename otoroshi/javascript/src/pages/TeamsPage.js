import React, { Component } from 'react';
import { Table, Form } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class TeamsPage extends Component {
  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
  ];

  deleteTeam = (team, table) => {
    window
      .newConfirm('Are you sure you want to delete team "' + team.name + '"')
      .then((confirmed) => {
        if (confirmed) {
          BackOfficeServices.deleteTeam(team).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle('Teams');
  }

  gotoTeam = (team) => {
    this.props.history.push({
      pathname: `/teams/edit/${team.id}`,
    });
  };

  formFlow = ['id', 'tenant', 'name', 'description', 'tags', 'metadata'];

  formSchema = (fprops) => {
    return {
      id: { type: 'string', props: { label: 'Id', placeholder: '---', disabled: fprops.update } },
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
          transformer: (a) => ({
            value: a.id,
            label: a.name + ' - ' + a.description,
          }),
        },
      },
      metadata: {
        type: 'object',
        props: { label: 'Metadata' },
      },
      tags: {
        type: 'array',
        props: { label: 'Tags' },
      },
    };
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
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllTeamsWithPagination({
              ...paginationState,
              fields: ['id', 'name', 'description'],
            })
          }
          updateItem={BackOfficeServices.updateTeam}
          deleteItem={BackOfficeServices.deleteTeam}
          createItem={BackOfficeServices.createTeam}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoTeam}
          firstSort={0}
          extractKey={(item) => {
            return item.id;
          }}
          itemUrl={(i) => `/bo/dashboard/teams/edit/${i.id}`}
          export={true}
          kubernetesKind="organize.otoroshi.io/Team"
        />
      </div>
    );
  }
}
