import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';

export class ScriptsPage extends Component {

  formSchema = {
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Script name', placeholder: 'My Awesome Script' },
    },
    desc: {
      type: 'string',
      props: { label: 'Script description', placeholder: 'Description of the Script' },
    },
    code: {
      type: 'code',
      props: { label: 'Script code', placeholder: 'Code the Script', props: { mode: 'scala' } },
    },
  };

  columns = [
    { title: 'Name', content: item => item.name },
    { title: 'Description', noMobile: true, content: item => item.description },
  ];

  formFlow = ['id', 'name', 'description', 'code'];

  componentDidMount() {
    this.props.setTitle(`All Scripts`);
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="scripts"
        defaultTitle="All Scripts"
        defaultValue={() => ({ id: faker.random.alphaNumeric(64) })}
        itemName="script"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={BackOfficeServices.findAllScripts}
        updateItem={BackOfficeServices.updateScript}
        deleteItem={BackOfficeServices.deleteScript}
        createItem={BackOfficeServices.createScript}
        navigateTo={item => {
          window.location = `/bo/dashboard/scripts/edit/${item.id}`;
        }}
        itemUrl={i => `/bo/dashboard/scripts/edit/${item.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={item => item.id}
      />
    );
  }
}
