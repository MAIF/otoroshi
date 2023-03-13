import React, { Component } from 'react';
import { Table, BooleanInput, SelectInput } from '../components/inputs';
import { NgBoxBooleanRenderer } from '../components/nginputs/inputs'; 
import WasmPlugin from '../forms/ng_plugins/WasmPlugin';
import { Proxy } from '../components/Proxy';

import * as BackOfficeServices from '../services/BackOfficeServices';

function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}

class WasmDataRights extends Component {
  render() {
    const Input = true ? NgBoxBooleanRenderer : BooleanInput;
    const schema = WasmPlugin.config_schema.authorizations.schema
    return (
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label">{this.props.label}</label>
        <div className="col-sm-10">
          <div style={{ width: '100%', display: 'flex', justifyContent: 'flex-start', alignItems: 'center' }}>
            <Input label="Read" description={schema[this.props.property].read.label} value={this.props.value.read} onChange={v => this.props.onChange({ ...this.props.value, read: v })} />
            <Input label="Write" description={schema[this.props.property].write.label} value={this.props.value.write} onChange={v => this.props.onChange({ ...this.props.value, write: v })} />
          </div>
        </div>
      </div>
    );
  }
}

export class WasmSourcePath extends Component {

  state = { local: [], manager: [] }

  componentDidMount() {
    fetch('/bo/api/plugins/wasm', {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
    }).catch(e => ({ json: () => []})).then(r => r.json()).then(plugins => {
      const values = plugins
        .map((plugin) => plugin.versions || [])
        .flat()
        .map((plugin) => {
          const parts = plugin.split('.wasm');
          return {
            label: parts[0],
            value: plugin,
          };
        });
      this.setState({ manager: values });
    })
    BackOfficeServices.findAllWasmPlugins().then(plugins => {
      this.setState({ local: plugins.map(p => ({ label: p.name, value: p.id }))})
    })
  }

  render() {
    const rawValue = this.props.rawValue ||  {
      config: {
        source: {
          kind: 'Unknown',
          path: 'foo',
        }
      }
    }
    const source = this.props.rootValue || rawValue.config.source;
    const kind = source.kind.toLowerCase();
    if (kind === 'unknown') {
      return null;
    } else if (kind === 'wasmmanager') {
      return (
        <SelectInput
          label="Wasm plugin"
          value={this.props.value}
          onChange={(t) => this.props.onChange(t)}
          possibleValues={this.state.manager}
        />
      );
    } else if (kind === 'local') {
      return (
        <SelectInput
          label="Wasm plugin"
          value={this.props.value}
          onChange={(t) => this.props.onChange(t)}
          possibleValues={this.state.local}
        />
      );
    } else {
      let label = "Path"
      if (kind === "http") {
        label = "URL"
      } else if (kind === "base64") {
        label = "Base64 encoded script"
      }
      return (
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label">{label}</label>
          <div className="col-sm-10">
            <input type="text" className="form-control" value={source.path} onChange={e => this.props.onChange(e.target.value)} />
          </div>
        </div>
      );
    }
  }
}

export class WasmPluginsPage extends Component {
  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
    {
      title: 'Steps',
      filterId: 'steps',
      content: (item) => item.steps.map(v => <span className="badge bg-success">{v}</span>),
    },
  ];

  deleteWasmPlugin = (wasmPlugin, table) => {
    window
      .newConfirm('Are you sure you want to delete wasm plugin "' + wasmPlugin.name + '"')
      .then((confirmed) => {
        if (confirmed) {
          BackOfficeServices.deleteWasmPlugin(wasmPlugin).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle('All Wasm Plugins');
  }

  gotoWasmPlugin = (wasmPlugin) => {
    this.props.history.push({
      pathname: `/wasm-plugins/edit/${wasmPlugin.id}`,
    });
  };

  formFlow = (value) => [
    '_loc', 
    'id', 
    'name', 
    'description', 
    'steps',
    'tags', 
    'metadata',
    '<<<Wasm source',
    'config.source.kind',
    'config.source.path',
    value.config.source.kind.toLowerCase() === 'http' && '>>>Wasm source http opts',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.method',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.headers',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.timeout',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.followRedirect',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.proxy',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.tls.enabled',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.tls.loose',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.tls.trust_all',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.tls.certs',
    value.config.source.kind.toLowerCase() === 'http' && 'config.source.opts.tls.trusted_certs',
    '<<<Wasm configuration',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.memoryPages',
    'config.functionName',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.config',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.allowedHosts',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.allowedPaths',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.preserve',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.wasi',
    value.config.source.kind.toLowerCase() !== 'local' && '<<<Wasm host function authorizations',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.httpAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.proxyHttpCallTimeout',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.proxyStateAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.configurationAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.globalDataStoreAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.pluginDataStoreAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.globalMapAccess',
    value.config.source.kind.toLowerCase() !== 'local' && 'config.authorizations.pluginMapAccess',
  ].filter(v => !!v);

  formSchema = {
    id: { type: 'string', props: { label: 'Id', placeholder: '---' } },
    _loc: {
      type: 'location',
      props: {},
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'Nice Wasm Plugin' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'A nice wasm plugin to do whatever you want' },
    },
    steps: {
      type: 'array',
      props: {
        label: 'Steps',
        possibleValues: [
          'Sink',
          'PreRoute',
          'ValidateAccess',
          'TransformRequest',
          'TransformResponse',
          'MatchRoute',
          // 'HandlesTunnel',
          'HandlesRequest',
          'CallBackend',
          'Job',
        ].map(v => ({ label: v, value: v })),
      },
    },
    'config.source.path': {
      type: WasmSourcePath,
      props: {
        label: 'Path'
      }
    },
    'config.source.kind': {
      type: 'select',
      props: {
        label: 'Kind',
        possibleValues: ['Base64', 'Http', 'WasmManager', 'File'].map(v => ({ label: v, value: v }))
      }
    },
    'config.source.opts.headers': { type: 'object', props: { label: 'Headers' } },
    'config.source.opts.timeout': { type: 'number', props: { label: 'Timeout', suffix: 'millis.' } },
    'config.source.opts.method': { type: 'string', props: { label: 'Method' } },
    'config.source.opts.followRedirect': { type: 'bool', props: { label: 'Follow redirects' } },
    'config.source.opts.proxy': { type: Proxy, props: { label: 'Proxy' } },
    'config.source.opts.tls.enabled': {
      type: 'bool',
      props: { label: 'Custom TLS Settings' },
    },
    'config.source.opts.tls.loose': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: { label: 'TLS loose' },
    },
    'config.source.opts.tls.trust_all': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: { label: 'TrustAll' },
    },
    'config.source.opts.tls.certs': {
      type: 'array',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: {
        label: 'Client certificates',
        placeholder: 'Choose a client certificate',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => ({
          value: a.id,
          label: (
            <span>
              <span className="badge bg-success" style={{ minWidth: 63 }}>
                {a.certType}
              </span>{' '}
              {a.name} - {a.description}
            </span>
          ),
        }),
      },
    },
    'config.source.opts.tls.trusted_certs': {
      type: 'array',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls && !v.mtlsConfig.trustAll),
      props: {
        label: 'Trusted certificates',
        placeholder: 'Choose a trusted certificate',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => ({
          value: a.id,
          label: (
            <span>
              <span className="badge bg-success" style={{ minWidth: 63 }}>
                {a.certType}
              </span>{' '}
              {a.name} - {a.description}
            </span>
          ),
        }),
      },
    },
    'config.source.opts': {
      type: 'object',
      props: {
        label: 'Options'
      }
    },
    'config.memoryPages': {
      type: 'number',
      props: {
        label: 'Memory pages',
        suffix: 'pages of 32 Kb'
      }
    },
    'config.functionName': { 
      type: 'string',
      props: {
        label: 'Function name',
        placeholder: 'transform_request'
      }
    },
    'config.config': { 
      type: 'object',
      props: {
        label: 'Config. map'
      } 
    },
    'config.allowedHosts': { 
      type: 'array',
      props: {
        label: 'Allow hosts'
      }
    },
    'config.allowedPaths': { 
      type: 'object',
      props: {
        label: 'Allow paths'
      }
    },
    'config.preserve': { 
      type: 'bool',
      props: {
        label: 'Preserve VMs'
      }
    },
    'config.wasi': { 
      type: 'bool',
      props: {
        label: 'WASI'
      }
    },
    'config.authorizations.httpAccess': { 
      type: 'bool',
      props: {
        label: 'Http Access'
      }
    },
    'config.authorizations.proxyStateAccess': { 
      type: 'bool',
      props: {
        label: 'Proxy state access'
      }
    },
    'config.authorizations.configurationAccess': { 
      type: 'bool',
      props: {
        label: 'Configuration access'
      }
    },
    'config.authorizations.proxyHttpCallTimeout': { 
      type: 'number',
      props: {
        label: 'Http timeout',
        suffix: 'millis.'
      }
    },
    'config.authorizations.globalDataStoreAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Datastore access',
        property: 'globalDataStoreAccess',
      }
    },
    'config.authorizations.pluginDataStoreAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Plugin scoped datastore access',
        property: 'pluginDataStoreAccess',
      }
    },
    'config.authorizations.globalMapAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Global map access',
        property: 'globalMapAccess',
      }
    },
    'config.authorizations.pluginMapAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Plugin scoped map access',
        property: 'pluginMapAccess',
      }
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="wasm-plugins"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          defaultTitle={this.title}
          defaultValue={BackOfficeServices.createNewWasmPlugin}
          itemName="WasmPlugin"
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllWasmPluginsWithPagination({
              ...paginationState,
              //fields: ['id', 'name', 'description'],
            })
          }
          updateItem={BackOfficeServices.updateWasmPlugin}
          deleteItem={BackOfficeServices.deleteWasmPlugin}
          createItem={BackOfficeServices.createWasmPlugin}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoWasmPlugin}
          firstSort={0}
          extractKey={(item) => {
            return item.id;
          }}
          itemUrl={(i) => `/bo/dashboard/wasm-plugins/edit/${i.id}`}
          export={true}
          kubernetesKind="WasmPlugin"
        />
      </div>
    );
  }
}
