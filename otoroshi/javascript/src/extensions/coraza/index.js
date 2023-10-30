import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';

const extensionId = 'otoroshi.extensions.CorazaWAF';

export function setupCorazaExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {
    class CorazaWafConfigsPage extends Component {
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
        inspect_body: {
          type: 'bool',
          props: { label: 'Inspect req/res body' },
        },
        pool_capacity: {
          type: 'number',
          props: { label: 'Number of coraza instances' },
        },
        config: {
          type: 'jsonobjectcode',
          props: {
            label: 'Coraza config.',
          },
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
        'pool_capacity',
        'inspect_body',
        'config',
      ];

      componentDidMount() {
        this.props.setTitle(`All Coraza WAF configs.`);
      }

      client = BackOfficeServices.apisClient(
        'coraza-waf.extensions.otoroshi.io',
        'v1',
        'coraza-configs'
      );

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/coraza-waf/coraza-configs',
            defaultTitle: 'All Coraza WAF configs.',
            defaultValue: () => ({
              id: 'coraza-waf-config_' + uuid(),
              name: 'My WAF',
              description: 'An awesome WAF',
              tags: [],
              metadata: {},
              inspect_body: true,
              config: {
                directives_map: {
                  default: [
                    'Include @recommended-conf',
                    'Include @crs-setup-conf',
                    'Include @owasp_crs/*.conf',
                    'SecRuleEngine DetectionOnly',
                  ],
                },
                default_directives: 'default',
                metric_labels: {},
                per_authority_directives: {},
              },
            }),
            itemName: 'Coraza WAF config',
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: (paginationState) => this.client.findAll(),
            updateItem: this.client.update,
            deleteItem: this.client.delete,
            createItem: this.client.create,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/coraza-waf/coraza-configs/edit/${item.id}`;
            },
            itemUrl: (item) => `/bo/dashboard/extensions/coraza-waf/coraza-configs/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: 'CorazaConfig',
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
          title: 'Coraza WAF configs.',
          description: 'All your Coraza WAF configs.',
          img: 'private-apps',
          link: '/extensions/coraza-waf/coraza-configs',
          display: () => true,
          icon: () => 'fa-cubes',
        },
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/extensions/coraza-waf/coraza-configs`;
          },
          env: <span className="fas fa-cubes" />,
          label: 'Coraza WAF configs.',
          value: 'coraza-configs',
        },
      ],
      routes: [
        {
          path: '/extensions/coraza-waf/coraza-configs/:taction/:titem',
          component: (props) => {
            return <CorazaWafConfigsPage {...props} />;
          },
        },
        {
          path: '/extensions/coraza-waf/coraza-configs/:taction',
          component: (props) => {
            return <CorazaWafConfigsPage {...props} />;
          },
        },
        {
          path: '/extensions/coraza-waf/coraza-configs',
          component: (props) => {
            return <CorazaWafConfigsPage {...props} />;
          },
        },
      ],
    };
  });
}
