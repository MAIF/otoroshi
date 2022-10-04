import React, { Component } from 'react';
import { nextClient } from '../../services/BackOfficeServices';
import { Form } from '../../components/inputs';
import { Collapse } from '../../components/inputs/Collapse';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import { FeedbackButton } from './FeedbackButton';
import { NgForm } from '../../components/nginputs/form';

export class Target extends Component {
  formSchema = {
    id: { type: 'string', props: { label: 'Id' } },
    hostname: { type: 'string', props: { label: 'Hostname' } },
    port: { type: 'number', props: { label: 'Port' } },
    tls: { type: 'bool', props: { label: 'TLS' } },
    weight: { type: 'number', props: { label: 'Weight' } },
    'predicate.type': {
      type: 'select',
      props: {
        label: 'Predicate',
        possibleValues: ['AlwaysMatch', 'GeolocationMatch', 'NetworkLocationMatch'].map((e) => ({
          label: e,
          value: e,
        })),
      },
    },
    'predicate.position': {
      type: 'array',
      display: (obj) => obj.predicate.type === 'GeolocationMatch',
      props: { label: 'Predicate positions' },
    },
    'predicate.provider': {
      type: 'string',
      display: (obj) => obj.predicate.type === 'NetworkLocationMatch',
      props: { label: 'Predicate provider' },
    },
    'predicate.region': {
      type: 'string',
      display: (obj) => obj.predicate.type === 'NetworkLocationMatch',
      props: { label: 'Predicate region' },
    },
    'predicate.zone': {
      type: 'string',
      display: (obj) => obj.predicate.type === 'NetworkLocationMatch',
      props: { label: 'Predicate zone' },
    },
    'predicate.dataCenter': {
      type: 'string',
      display: (obj) => obj.predicate.type === 'NetworkLocationMatch',
      props: { label: 'Predicate data center' },
    },
    'predicate.rack': {
      type: 'string',
      display: (obj) => obj.predicate.type === 'NetworkLocationMatch',
      props: { label: 'Predicate rack' },
    },
    protocol: {
      type: 'string',
      props: {
        label: 'Protocol',
        possibleValues: ['HTTP/1.0', 'HTTP/1.1', 'HTTP/2.0'].map((e) => ({ label: e, value: e })),
      },
    },
    ip_address: { type: 'string', props: { label: 'IP Address' } },
    'tls_config.enabled': { type: 'bool', props: { label: 'Enabled' } },
    'tls_config.loose': { type: 'bool', props: { label: 'Loose' } },
    'tls_config.trust_all': { type: 'bool', props: { label: 'Trust all' } },
    'tls_config.certs': {
      type: 'array',
      props: {
        label: 'Certificates',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => {
          return {
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          };
        },
      },
    },
    'tls_config.trusted_certs': {
      type: 'array',
      props: {
        label: 'Trusted Certificates',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => {
          return {
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          };
        },
      },
    },
  };

  formFlow = [
    'id',
    'hostname',
    'port',
    'tls',
    'weight',
    'predicate.type',
    'predicate.position',
    'predicate.provider',
    'predicate.region',
    'predicate.zone',
    'predicate.dataCenter',
    'predicate.rack',
    'protocol',
    'ip_address',
    '>>>TLS Settings',
    'tls_config.enabled',
    'tls_config.loose',
    'tls_config.trust_all',
    'tls_config.certs',
    'tls_config.trusted_certs',
  ];

  render() {
    return (
      <Form
        schema={this.formSchema}
        flow={this.formFlow}
        value={this.props.itemValue}
        onChange={(e) => {
          const arr = this.props.value;
          arr[this.props.idx] = e;
          this.props.onChange(arr);
        }}
      />
    );
  }
}

export class CustomTimeout extends Component {
  formSchema = {
    path: { type: 'string', props: { label: 'Path' } },
    global_timeout: { type: 'number', props: { label: 'global timeout', suffix: 'milliseconds' } },
    connection_timeout: {
      type: 'number',
      props: { label: 'connection timeout', suffix: 'milliseconds' },
    },
    idle_timeout: { type: 'number', props: { label: 'idle timeout', suffix: 'milliseconds' } },
    call_timeout: { type: 'number', props: { label: 'call timeout', suffix: 'milliseconds' } },
    call_and_stream_timeout: {
      type: 'number',
      props: { label: 'call and stream timeout', suffix: 'milliseconds' },
    },
  };

  formFlow = [
    'path',
    'global_timeout',
    'connection_timeout',
    'idle_timeout',
    'call_timeout',
    'call_and_stream_timeout',
  ];

  render() {
    return (
      <Form
        schema={this.formSchema}
        flow={this.formFlow}
        value={this.props.itemValue}
        onChange={(e) => {
          const arr = this.props.value;
          arr[this.props.idx] = e;
          this.props.onChange(arr);
        }}
      />
    );
  }
}

export const schemas = {
  route: {
    schema: {
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
      enabled: {
        type: 'bool',
        props: { label: 'enabled' },
      },
      debug_flow: {
        type: 'bool',
        props: { label: 'Debug' },
      },
      export_reporting: {
        type: 'bool',
        props: { label: 'Export reports' },
      },
      capture: {
        type: 'bool',
        props: { label: 'Capture traffic' },
      },
      groups: {
        type: 'array',
        props: {
          label: 'Service groups',
          valuesFrom: '/bo/api/proxy/api/groups',
          transformer: (a) => ({ value: a.id, label: a.name }),
        },
      },
    },
    flow: [
      '_loc',
      'id',
      'name',
      'description',
      'tags',
      'metadata',
      'enabled',
      'debug_flow',
      'export_reporting',
      'capture',
      'groups',
    ],
  },
  frontend: {
    schema: {
      domains: {
        type: 'array',
        props: { label: 'Domains' },
      },
      strip_path: {
        type: 'bool',
        props: { label: 'Strip path' },
      },
      exact: {
        type: 'bool',
        props: { label: 'Exact match' },
      },
      methods: {
        type: 'array',
        props: {
          label: 'Methods',
          possibleValues: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'PATCH'].map((e) => ({
            label: e,
            value: e,
          })),
        },
      },
      headers: {
        type: 'object',
        props: { label: 'Expected headers' },
      },
      query: {
        type: 'object',
        props: { label: 'Expected query params' },
      },
    },
    flow: ['domains', 'strip_path', 'exact', 'methods', 'headers', 'query'],
  },
  backend: {
    schema: {
      rewrite: {
        type: 'bool',
        props: { label: 'Full path rewrite' },
      },
      root: {
        type: 'string',
        props: { label: 'Targets root path' },
      },
      'load_balancing.type': {
        type: 'select',
        props: {
          label: 'type',
          possibleValues: [
            'RoundRobin',
            'Random',
            'Sticky',
            'IpAddressHash',
            'BestResponseTime',
            'WeightedBestResponseTime',
          ].map((e) => ({ value: e, label: e })),
        },
      },
      'load_balancing.ratio': {
        type: 'number',
        display: (obj) => {
          if (!obj.load_balancing) {
            return false;
          } else {
            return obj.load_balancing.type === 'WeightedBestResponseTime';
          }
        },
        props: {
          label: 'ratio',
        },
      },
      'health_check.enabled': {
        type: 'bool',
        props: {
          label: 'enabled',
        },
      },
      'health_check.url': {
        type: 'string',
        props: {
          label: 'URL',
        },
      },
      targets: {
        type: 'array',
        props: {
          label: 'Targets',
          placeholder: 'Target URL',
          help:
            'The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures',
          component: Target,
          defaultValue: {
            id: 'mirror.otoroshi.io',
            hostname: 'mirror.otoroshi.io',
            port: 443,
            tls: true,
            weight: 1,
            predicate: { type: 'AlwaysMatch' },
            protocol: 'HTTP/1.1',
            ip_address: null,
            tls_config: {
              certs: [],
              trusted_certs: [],
              enabled: false,
              loose: false,
              trust_all: false,
            },
          },
        },
      },
      'client.backoff_factor': { type: 'number', props: { label: 'backoff factor' } },
      'client.retries': { type: 'number', props: { label: 'retries' } },
      'client.max_errors': { type: 'number', props: { label: 'max errors', suffix: 'errors' } },
      'client.global_timeout': {
        type: 'number',
        props: { label: 'global timeout', suffix: 'milliseconds' },
      },
      'client.connection_timeout': {
        type: 'number',
        props: { label: 'connection timeout', suffix: 'milliseconds' },
      },
      'client.idle_timeout': {
        type: 'number',
        props: { label: 'idle timeout', suffix: 'milliseconds' },
      },
      'client.call_timeout': {
        type: 'number',
        props: { label: 'call timeout', suffix: 'milliseconds' },
      },
      'client.call_and_stream_timeout': {
        type: 'number',
        props: { label: 'call and stream timeout', suffix: 'milliseconds' },
      },
      'client.retry_initial_delay': {
        type: 'number',
        props: { label: 'initial delay', suffix: 'milliseconds' },
      },
      'client.sample_interval': {
        type: 'number',
        props: { label: 'sample interval', suffix: 'milliseconds' },
      },
      'client.cache_connection_settings.enabled': {
        type: 'bool',
        props: { label: 'cache connection' },
      },
      'client.cache_connection_settings.queue_size': {
        type: 'number',
        props: { label: 'cache connection queue size' },
      },
      'client.custom_timeouts': {
        type: 'array',
        props: { label: 'custom timeouts', component: CustomTimeout },
      },
      'client.host': { type: 'string', props: { label: 'host' } },
      'client.port': { type: 'number', props: { label: 'port' } },
      'client.protocol': { type: 'string', props: { label: 'protocol' } },
      'client.principal': { type: 'string', props: { label: 'principal' } },
      'client.password': { type: 'string', props: { label: 'password' } },
      'client.ntlmDomain': { type: 'string', props: { label: 'NTLM domain' } },
      'client.encoding': { type: 'string', props: { label: 'encoding' } },
      'client.nonProxyHosts': { type: 'array', props: { label: 'non proxy hosts' } },
    },
    flow: [
      'rewrite',
      'root',
      '>>>Health Check',
      'health_check.enabled',
      'health_check.url',
      '>>>Loadbalancing',
      'load_balancing.type',
      'load_balancing.ratio',
      '>>>Client Settings',
      'client.backoff_factor',
      'client.retries',
      'client.max_errors',
      'client.global_timeout',
      'client.connection_timeout',
      'client.idle_timeout',
      'client.call_timeout',
      'client.call_and_stream_timeout',
      'client.retry_initial_delay',
      'client.sample_interval',
      'client.cache_connection_settings.enabled',
      'client.cache_connection_settings.queue_size',
      'client.custom_timeouts',
      '>>>Proxy',
      'client.host',
      'client.port',
      'client.protocol',
      'client.principal',
      'client.password',
      'client.ntlmDomain',
      'client.encoding',
      'client.nonProxyHosts',
      '---',
      'targets',
    ],
  },
  plugin: {
    schema: (plugins) => ({
      enabled: { type: 'bool', props: { label: 'enabled' } },
      debug: { type: 'bool', props: { label: 'debug' } },
      plugin: {
        type: 'select',
        props: {
          label: 'plugin',
          possibleValues: plugins,
          _valuesFrom: '/bo/api/proxy/api/experimental/plugins/all',
          _transformer: (a) => ({
            value: a.id, // TODO: preload list here
            label: a.name,
            desc: a.description,
          }),
        },
      },
      include: { type: 'array', props: { label: 'included paths', suffix: 'regex' } },
      exclude: { type: 'array', props: { label: 'excluded paths', suffix: 'regex' } },
      config: { type: 'jsonobjectcode', props: { label: 'plugin configuration' } },
      plugin_index: { type: 'jsonobjectcode', props: { label: 'plugin index', height: '50px' } },
    }),
    flow: ['plugin', 'enabled', 'debug', 'include', 'exclude', 'plugin_index', 'config'],
  },
};

export class RouteForm extends Component {
  state = { value: null, plugins: [], json: false };

  componentDidMount() {
    this.entity = window.location.pathname.split('/')[3];
    this.client = nextClient.forEntity(
      this.entity === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES
    );
    this.load();
    this.loadPlugins();
  }

  onTestingButtonClick(history, value) {
    history.push(`/routes/${value.id}?tab=flow`, { showTryIt: true });
  }

  load = () => {
    return this.client.findById(this.props.routeId).then((value) => {
      this.setState({ value });
    });
  };

  loadPlugins = () => {
    return fetch('/bo/api/proxy/api/experimental/plugins/all', {
      method: 'GET',
      credentials: 'include',
      headers: {
        accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((plugins) => this.setState({ plugins }));
  };

  save = () => {
    const entity = this.state.value;
    if (this.props.isCreating) {
      return this.client.create(entity).then((e) => this.props.setValue(e));
    } else {
      return this.client.update(entity).then((e) => this.props.setValue(e));
    }
  };

  delete = () => {
    return this.client.deleteById(this.state.value.id);
  };

  render() {
    if (!this.state.value) {
      return null;
    }
    if (this.state.json) {
      return (
        <div style={{ width: '100%', display: 'flex', flexDirection: 'column' }}>
          <button type="button" onClick={(e) => this.setState({ json: false })}>
            switch
          </button>
          <form>
            <JsonObjectAsCodeInput
              editorOnly
              height={window.innerHeight - 180}
              label="plugin"
              value={this.state.value}
              onChange={(value) => this.setState({ value })}
            />
          </form>
        </div>
      );
    }
    return (
      <div style={{ width: '100%', display: 'flex', flexDirection: 'column' }}>
        <button type="button" className="hide" onClick={(e) => this.setState({ json: true })}>
          switch
        </button>
        <Collapse key="informations" label="Informations">
          <Form
            schema={schemas.route.schema}
            flow={schemas.route.flow}
            value={this.state.value}
            onChange={(value) => this.setState({ value })}
          />
        </Collapse>
        <Collapse key="frontend" initCollapsed label="Frontend">
          <Form
            schema={schemas.frontend.schema}
            flow={schemas.frontend.flow}
            value={this.state.value.frontend}
            onChange={(frontend) => this.setState({ value: { ...this.state.value, frontend } })}
          />
        </Collapse>
        <Collapse key="backend_ref" initCollapsed label="Backend reference">
          <Form
            schema={{
              backend_ref: {
                type: 'select',
                props: {
                  label: 'backend references',
                  valuesFrom: '/bo/api/proxy/api/experimental/backends',
                  transformer: (a) => ({
                    value: a.id,
                    label: a.name,
                    desc: a.description,
                  }),
                },
              },
            }}
            flow={['backend_ref']}
            value={{ backend_ref: this.state.value.backend_ref }}
            onChange={(obj) =>
              this.setState({ value: { ...this.state.value, backend_ref: obj.backend_ref } })
            }
          />
        </Collapse>
        {!this.state.value.backend_ref && (
          <Collapse key="backend" initCollapsed label="Backend">
            <Form
              schema={{ ...schemas.backend.schema }}
              flow={schemas.backend.flow}
              value={this.state.value.backend}
              onChange={(backend) => this.setState({ value: { ...this.state.value, backend } })}
            />
          </Collapse>
        )}
        <Collapse key="plugins" initCollapsed label="Plugins">
          {this.state.value.plugins.map((plugin, idx) => {
            return (
              <Plugin
                pluginInfos={this.state.plugins.filter((p) => p.id === plugin.plugin)[0]}
                plugin={plugin}
                schema={schemas.plugin.schema(
                  this.state.plugins.map((a) => ({
                    value: a.id,
                    label: a.name,
                    desc: a.description,
                  }))
                )}
                flow={schemas.plugin.flow}
                onChange={(plugin) => {
                  const plugins = this.state.value.plugins;
                  plugins[idx] = plugin;
                  this.setState({ value: { ...this.state.value, plugins } });
                }}
                onDelete={() => {
                  const plugins = this.state.value.plugins;
                  plugins.splice(idx, 1);
                  this.setState({ value: { ...this.state.value, plugins } });
                }}
                onUp={() => {
                  const plugins = this.state.value.plugins;
                  if (idx > 0) {
                    const newIdx = idx - 1;
                    const prev = plugins[newIdx];
                    plugins[newIdx] = plugin;
                    plugins[idx] = prev;
                    this.setState({ value: { ...this.state.value, plugins } });
                  }
                }}
                onDown={() => {
                  const plugins = this.state.value.plugins;
                  if (idx < plugins.length - 1) {
                    const newIdx = idx + 1;
                    const prev = plugins[newIdx];
                    plugins[newIdx] = plugin;
                    plugins[idx] = prev;
                    this.setState({ value: { ...this.state.value, plugins } });
                  }
                }}
                addBefore={() => {
                  const plugins = this.state.value.plugins;
                  plugins.splice(idx, 0, {
                    enabled: true,
                    debug: false,
                    plugin: null,
                    include: [],
                    exclude: [],
                    config: {},
                    plugin_index: null,
                  });
                  this.setState({ value: { ...this.state.value, plugins } });
                }}
                addAfter={() => {
                  const plugins = this.state.value.plugins;
                  plugins.splice(idx + 1, 0, {
                    enabled: true,
                    debug: false,
                    plugin: null,
                    include: [],
                    exclude: [],
                    config: {},
                    plugin_index: null,
                  });
                  this.setState({ value: { ...this.state.value, plugins } });
                }}
              />
            );
          })}
        </Collapse>
      </div>
    );
  }
}

class Plugin extends Component {
  state = { form: true };

  render() {
    const plugin = this.props.plugin;
    const pluginInfos = this.props.pluginInfos || { name: '', config_flow: [], config_schema: {} };
    if (!plugin) {
      return null;
    }
    if (plugin.plugin === 'cp:otoroshi.next.plugins.ApikeyCalls') {
      pluginInfos.config_flow = [
        'wipe_backend_request',
        'pass_with_user',
        'validate',
        'update_quotas',
        'routing',
        'extractors',
      ];
    }
    return (
      <Collapse label={`   - ${pluginInfos.name}`} initCollapsed={true}>
        <div
          style={{
            width: '100%',
            paddingTop: 5,
            paddingBottom: 5,
            marginTop: 40,
            marginBottom: 10,
            display: 'flex',
            flexDirection: 'row',
            justifyContent: 'flex-end',
            alignItems: 'center',
          }}>
          <div className="col-sm-2"></div>
          <div style={{ width: '100%' }}></div>
          <div className="btn-group" style={{ marginRight: 10, width: 400 }}>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.addBefore()}>
              <i className="fas fa-plus" /> plugin <i className="fas fa-chevron-up" />
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.addAfter()}>
              <i className="fas fa-plus" /> plugin <i className="fas fa-chevron-down" />
            </button>
          </div>
          <div className="btn-group" style={{ marginRight: 10 }}>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.setState({ form: true })}>
              form
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.setState({ form: false })}>
              json
            </button>
          </div>
          <div className="btn-group" style={{ marginRight: 10 }}>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.onUp(plugin)}>
              <i className="fas fa-chevron-up" />
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.onDown(plugin)}>
              <i className="fas fa-chevron-down" />
            </button>
          </div>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={(e) => this.props.onDelete(plugin)}>
              <i className="fas fa-trash" />
            </button>
          </div>
        </div>
        {this.state.form && (
          <>
            <Form
              key={plugin.plugin}
              schema={this.props.schema}
              flow={this.props.flow}
              value={plugin}
              onChange={(p) => this.props.onChange(p)}
            />
            {pluginInfos.config_flow.length > 0 && (
              <div className="row" style={{ width: '100%' }}>
                <div className="col-md-10">
                  <NgForm
                    key={plugin.plugin}
                    value={plugin.config}
                    onChange={(config) => this.props.onChange({ ...plugin, config })}
                    flow={pluginInfos.config_flow}
                    schema={pluginInfos.config_schema}
                  />
                </div>
              </div>
            )}
          </>
        )}
        {!this.state.form && (
          <form>
            <JsonObjectAsCodeInput
              label="plugin"
              value={plugin}
              onChange={(p) => this.props.onChange(p)}
            />
          </form>
        )}
      </Collapse>
    );
  }
}
