import React, { useEffect, useState } from 'react';
import { LabelAndInput, NgForm, NgSelectRenderer } from '../../components/nginputs';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';

// the phases a conditional plugin is able to delegate to
const WRAPPABLE_STEPS = [
  'PreRoute',
  'ValidateAccess',
  'TransformRequest',
  'TransformResponse',
  'CallBackend',
];

// the plugin list is the same for every instance of the form, fetch it once per page load
let wrappablePluginsPromise = null;

function fetchWrappablePlugins() {
  if (!wrappablePluginsPromise) {
    wrappablePluginsPromise = fetch('/bo/api/proxy/api/plugins/all', {
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
      .then((r) => r.json())
      .then((list) =>
        (list || []).filter((plugin) =>
          (plugin.plugin_steps || plugin.pluginSteps || []).some((step) =>
            WRAPPABLE_STEPS.includes(step)
          )
        )
      )
      .catch(() => []);
  }
  return wrappablePluginsPromise;
}

function useWrappablePlugins() {
  const [plugins, setPlugins] = useState([]);
  useEffect(() => {
    let mounted = true;
    fetchWrappablePlugins().then((list) => {
      if (mounted) setPlugins(list);
    });
    return () => {
      mounted = false;
    };
  }, []);
  return plugins;
}

function schemaOf(plugin) {
  return (plugin || {}).config_schema || (plugin || {}).configSchema || {};
}

function flowOf(plugin) {
  return (plugin || {}).config_flow || (plugin || {}).configFlow || [];
}

export default {
  id: 'cp:otoroshi.next.plugins.ConditionalPlugin',
  config_schema: {
    predicates: {
      label: 'Predicates',
      type: 'object',
      array: true,
      format: 'form',
      help: 'All the predicates must match for the wrapped plugin to run',
      schema: {
        path: {
          label: 'path',
          type: 'string',
          props: {
            subTitle: 'Example: $.apikey.metadata.foo',
          },
        },
        value: {
          type: 'code',
          help: 'Example: Contains(bar)',
          props: {
            label: 'Value',
            type: 'json',
            editorOnly: true,
          },
        },
      },
      flow: ['path', 'value'],
    },
    invert: {
      type: 'box-bool',
      label: 'Invert',
      props: {
        description: 'Run the wrapped plugin when the predicates do NOT match',
      },
    },
    evaluation_mode: {
      type: 'select',
      label: 'Evaluation mode',
      props: {
        options: [
          {
            value: 'per_phase',
            label: 'On every phase - most reactive, can be inconsistent across phases',
          },
          { value: 'once', label: 'Once per request - one decision for every phase' },
          { value: 'latch', label: 'Once matched, always run for the rest of the request' },
        ],
      },
    },
    plugin_id: {
      renderer: (props) => {
        const plugins = useWrappablePlugins();
        return (
          <NgSelectRenderer
            label="Plugin"
            value={props.value}
            options={plugins.map((plugin) => ({
              value: plugin.id,
              label: plugin.name || plugin.id,
            }))}
            onChange={(id) => {
              // switching plugin makes the previous configuration meaningless
              const selected = plugins.find((plugin) => plugin.id === id);
              props.rootOnChange({
                ...(props.rootValue || {}),
                plugin_id: id,
                plugin_config: (selected || {}).default_config || {},
              });
            }}
          />
        );
      },
    },
    plugin_config: {
      renderer: (props) => {
        const plugins = useWrappablePlugins();
        const pluginId = (props.rootValue || {}).plugin_id;

        if (!pluginId) return null;

        const selected = plugins.find((plugin) => plugin.id === pluginId);
        const schema = schemaOf(selected);
        const flow = flowOf(selected);

        // a plugin without a form schema, or one not yet loaded, falls back to a raw json editor
        if (Object.keys(schema).length === 0) {
          return (
            <JsonObjectAsCodeInput
              label="Plugin configuration"
              value={props.value || {}}
              onChange={(config) => props.onChange(config)}
              height="200px"
            />
          );
        }

        return (
          <LabelAndInput label="Plugin configuration">
            <NgForm
              schema={schema}
              flow={flow.length > 0 ? flow : Object.keys(schema)}
              value={props.value || {}}
              onChange={(config) => props.onChange(config)}
            />
          </LabelAndInput>
        );
      },
    },
  },
  config_flow: ['predicates', 'invert', 'evaluation_mode', 'plugin_id', 'plugin_config'],
};
