import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';

export class GroupsPage extends Component {
  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Group name', placeholder: 'My Awesome service group' },
    },
    description: {
      type: 'string',
      props: { label: 'Group description', placeholder: 'Description of the group' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Group metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Group tags' },
    },
  };

  columns = [
    {
      title: 'Name',
      content: (item) => item.name,
      wrappedCell: (v, item, table) => {
        if (this.state && this.state.env && this.state.env.adminGroupId === item.id) {
          return (
            <span
              title="This group holds the API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. You might not want to delete it"
              className="label label-danger">
              {item.name}
            </span>
          );
        }
        return item.name;
      },
    },
    { title: 'Description', content: (item) => item.description },
    {
      title: 'Stats',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      content: (item) => (
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={(e) => (window.location = `/bo/dashboard/groups/edit/${item.id}/stats`)}>
          <i className="fas fa-chart-bar" />
        </button>
      ),
    },
  ];

  formFlow = ['_loc', 'id', 'name', 'description', 'tags', 'metadata'];

  state = { env: null };

  componentDidMount() {
    this.props.setTitle(`All service groups`);
    BackOfficeServices.env().then((env) => this.setState({ env }));
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="groups"
        defaultTitle="All service groups"
        defaultValue={BackOfficeServices.createNewGroup}
        itemName="group"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={BackOfficeServices.findAllGroups}
        updateItem={BackOfficeServices.updateGroup}
        deleteItem={BackOfficeServices.deleteGroup}
        createItem={BackOfficeServices.createGroup}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/services?group=${item.id}&groupName=${item.name}`;
          // this.props.history.push({
          //   pathname: `/services?group=${item.id}&groupName=${item.name}`,
          //   // query: { group: item.id, groupName: item.name },
          // });
        }}
        itemUrl={(i) => `/bo/dashboard/services?group=${i.id}&groupName=${i.name}`}
        displayTrash={(item) => this.state.env && this.state.env.adminGroupId === item.id}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="ServiceGroup"
      />
    );
  }
}
