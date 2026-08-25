import React, { Component } from 'react';
import { Form } from './inputs';
import { Collapse } from './inputs/Collapse';
import { JsonObjectAsCodeInput } from './inputs/CodeInput';
import { NgForm } from './nginputs/form';
import { getPlugins, getOldPlugins } from '../services/BackOfficeServices';
import { Plugins } from '../forms/ng_plugins';

const EMPTY_SLOT = {
  enabled: true,
  debug: false,
  plugin: null,
  include: [],
  exclude: [],
  bound_listeners: [],
  config: {},
  plugin_index: null,
};

const slotSchema = (plugins) => ({
  enabled: { type: 'bool', props: { label: 'enabled' } },
  debug: { type: 'bool', props: { label: 'debug' } },
  plugin: {
    type: 'select',
    props: {
      label: 'plugin',
      possibleValues: plugins,
    },
  },
  bound_listeners: { type: 'array', props: { label: 'bound listeners' } },
  include: { type: 'array', props: { label: 'included paths', suffix: 'regex' } },
  exclude: { type: 'array', props: { label: 'excluded paths', suffix: 'regex' } },
  config: { type: 'jsonobjectcode', props: { label: 'plugin configuration' } },
  plugin_index: { type: 'jsonobjectcode', props: { label: 'plugin index', height: '50px' } },
});

const SLOT_FLOW = [
  'plugin',
  'enabled',
  'debug',
  'include',
  'exclude',
  'bound_listeners',
  'plugin_index',
];

function shortName(pluginId) {
  return (pluginId || '')
    .split('.')
    .slice(-1)[0]
    .replace(/([a-z])([A-Z])/g, '$1 $2');
}

// The route form only reads /plugins/all, which misses the schemas defined in JS and forces it to
// special case some plugins by hand. Merging the three sources the way the designer does gives
// every plugin its real form.
function loadCatalog() {
  return Promise.all([
    Promise.resolve(
      Plugins().map((plugin) => ({
        ...plugin,
        config_schema:
          typeof plugin.config_schema === 'function'
            ? plugin.config_schema({ showAdvancedDesignerView: () => {} })
            : plugin.config_schema,
      }))
    ),
    getOldPlugins(),
    getPlugins(),
  ]).then(([jsPlugins, oldPlugins, allPlugins]) => {
    const merged = [
      ...allPlugins,
      ...oldPlugins.map((p) => ({ ...p, legacy: true })),
      ...jsPlugins,
    ].map((plugin) => ({
      ...plugin,
      config_schema: plugin.config_schema || plugin.configSchema || {},
      config_flow: plugin.config_flow || plugin.configFlow || [],
      default_config: plugin.default_config || plugin.defaultConfig || {},
    }));
    return Object.values(
      merged.reduce(
        (acc, plugin) => ({
          ...acc,
          [plugin.id]: { ...(acc[plugin.id] || {}), ...plugin },
        }),
        {}
      )
    ).sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
  });
}

class Plugin extends Component {
  state = { form: true };

  render() {
    const plugin = this.props.plugin;
    const pluginInfos = this.props.pluginInfos || { name: '', config_flow: [], config_schema: {} };
    if (!plugin) {
      return null;
    }
    const name = pluginInfos.name || shortName(plugin.plugin);
    const label = (
      <span style={{ marginLeft: 12 }}>
        <span style={{ opacity: 0.5, fontWeight: 'normal' }}>{this.props.position}.</span>{' '}
        {name ? (
          name
        ) : (
          <span style={{ fontWeight: 'normal', fontStyle: 'italic', opacity: 0.6 }}>
            choose a plugin
          </span>
        )}
        {plugin.plugin && !plugin.enabled && (
          <span className="badge bg-secondary" style={{ marginLeft: 8, fontWeight: 'normal' }}>
            disabled
          </span>
        )}
        {plugin.plugin && plugin.debug && (
          <span className="badge bg-warning" style={{ marginLeft: 8, fontWeight: 'normal' }}>
            debug
          </span>
        )}
      </span>
    );
    return (
      <Collapse label={label} initCollapsed={true} noLeftColumn>
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
          }}
        >
          <div className="col-sm-2"></div>
          <div style={{ width: '100%' }}></div>
          <div className="btn-group" style={{ marginRight: 10, width: 400 }}>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.addBefore()}
            >
              <i className="fas fa-plus" /> plugin <i className="fas fa-chevron-up" />
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={(e) => this.props.addAfter()}
            >
              <i className="fas fa-plus" /> plugin <i className="fas fa-chevron-down" />
            </button>
          </div>
          <div className="btn-group" style={{ marginRight: 10 }}>
            <button
              type="button"
              className={`btn btn-sm ${this.state.form ? 'btn-primaryColor' : 'btn-primary'}`}
              onClick={(e) => this.setState({ form: true })}
            >
              form
            </button>
            <button
              type="button"
              className={`btn btn-sm ${this.state.form ? 'btn-primary' : 'btn-primaryColor'}`}
              onClick={(e) => this.setState({ form: false })}
            >
              json
            </button>
          </div>
          <div className="btn-group" style={{ marginRight: 10 }}>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              disabled={this.props.isFirst}
              onClick={(e) => this.props.onUp(plugin)}
            >
              <i className="fas fa-chevron-up" />
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              disabled={this.props.isLast}
              onClick={(e) => this.props.onDown(plugin)}
            >
              <i className="fas fa-chevron-down" />
            </button>
          </div>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={(e) => this.props.onDelete(plugin)}
            >
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
            {pluginInfos.config_flow.length === 0 && (
              <form>
                <JsonObjectAsCodeInput
                  label="plugin configuration"
                  value={plugin.config}
                  onChange={(config) => this.props.onChange({ ...plugin, config })}
                />
              </form>
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

// Edits an array of NgPluginInstance. Same shape as the plugins section of the route form, reusable
// wherever a plugin chain lives outside of a route: apikeys, api plans, ...
export class PluginsChainEditor extends Component {
  state = { plugins: [] };

  componentDidMount() {
    loadCatalog().then((plugins) => this.setState({ plugins }));
  }

  // the legacy Form hands over {} when the value is still undefined
  slots = () => (Array.isArray(this.props.value) ? this.props.value : []);

  update = (slots) => this.props.onChange(slots);

  insertAt = (idx) => {
    const slots = [...this.slots()];
    slots.splice(idx, 0, { ...EMPTY_SLOT });
    this.update(slots);
  };

  render() {
    const slots = this.slots();
    const possibleValues = this.state.plugins.map((a) => ({
      value: a.id,
      label: a.name || shortName(a.id),
      desc: a.description,
    }));
    return (
      <div style={{ width: '100%' }}>
        {slots.map((plugin, idx) => (
          <Plugin
            key={idx}
            pluginInfos={this.state.plugins.filter((p) => p.id === plugin.plugin)[0]}
            plugin={plugin}
            position={idx + 1}
            isFirst={idx === 0}
            isLast={idx === slots.length - 1}
            schema={slotSchema(possibleValues)}
            flow={SLOT_FLOW}
            onChange={(p) => {
              const next = [...slots];
              next[idx] = p;
              this.update(next);
            }}
            onDelete={() => this.update(slots.filter((_, i) => i !== idx))}
            onUp={() => {
              if (idx > 0) {
                const next = [...slots];
                next[idx - 1] = slots[idx];
                next[idx] = slots[idx - 1];
                this.update(next);
              }
            }}
            onDown={() => {
              if (idx < slots.length - 1) {
                const next = [...slots];
                next[idx + 1] = slots[idx];
                next[idx] = slots[idx + 1];
                this.update(next);
              }
            }}
            addBefore={() => this.insertAt(idx)}
            addAfter={() => this.insertAt(idx + 1)}
          />
        ))}
        <div className="row mb-3">
          <div className="col-sm-10 offset-sm-2">
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={() => this.insertAt(slots.length)}
            >
              <i className="fas fa-plus" /> add a plugin
            </button>
          </div>
        </div>
      </div>
    );
  }
}

export default PluginsChainEditor;
