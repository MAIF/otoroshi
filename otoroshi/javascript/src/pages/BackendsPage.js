import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { nextClient } from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';

class Target extends Component {

  formSchema = {
    "id": { type: 'string', props: { label: 'Id' }},
    "hostname": { type: 'string', props: { label: 'Hostname' }},
    "port": { type: 'number', props: { label: 'Port' }},
    "tls": { type: 'bool', props: { label: 'TLS' }},
    "weight": { type: 'number', props: { label: 'Weight' }},
    "predicate.type": { type: 'select', props: { label: 'Predicate', possibleValues: ['AlwaysMatch', 'GeolocationMatch', 'NetworkLocationMatch'].map(e => ({ label: e, value: e })) }},
    "predicate.position": { type: 'array', display: (obj) => obj.predicate.type === 'GeolocationMatch', props: { label: 'Predicate positions' }},
    "predicate.provider": { type: 'string', display: (obj) => obj.predicate.type === 'NetworkLocationMatch', props: { label: 'Predicate provider' }},
    "predicate.region": { type: 'string', display: (obj) => obj.predicate.type === 'NetworkLocationMatch', props: { label: 'Predicate region' }},
    "predicate.zone": { type: 'string', display: (obj) => obj.predicate.type === 'NetworkLocationMatch', props: { label: 'Predicate zone' }},
    "predicate.dataCenter": { type: 'string', display: (obj) => obj.predicate.type === 'NetworkLocationMatch', props: { label: 'Predicate data center' }},
    "predicate.rack": { type: 'string', display: (obj) => obj.predicate.type === 'NetworkLocationMatch', props: { label: 'Predicate rack' }},
    "protocol": { type: 'string', props: { label: 'Protocol', possibleValues: ['HTTP/1.0', 'HTTP/1.1', 'HTTP/2.0'].map(e => ({ label: e, value: e })) }},
    "ip_address": { type: 'string', props: { label: 'IP Address' }},
    "tls_config.enabled": { type: 'bool', props: { label: 'Enabled' }},
    "tls_config.loose": { type: 'bool', props: { label: 'Loose' }},
    "tls_config.trust_all": { type: 'bool', props: { label: 'Trust all' }},
    "tls_config.certs": { type: 'array', props: { label: 'Certificates' }},
    "tls_config.trusted_certs": { type: 'array', props: { label: 'Trusted Certificates' }},
  }

  formFlow = [
    "id",
    "hostname",
    "port",
    "tls",
    "weight",
    "predicate.type",
    "predicate.position",
    "predicate.provider",
    "predicate.region",
    "predicate.zone",
    "predicate.dataCenter",
    "predicate.rack",
    "protocol",
    "ip_address",
    '>>>TLS Settings',
    "tls_config.enabled",
    "tls_config.loose",
    "tls_config.trust_all",
    "tls_config.certs",
    "tls_config.trusted_certs",
  ]

  render() {
    return (
      <Form
        schema={this.formSchema}
        flow={this.formFlow}
        value={this.props.itemValue}
        onChange={e => {
          const arr = this.props.value;
          arr[this.props.idx] = e;
          this.props.onChange(arr)
        }}
      />
    )
  }
}

class CustomTimeout extends Component {

  formSchema = {
    "path": { type: 'string', props: { label: 'Path' }},
    "global_timeout": { type: 'number', props: { label: 'global timeout', suffix: 'milliseconds' }},
    "connection_timeout": { type: 'number', props: { label: 'connection timeout', suffix: 'milliseconds' }},
    "idle_timeout": { type: 'number', props: { label: 'idle timeout', suffix: 'milliseconds' }},
    "call_timeout": { type: 'number', props: { label: 'call timeout', suffix: 'milliseconds' }},
    "call_and_stream_timeout": { type: 'number', props: { label: 'call and stream timeout', suffix: 'milliseconds' }},
  }

  formFlow = [
    "path",
    "global_timeout",       
    "connection_timeout",
    "idle_timeout",
    "call_timeout",
    "call_and_stream_timeout",
  ]

  render() {
    return (
      <Form
        schema={this.formSchema}
        flow={this.formFlow}
        value={this.props.itemValue}
        onChange={e => {
          const arr = this.props.value;
          arr[this.props.idx] = e;
          this.props.onChange(arr)
        }}
      />
    )
  }
}

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
    'backend.rewrite': {
      type: 'bool',
      props: { label: 'Full path rewrite' }
    },
    'backend.root': {
      type: 'string',
      props: { label: 'Targets root path' }
    },
    'backend.load_balancing.type': {
      type: 'select',
      props: {
        label: 'type',
        possibleValues: ['RoundRobin', 'Random', 'Sticky', 'IpAddressHash', 'BestResponseTime', 'WeightedBestResponseTime'].map(e => ({ value: e, label: e })),
      },
    },
    'backend.load_balancing.ratio': {
      type: 'number',
      display: (obj) => {
        if (!obj.backend.load_balancing) {
          return false
        } else {
          return obj.backend.load_balancing.type === 'WeightedBestResponseTime' 
        }
      },
      props: {
        label: 'ratio',
      },
    },
    'backend.health_check.enabled': {
      type: 'bool',
      props: {
        label: 'enabled',
      },
    },
    'backend.health_check.url': {
      type: 'string',
      props: {
        label: 'URL',
      },
    },
    'backend.targets': {
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
    'backend.client.backoff_factor': { type: 'number', props: { label: 'backoff factor' }},
    'backend.client.retries': { type: 'number', props: { label: 'retries' }},
    'backend.client.max_errors': { type: 'number', props: { label: 'max errors', suffix: 'errors' }},
    'backend.client.global_timeout': { type: 'number', props: { label: 'global timeout', suffix: 'milliseconds' }},
    'backend.client.connection_timeout': { type: 'number', props: { label: 'connection timeout', suffix: 'milliseconds' }},
    'backend.client.idle_timeout': { type: 'number', props: { label: 'idle timeout', suffix: 'milliseconds' }},
    'backend.client.call_timeout': { type: 'number', props: { label: 'call timeout', suffix: 'milliseconds' }},
    'backend.client.call_and_stream_timeout': { type: 'number', props: { label: 'call and stream timeout', suffix: 'milliseconds' }},
    'backend.client.retry_initial_delay': { type: 'number', props: { label: 'initial delay', suffix: 'milliseconds' }},
    'backend.client.sample_interval': { type: 'number', props: { label: 'sample interval', suffix: 'milliseconds' }},
    'backend.client.cache_connection_settings.enabled': { type: 'bool', props: { label: 'cache connection' }},
    'backend.client.cache_connection_settings.queue_size': { type: 'number', props: { label: 'cache connection queue size' }},
    'backend.client.custom_timeouts': { type: 'array', props: { label: 'custom timeouts', component: CustomTimeout }},
    'backend.client.host': { type: 'string', props: { label: 'host' }},
    'backend.client.port' : { type: 'number', props: { label: 'port' }}, 
    'backend.client.protocol' : { type: 'string', props: { label: 'protocol' }},  
    'backend.client.principal': { type: 'string', props: { label: 'principal' }},  
    'backend.client.password': { type: 'string', props: { label: 'password' }},   
    'backend.client.ntlmDomain': { type: 'string', props: { label: 'NTLM domain' }}, 
    'backend.client.encoding': { type: 'string', props: { label: 'encoding' }},   
    'backend.client.nonProxyHosts': { type: 'array', props: { label: 'non proxy hosts' }},
  };

  formFlow = [
    '_loc', 
    'id', 
    'name', 
    'description', 
    'tags', 
    'metadata',
    '---',
    'backend.rewrite',
    'backend.root',
    '>>>Health Check',
    'backend.health_check.enabled',
    'backend.health_check.url',
    '>>>Loadbalancing',
    'backend.load_balancing.type',
    'backend.load_balancing.ratio',
    '>>>Client Settings',
    'backend.client.backoff_factor',
    'backend.client.retries',
    'backend.client.max_errors',
    'backend.client.global_timeout',
    'backend.client.connection_timeout',
    'backend.client.idle_timeout',
    'backend.client.call_timeout',
    'backend.client.call_and_stream_timeout',
    'backend.client.retry_initial_delay',
    'backend.client.sample_interval',
    'backend.client.cache_connection_settings.enabled',
    'backend.client.cache_connection_settings.queue_size',
    'backend.client.custom_timeouts',
    '>>>Proxy',
    'backend.client.host',
    'backend.client.port',
    'backend.client.protocol',
    'backend.client.principal',
    'backend.client.password',
    'backend.client.ntlmDomain',
    'backend.client.encoding',
    'backend.client.nonProxyHosts',
    '---',
    'backend.targets',
  ];

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
        itemUrl={(item) =>  `/bo/dashboard/backends/edit/${item.id}`}
        showActions={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="Backend"
      />
    );
  }
}
