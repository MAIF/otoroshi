import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';

const extensionId = 'otoroshi.extensions.HttpListeners';

export function setupHttpListenersExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {
    class HttpListenersPage extends Component {
      formSchema = {
        _loc: {
          type: 'location',
          props: {},
        },
        id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
        name: {
          type: 'string',
          props: { label: 'Name', placeholder: 'My Awesome WAF' },
        },
        description: {
          type: 'string',
          props: { label: 'Description', placeholder: 'Description of the WAF config' },
        },
        metadata: {
          type: 'object',
          props: { label: 'Metadata' },
        },
        tags: {
          type: 'array',
          props: { label: 'Tags' },
        },
        'config.enabled': {
          type: 'bool',
          props: { label: 'enabled' }
        },
        'config.exclusive': {
          type: 'bool',
          props: { label: 'exclusive' }
        },
        'config.tls': {
          type: 'bool',
          props: { label: 'tls' }
        },
        'config.http2': {
          type: 'bool',
          props: { label: 'http2' }
        },
        'config.h2c': {
          type: 'bool',
          props: { label: 'h2c' }
        },
        'config.http3': {
          type: 'bool',
          props: { label: 'http3' }
        },
        'config.port': {
          type: 'number',
          props: { label: 'port' }
        },
        'config.exposedPort': {
          type: 'number',
          props: { label: 'exposed port' }
        },
        'config.host': {
          type: 'string',
          props: { label: 'host' }
        },
        'config.accessLog': {
          type: 'bool',
          props: { label: 'access logs' }
        },
        'config.clientAuth': {
          type: 'select',
          props: { 
            label: 'client auth.',
            possibleValues: [
              { label: 'None', value: 'none' },
              { label: 'Want', value: 'want' },
              { label: 'Need', value: 'need' },
            ]
          }
        },
      };

      columns = [
        {
          title: 'Name',
          filterId: 'name',
          content: (item) => item.name,
        },
        { title: 'Description', filterId: 'description', content: (item) => item.description },
      ];

      formFlow = [
        '_loc',
        'id',
        'name',
        'description',
        'tags',
        'metadata',
        '<<<Config.',
        'config.enabled',
        'config.exclusive',
        '<<<Host and ports',
        'config.port',
        'config.exposedPort',
        'config.host',
        '<<<TLS',
        'config.tls',
        'config.clientAuth',
        '<<<Protocols',
        'config.http2',
        'config.h2c',
        'config.http3',
        '<<<Options',
        'config.accessLog',
      ];

      componentDidMount() {
        this.props.setTitle(`All HTTP Listener configs.`);
      }

      client = BackOfficeServices.apisClient(
        'http-listeners.extensions.otoroshi.io',
        'v1',
        'http-listeners'
      );

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/http-listeners/listeners',
            defaultTitle: 'All HTTP Listeners configs.',
            defaultValue: () => ({
              id: 'http-listener_' + uuid(),
              name: 'Http listener',
              description: 'An http listener',
              tags: [],
              metadata: {},
              config: {
                enabled: true,
                exclusive: false,
                tls: true,
                http2: true,
                http3: false,
                port: 7890,
                exposedPort: 7890,
                host: '0.0.0.0',
                accessLog: false,
                clientAuth: 'none',
              },
            }),
            itemName: 'HTTP Listener',
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: (paginationState) => this.client.findAll(),
            updateItem: this.client.update,
            deleteItem: this.client.delete,
            createItem: this.client.create,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/http-listeners/listeners/edit/${item.id}`;
            },
            itemUrl: (item) => `/bo/dashboard/extensions/http-listeners/listeners/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: 'HttpListener',
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      sidebarItems: [],
      creationItems: [],
      dangerZoneParts: [],
      features: [
        {
          title: 'HTTP Listeners',
          description: 'All your HTTP Listeners configs.',
          img: 'private-apps',
          link: '/extensions/http-listeners/listeners',
          display: () => true,
          icon: () => 'fa-cubes',
        },
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/extensions/http-listeners/listeners`;
          },
          env: <span className="fas fa-cubes" />,
          label: 'HTTP Listeners',
          value: 'http-listeners',
        },
      ],
      routes: [
        {
          path: '/extensions/http-listeners/listeners/:taction/:titem',
          component: (props) => {
            return <HttpListenersPage {...props} />;
          },
        },
        {
          path: '/extensions/http-listeners/listeners/:taction',
          component: (props) => {
            return <HttpListenersPage {...props} />;
          },
        },
        {
          path: '/extensions/http-listeners/listeners',
          component: (props) => {
            return <HttpListenersPage {...props} />;
          },
        },
      ],
    };
  });
}
