import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import CodeInput from "../../components/inputs/CodeInput";

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
        mode: {
          type: 'select',
          props: {
            label: 'Mode',
            possibleValues: [
              { label: 'Detection Only', value: 'DetectionOnly' },
              { label: 'Blocking', value: 'On' },
            ]
          }
        },
        include_crs: {
          type: 'bool',
          props: {
            help: 'to know more about it, go to https://coraza.io/docs/tutorials/coreruleset/',
            label: 'Use OWASP CRS'
          }
        },
        custom_rules_infos: {
          type: 'display',
          props: {
            label: 'Custom rules',
            value: <p style={{ marginTop: 6 }}>
              to know more about custom rules, go to <a href="https://coraza.io/docs/seclang/">https://coraza.io/docs/seclang/</a>
            </p>
          }
        },
        custom_rules: {
          type: 'array',
          props: {
            label: '',
            component: (props) => <CodeInput {...props} label="" mode="prolog" value={props.itemValue} onChange={e => {
              const arr = props.value;
              arr[props.idx] = e;
              props.onChange(arr)
            }} />
          }
        }
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
        '<<<WAF config.',
        'mode',
        'include_crs',
        'custom_rules_infos',
        'custom_rules',
        '>>>Raw Coraza config.',
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
              mode: 'DetectionOnly',
              include_crs: true,
              custom_rules: [],
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
            fetchItems: (paginationState) => this.client.findAll().then(arr => {
              const base = [
                'Include @recommended-conf',
                'Include @crs-setup-conf',
                'Include @owasp_crs/*.conf',
                'SecRuleEngine DetectionOnly',
                'SecRuleEngine On',
              ];
              return arr.map(item => {
                let mode = 'DetectionOnly';
                if (item.config.directives_map.default.indexOf("SecRuleEngine On") > -1) {
                  mode = 'On';
                }
                const include_crs = item.config.directives_map.default.indexOf("Include @owasp_crs/*.conf") > -1;
                const custom_rules = item.config.directives_map.default.filter(line => {
                  return base.indexOf(line) === -1;
                });
                const r = {
                  ...item,
                  mode,
                  include_crs,
                  custom_rules,
                }
                console.log(r)
                return r;
              })
            }),
            updateItem: (content) => {
              const d =  [
                'Include @recommended-conf',
                'Include @crs-setup-conf',
              ];
              if (content.include_crs) {
                d.push('Include @owasp_crs/*.conf')
              }
              content.custom_rules.forEach(v => d.push(v));
              if (content.mode === 'On') {
                d.push('SecRuleEngine On')
              } else {
                d.push('SecRuleEngine DetectionOnly')
              }
              content.config.directives_map.default = d;
              return this.client.update(content)
            },
            createItem: (content) => {
              const d =  [
                'Include @recommended-conf',
                'Include @crs-setup-conf',
              ];
              if (content.include_crs) {
                d.push('Include @owasp_crs/*.conf')
              }
              content.custom_rules.forEach(v => d.push(v));
              if (content.mode === 'On') {
                d.push('SecRuleEngine On')
              } else {
                d.push('SecRuleEngine DetectionOnly')
              }
              content.config.directives_map.default = d;
              return this.client.create(content)
            },
            deleteItem: this.client.delete,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/coraza-waf/coraza-configs/edit/${item.id}`;
            },
            itemUrl: (item) => `/bo/dashboard/extensions/coraza-waf/coraza-configs/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: 'coraza-waf.extensions.otoroshi.io/CorazaConfig',
            onStateChange: (newValue, oldValue, onChange) => {
              const d =  [
                'Include @recommended-conf',
                'Include @crs-setup-conf',
              ];
              if (newValue.include_crs) {
                d.push('Include @owasp_crs/*.conf')
              }
              newValue.custom_rules.forEach(v => d.push(v));
              if (newValue.mode === 'On') {
                d.push('SecRuleEngine On')
              } else {
                d.push('SecRuleEngine DetectionOnly')
              }
              newValue.config.directives_map.default = d;
              onChange(newValue)
            }
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      categories:[{
        title: 'WAF',
        description: 'All the features related to Web Application Firewalls',
        features: [
          {
            title: 'WAF configs.',
            description: 'All your Coraza WAF configs.',
            absoluteImg: '',
            link: '/extensions/coraza-waf/coraza-configs',
            display: () => true,
            icon: () => 'fa-cubes',
          }
        ]
      }],
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
