import React, { Component } from 'react';
import { nextClient } from '../../services/BackOfficeServices';
import { Table, Form } from '../../components/inputs';
import { Target, CustomTimeout } from '../BackendsPage';
import { Collapse } from '../../components/inputs/Collapse';

const schemas = {
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
          valuesFrom: "/bo/api/proxy/api/groups",
          transformer: (a) => ({ value: a.id, label: a.name })
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
        props: { label: 'Strip path' }
      },
      exact: {
        type: 'bool',
        props: { label: 'Exact match' }
      },
      methods: {
        type: 'array',
        props: {
          label: 'methods',
          possibleValues: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'PATCH'].map(e => ({ label: e, value: e }))
        }
      },
      headers: {
        type: 'object',
        props: { label: 'Expected headers' }
      },
      query: {
        type: 'object',
        props: { label: 'Expected query params' }
      },
    },
    flow: ['domains', 'strip_path', 'exact',  'methods', 'headers', 'query'],
  },
  backend: {
    schema: {
      'rewrite': {
        type: 'bool',
        props: { label: 'Full path rewrite' }
      },
      'root': {
        type: 'string',
        props: { label: 'Targets root path' }
      },
      'load_balancing.type': {
        type: 'select',
        props: {
          label: 'type',
          possibleValues: ['RoundRobin', 'Random', 'Sticky', 'IpAddressHash', 'BestResponseTime', 'WeightedBestResponseTime'].map(e => ({ value: e, label: e })),
        },
      },
      'load_balancing.ratio': {
        type: 'number',
        display: (obj) => {
          if (!obj.load_balancing) {
            return false
          } else {
            return obj.load_balancing.type === 'WeightedBestResponseTime' 
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
      'targets': {
        type: 'array',
        props: {
          label: "Targets",
          placeholder: "Target URL",
          help: "The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures",
          component: Target,
          defaultValue: {
            "id"        : "mirror.otoroshi.io",
            "hostname"  : "mirror.otoroshi.io",
            "port"      : 443,
            "tls"       : true,
            "weight"    : 1,
            "predicate" : { type: 'AlwaysMatch' },
            "protocol"  : 'HTTP/1.1',
            "ip_address": null,
            "tls_config": {
              "certs": [],
              "trusted_certs": [],
              "enabled": false,
              "loose": false,
              "trust_all": false
            }
          },
        }
      },
      'client.backoff_factor': { type: 'number', props: { label: 'backoff factor' }},
      'client.retries': { type: 'number', props: { label: 'retries' }},
      'client.max_errors': { type: 'number', props: { label: 'max errors', suffix: 'errors' }},
      'client.global_timeout': { type: 'number', props: { label: 'global timeout', suffix: 'milliseconds' }},
      'client.connection_timeout': { type: 'number', props: { label: 'connection timeout', suffix: 'milliseconds' }},
      'client.idle_timeout': { type: 'number', props: { label: 'idle timeout', suffix: 'milliseconds' }},
      'client.call_timeout': { type: 'number', props: { label: 'call timeout', suffix: 'milliseconds' }},
      'client.call_and_stream_timeout': { type: 'number', props: { label: 'call and stream timeout', suffix: 'milliseconds' }},
      'client.retry_initial_delay': { type: 'number', props: { label: 'initial delay', suffix: 'milliseconds' }},
      'client.sample_interval': { type: 'number', props: { label: 'sample interval', suffix: 'milliseconds' }},
      'client.cache_connection_settings.enabled': { type: 'bool', props: { label: 'cache connection' }},
      'client.cache_connection_settings.queue_size': { type: 'number', props: { label: 'cache connection queue size' }},
      'client.custom_timeouts': { type: 'array', props: { label: 'custom timeouts', component: CustomTimeout }},
      'client.host': { type: 'string', props: { label: 'host' }},
      'client.port' : { type: 'number', props: { label: 'port' }}, 
      'client.protocol' : { type: 'string', props: { label: 'protocol' }},  
      'client.principal': { type: 'string', props: { label: 'principal' }},  
      'client.password': { type: 'string', props: { label: 'password' }},   
      'client.ntlmDomain': { type: 'string', props: { label: 'NTLM domain' }}, 
      'client.encoding': { type: 'string', props: { label: 'encoding' }},   
      'client.nonProxyHosts': { type: 'array', props: { label: 'non proxy hosts' }},
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
    ]
  },
  plugin: {
    schema: {
      'enabled': { type: 'bool', props: { label: 'enabled' }},
      'debug': { type: 'bool', props: { label: 'debug' }},
      'plugin': { type: 'select', props: { label: 'plugin', valuesFrom: '/bo/api/proxy/api/experimental/plugins/all', transformer: (a) => ({
        value: a.id, // TODO: preload list here
        label: a.name,
        desc: a.description,
      })} },
      'include': { type: 'array', props: { label: 'included paths', suffix: 'regex' }},
      'exclude': { type: 'array', props: { label: 'excluded paths', suffix: 'regex' }},
      'config': { type: 'jsonobjectcode', props: { label: 'config' }},
      'plugin_index': { type: 'jsonobjectcode', props: { label: 'index' }},
    },
    flow: [
      'enabled',
      'plugin',
      'debug',
      'include',
      'exclude',
      'plugin_index',
      'config'
    ]
  }
}

export class RouteForm extends Component {

  state = { value: null };

  componentDidMount() {
    this.client = nextClient.forEntity(nextClient.ENTITIES.ROUTES);
    this.load();
  }

  load = () => {
    this.client.findById(this.props.routeId).then(value => {
      console.log(value)
      this.setState({ value })
    })
  }

  save = (entity) => {
    this.client.create(entity)
  }

  render() {
    if (!this.state.value) {
      return null;
    }
    return (
      <div style={{ width: '100%', display: 'flex', flexDirection: 'column' }}>
        <Collapse key="informations" label="Informations">
          <Form
            schema={schemas.route.schema}
            flow={schemas.route.flow}
            value={this.state.value}
            onChange={value => this.setState({ value })}
          />
        </Collapse>
        <Collapse key="frontend" initCollapsed label="Frontend">
          <Form
            schema={schemas.frontend.schema}
            flow={schemas.frontend.flow}
            value={this.state.value.frontend}
            onChange={frontend => this.setState({ value: { ...this.state.value, frontend } })}
          />
        </Collapse>
        <Collapse key="backend" initCollapsed label="Backend">
          <Form
            schema={schemas.backend.schema}
            flow={schemas.backend.flow}
            value={this.state.value.backend}
            onChange={backend => this.setState({ value: { ...this.state.value, backend } })}
          />
        </Collapse>
        <Collapse key="plugins" initCollapsed label="Plugins">
          {this.state.value.plugins.map((plugin, idx) => {
            // TODO: header
            // TODO: remove
            // TODO: move
            // TODO: show json or form
            return (
              <>
                <Form
                  key={plugin.plugin}
                  schema={schemas.plugin.schema}
                  flow={schemas.plugin.flow}
                  value={plugin}
                  onChange={plugin => {
                    const plugins = this.state.value.plugins;
                    plugins[idx] = plugin;
                    this.setState({ value: { ...this.state.value, plugins  } })
                  }}
                />
                <div style={{ width: '100%', height: 5, backgroundColor: 'red' }} />
              </>
            );
          })}
        </Collapse>
      </div>
    )
  }
}