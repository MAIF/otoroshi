import React, { Component } from 'react';
import { Table, BooleanInput, SelectInput } from '../components/inputs';
import { NgBoxBooleanRenderer } from '../components/nginputs/inputs'; 
import WasmPlugin from '../forms/ng_plugins/WasmPlugin'

import * as BackOfficeServices from '../services/BackOfficeServices';

class WasmDataRights extends Component {
  render() {
    const Input = true ? NgBoxBooleanRenderer : BooleanInput;
    const schema = WasmPlugin.config_schema.accesses.schema
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

  formFlow = [
    '_loc', 
    'id', 
    'name', 
    'description', 
    'tags', 
    'metadata',
    '<<<Wasm source',
    'config.source.kind',
    'config.source.path',
    '<<<Wasm configuration',
    'config.memoryPages',
    'config.functionName',
    'config.config',
    'config.allowedHosts',
    'config.preserve',
    'config.wasi',
    '<<<Wasm host access',
    'config.accesses.httpAccess',
    'config.accesses.proxyHttpCallTimeout',
    'config.accesses.proxyStateAccess',
    'config.accesses.configurationAccess',
    'config.accesses.globalDataStoreAccess',
    'config.accesses.pluginDataStoreAccess',
    'config.accesses.globalMapAccess',
    'config.accesses.pluginMapAccess',
  ];

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
    'config.memoryPages': {
      type: 'number',
      props: {
        label: 'Memory pages',
        suffix: '32 Kb'
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
    'config.accesses.httpAccess': { 
      type: 'bool',
      props: {
        label: 'Http Access'
      }
    },
    'config.accesses.proxyStateAccess': { 
      type: 'bool',
      props: {
        label: 'Proxy state access'
      }
    },
    'config.accesses.configurationAccess': { 
      type: 'bool',
      props: {
        label: 'Configuration access'
      }
    },
    'config.accesses.proxyHttpCallTimeout': { 
      type: 'number',
      props: {
        label: 'Http timeout',
        suffix: 'millis.'
      }
    },
    'config.accesses.globalDataStoreAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Datastore access',
        property: 'globalDataStoreAccess',
      }
    },
    'config.accesses.pluginDataStoreAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Plugin scoped datastore access',
        property: 'pluginDataStoreAccess',
      }
    },
    'config.accesses.globalMapAccess': { 
      type: WasmDataRights,
      props: {
        label: 'Global map access',
        property: 'globalMapAccess',
      }
    },
    'config.accesses.pluginMapAccess': { 
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
