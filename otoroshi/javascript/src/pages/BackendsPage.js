import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { nextClient } from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';
import { schemas } from './RouteDesigner/form';

export class BackendsPage extends Component {
  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'name', placeholder: 'My Awesome service Backend' },
    },
    description: {
      type: 'string',
      props: { label: 'description', placeholder: 'Description of the Backend' },
    },
    metadata: {
      type: 'object',
      props: { label: 'metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'tags' },
    },
  };

  formFlow = ['_loc', 'id', 'name', 'description', 'tags', 'metadata', '---'];

  columns = [
    {
      title: 'Name',
      content: (item) => item.name,
    },
    { title: 'Description', content: (item) => item.description },
  ];

  state = { env: null };

  componentDidMount() {
    this.props.setTitle(`All backends`);
    BackOfficeServices.env().then((env) => this.setState({ env }));
  }

  render() {
    const client = nextClient.forEntity(nextClient.ENTITIES.BACKENDS);
    return (
      <Table
        parentProps={this.props}
        selfUrl="backends"
        defaultTitle="All backends"
        defaultValue={() => client.template()}
        itemName="backend"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        deleteItem={(item) => client.deleteById(item.id)}
        fetchItems={() => client.findAll()}
        updateItem={(item) => client.update(item)}
        createItem={(item) => client.create(item)}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/backends/edit/${item.id}`;
        }}
        itemUrl={(item) => `/bo/dashboard/backends/edit/${item.id}`}
        showActions={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="Backend"
        formFunction={(opts) => {
          const { value, onChange, flow, schema } = opts;
          return (
            <>
              <Form value={value} onChange={onChange} flow={flow} schema={schema} />
              <Form
                value={value.backend}
                onChange={(backend) => onChange({ ...value, backend })}
                flow={schemas.backend.flow}
                schema={schemas.backend.schema}
              />
            </>
          );
        }}
      />
    );
  }
}
