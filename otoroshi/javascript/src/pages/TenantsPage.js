import React, { Component } from 'react';
import { Table, Form } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class TenantsPage extends Component {
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

  deleteTenant = (tenant, table) => {
    window
      .newConfirm('Are you sure you want to delete organization "' + tenant.name + '"')
      .then(confirmed => {
        if (confirmed) {
          BackOfficeServices.deleteTenant(tenant).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle('All Organizations');
  }

  gotoTenant = tenant => {
    this.props.history.push({
      pathname: `/organizations/edit/${tenant.id}`,
    });
  };

  formFlow = [
    'id',
    'name',
    'description',
    'metadata',
  ];

  formSchema = {
    id: { type: 'string', props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'Nice organization' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'A nice organization to do whatever you want' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="organizations"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          defaultTitle={this.title}
          defaultValue={BackOfficeServices.createNewTenant}
          itemName="Organization"
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllTenants}
          updateItem={BackOfficeServices.updateTenant}
          deleteItem={BackOfficeServices.deleteTenant}
          createItem={BackOfficeServices.createTenant}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoTenant}
          firstSort={0}
          extractKey={item => {
            return item.id;
          }}
          itemUrl={i => `/bo/dashboard/organizations/edit/${i.id}`}
        />
      </div>
    );
  }
}
