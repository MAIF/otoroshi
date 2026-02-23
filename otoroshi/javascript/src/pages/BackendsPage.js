import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { nextClient } from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';
import { schemas } from './RouteDesigner/form';
import InfoCollapse from '../components/InfoCollapse';

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
      filterId: 'name',
      content: (item) => item.name,
    },
    { title: 'Description', filterId: 'description', content: (item) => item.description },
  ];

  componentDidMount() {
    this.props.setTitle(`Backends`);
  }

  render() {
    const client = nextClient.forEntity(nextClient.ENTITIES.BACKENDS);
    return <>
      <InfoCollapse title="What is a Backend?">
        <p>
          A Backend is a <strong>reusable configuration</strong> that defines how Otoroshi connects to your upstream services.
          Instead of configuring the same HTTP client settings on every route, you define a backend once
          and share it across multiple HTTP Routes and APIs.
        </p>
        <p>
          This makes it easy to centralize and standardize how your gateway talks to your infrastructure.
          Here is what you can configure in a backend:
        </p>
        <ul>
          <li><strong>Targets</strong> — define one or more upstream servers with hostname, port, TLS settings, HTTP protocol (HTTP/1.1, HTTP/2, HTTP/3), fixed IP addresses, and load balancing weights.</li>
          <li><strong>Load balancing</strong> — choose how traffic is distributed across targets (round-robin, random, weighted, best response time, etc.).</li>
          <li><strong>HTTP client settings</strong> — configure timeouts, connection pools, retry policies, and other client-level options in one place.</li>
          <li><strong>TLS configuration</strong> — manage TLS/mTLS settings per target for secure communication with your upstream services.</li>
          <li><strong>Health checks</strong> — monitor the availability of your targets and automatically route traffic away from unhealthy ones.</li>
          <li><strong>URL rewriting</strong> — set a root path and control how the request URL is rewritten before reaching the backend.</li>
        </ul>
        <p>
          By mutualizing backend configurations, you avoid duplication, reduce errors,
          and can update connection settings for all related routes from a single place.
        </p>
      </InfoCollapse>
      <Table
        parentProps={this.props}
        selfUrl="backends"
        defaultTitle="All backends"
        defaultValue={() => client.template()}
        itemName="Backend"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        deleteItem={(item) => client.deleteById(item.id)}
        fetchItems={(paginationState) =>
          client.findAllWithPagination({
            ...paginationState,
            fields: ['id', 'name', 'description'],
          })
        }
        updateItem={(item) => client.update(item)}
        createItem={(item) => client.create(item)}
        navigateTo={(item) => {
          this.props.history.push(`/backends/edit/${item.id}`);
        }}
        itemUrl={(item) => `/bo/dashboard/backends/edit/${item.id}`}
        showActions={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="proxy.otoroshi.io/Backend"
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
    </>
  }
}
