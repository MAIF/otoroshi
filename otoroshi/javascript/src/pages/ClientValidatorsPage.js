import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';

export class ClientValidatorsPage extends Component {

  columns = [
    { title: 'Name', content: item => item.name },
    { title: 'Description', content: item => item.description },
    { title: 'Host', content: item => item.host },
    { title: 'Cache', content: item => !item.noCache ? 'yes' : 'no', style: { width: 100, textAlign: 'center' } },
  ];

  formSchema = {
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome validator' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the validator' },
    },
    url: {
      type: 'string',
      props: { label: 'URL', placeholder: 'http://127.0.0.1:3000' },
    },
    host: {
      type: 'string',
      props: { label: 'Host', placeholder: 'validator.foo.bar' },
    },
    noCache: {
      type: 'bool',
      props: { label: 'Do not cache validations' },
    },
    goodTtl: {
      type: 'number',
      props: { label: 'Good validation TTL', placeholder: '600000', suffix: 'milliseconds' },
    },
    badTtl: {
      type: 'number',
      props: { label: 'Bad validation  TTL', placeholder: '60000', suffix: 'milliseconds' },
    },
    method: {
      type: 'string',
      props: { label: 'HTTP method', placeholder: 'POST' },
    },
    path: {
      type: 'string',
      props: { label: 'HTTP Path', placeholder: '/certificates/_validate' },
    },
    timeout: {
      type: 'number',
      props: { label: 'Call timeout', placeholder: '10000', suffix: 'milliseconds' },
    },
    headers: {
      type: 'object',
      props: { label: 'HTTP headers' },
    },
  };

  formFlow = [
    'id',
    'name',
    'description',
    'url',
    'host',
    'method',
    'path',
    'timeout',
    'headers',
    'noCache',
    'goodTtl',
    'badTtl',
  ];

  componentDidMount() {
    this.props.setTitle(`Validation authorities (experimental)`);
  }

  gotoValidator = verifier => {
    this.props.history.push({
      pathname: `validation-authorities/edit/${verifier.id}`,
    });
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="validation-authorities"
          defaultTitle="Validation authorities"
          defaultValue={() => ({
            id: faker.random.alphaNumeric(64),
            name: 'Validation authority',
            description: 'A new validation authority',
            url: 'http://127.0.0.1:3000',
            host: 'validation.foo.bar',
            goodTtl: 600000,
            badTtl: 60000,
            method: 'POST',
            path: '/certificates/_validate',
            timeout: 10000,
            noCache: false,
            headers: {}
          })}
          itemName="Validation authority"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllClientValidators}
          updateItem={BackOfficeServices.updateClientValidator}
          deleteItem={BackOfficeServices.deleteClientValidator}
          createItem={BackOfficeServices.createClientValidator}
          navigateTo={this.gotoValidator}
          itemUrl={i => `/bo/dashboard/validation-authorities/edit/${i.id}`}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={item => item.id}
        />
      </div>
    );
  }
}
