import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import CodeInput from '../../components/inputs/CodeInput';
import { NgCodeRenderer, NgSingleCodeLineRenderer, SingleLineCode } from '../../components/nginputs';
import { Row } from '../../components/Row';
import { Button } from '../../components/Button';

const extensionId = 'otoroshi.extensions.CorazaWAF';

export function setupCorazaExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {
    class CorazaWafConfigsPage extends Component {
      state = {
        defaultLocation: undefined,
      };

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
          type: 'box-bool',
          props: {
            label: 'Inspect request and response bodies',
            description: <div>
              It is a ModSecurity directive that enables inspection of the request body (e.g., POST data, JSON, XML).

              <p>With it set to On, ModSecurity can analyze the full content of incoming requests — not just headers and URLs — allowing detection of attacks hidden in form submissions or API calls.</p>
            </div>
          },
        },
        inspect_in_body: {
          type: 'box-bool',
          props: {
            label: 'Inspect request body',
            description: <div>
              It is a ModSecurity directive that enables inspection of the request body (e.g., POST data, JSON, XML).

              <p>With it set to On, ModSecurity can analyze the full content of incoming requests — not just headers and URLs — allowing detection of attacks hidden in form submissions or API calls.</p>
            </div>
          },
        },
        inspect_out_body: {
          type: 'box-bool',
          props: {
            label: 'Inspect response body',
            description: <div>
              It is a ModSecurity directive that enables inspection of the response body.

              <p>With it set to On, ModSecurity can analyze the full content of the response — not just headers.</p>
            </div>
          },
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
        is_blocking_mode: {
          type: 'box-bool',
          props: {
            label: 'Is Blocking transaction ?',
            description: <div>
              SecRuleEngine is a directive in ModSecurity (a web application firewall) that controls whether rules are enforced or just logged.

              <p><span style={{
                fontWeight: 'bold'
              }}>Blocking mode: </span> Rules are fully enforced. Matching requests can trigger disruptive actions such as blocking or redirecting.</p>

              <p><span style={{
                fontWeight: 'bold'
              }}>DetectionOnly: </span> Rules are processed, but no disruptive actions (like blocking) are taken. Attack details are logged as events.</p>
            </div>
          },
          // props: {
          //   label: 'Mode',
          //   possibleValues: [
          //     { label: 'Detection Only', value: 'DetectionOnly' },
          //     { label: 'Blocking', value: 'On' },
          //   ],
          // },
        },
        include_owasp_crs: {
          type: 'box-bool',
          props: {
            description: <div>
              Allows to enable or disable the OWASP Core Rule Set (CRS) for their Coraza Web Application Firewall configuration.
              <div>
                To know more about it, <Button
                  className="btn-sm"
                  onClick={() => {
                    window.open(
                      "https://coraza.io/docs/tutorials/coreruleset/",
                      '_blank'
                    );
                  }}>check the ruleset</Button>
              </div>
            </div>,
            label: 'Use OWASP CRS',
          },
        },
        custom_rules_infos: {
          type: 'display',
          props: {
            label: 'Custom rules',
            value: (
              <p style={{ marginTop: 6 }}>
                To know more about {' '}
                <Button
                  className="btn-sm"
                  onClick={() => {
                    window.open(
                      "https://coraza.io/docs/seclang/",
                      '_blank'
                    );
                  }}>custom rules</Button>
              </p>
            ),
          },
        },
        directives: {
          type: 'array',
          props: {
            label: 'Directives',
            component: (props) => <Row>
              <NgCodeRenderer
                ngOptions={{
                  spread: true,
                }}
                rawSchema={{
                  props: {
                    showGutter: false,
                    ace_config: {
                      onLoad: editor => editor.renderer.setPadding(10),
                      // maxLines: 20,
                      fontSize: 14,
                    },
                    editorOnly: true,
                    height: '10rem',
                    mode: 'prolog',
                  },
                }}
                value={props.itemValue}
                onChange={(e) => {
                  const arr = props.value;
                  arr[props.idx] = e;
                  props.onChange(arr);
                }}
              />
            </Row>
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
        '<<<WAF config.',
        'pool_capacity',
        'is_blocking_mode',
        'inspect_in_body',
        'inspect_out_body',
        'include_owasp_crs',
        'custom_rules_infos',
        'directives',
      ];

      componentDidMount() {
        this.props.setTitle(`All Coraza WAF configs.`);

        fetch('/bo/api/location', {
          headers: {
            Accept: 'application/json',
          },
        })
          .then((r) => r.json())
          .then((defaultLocation) =>
            this.setState({
              defaultLocation,
            })
          );
      }

      client = BackOfficeServices.apisClient(
        'coraza-waf.extensions.otoroshi.io',
        'v1',
        'coraza-configs'
      );

      render() {
        if (!this.state.defaultLocation) return null;

        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/coraza-waf/coraza-configs',
            defaultTitle: 'All Coraza WAF configs.',
            defaultValue: () => ({
              id: 'coraza-waf-config_' + uuid(),
              _loc: this.state.defaultLocation,
              name: 'My WAF',
              description: 'An awesome WAF',
              tags: [],
              metadata: {},
              inspect_body: true,
              include_owasp_crs: true,
              pool_capacity: 2,
              mode: 'DetectionOnly',
              directives: []
            }),
            itemName: 'Coraza WAF config',
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: this.client.findAll,
            updateItem: this.client.update,
            createItem: this.client.create,
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
            onStateChange: (newValue, oldValue, onChange) => onChange(newValue),
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      categories: [
        {
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
            },
          ],
        },
      ],
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
