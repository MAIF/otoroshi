import React from 'react';
import { useEffect } from 'react';
import { useState } from 'react';
import { LabelAndInput, NgSelectRenderer } from '../../components/nginputs';

// don't import this file in the index.js
// it served as template for the other Wasm plugin
const schema = {
  compiler_source: {
    // label: 'Compiler source',
    renderer: props => {
      const [plugins, setPlugins] = useState([]);

      useEffect(() => {
        fetch('/bo/api/plugins/wasm', {
          headers: {
            credentials: 'include',
            headers: {
              Accept: 'application/json',
              'Content-Type': 'application/json',
            }
          }
        })
          .then(r => r.json())
          .then(plugins => {
            setPlugins(plugins
              .map(plugin => (plugin.versions || []))
              .flat()
              .map(plugin => {
                const parts = plugin.split('.wasm');
                return {
                  filename: parts[0],
                  wasm: plugin
                }
              })
            )
          });
      }, []);

      return <LabelAndInput label="Compiler source">
        <NgSelectRenderer
          value={props.value}
          placeholder="Select a plugin"
          label={' '}
          ngOptions={{
            spread: true
          }}
          onChange={props.onChange}
          margin={0}
          style={{ flex: 1 }}
          options={plugins}
          optionsTransformer={(arr) =>
            arr.map((item) => ({ label: item.filename, value: item.wasm }))
          } />
      </LabelAndInput>
    }
  },
  raw_source: {
    type: 'string',
    label: 'Raw source'
  },
  memoryPages: {
    type: 'number',
    label: 'Max number of pages',
    props: {
      defaultValue: 4,
      subTitle: 'Configures memory for the Wasm runtime. Memory is described in units of pages (64KB) and represent contiguous chunks of addressable memory'
    }
  },
  functionName: {
    type: 'string',
    label: 'Name of the exported function to invoke'
  },
  config: {
    label: 'Static configuration',
    type: 'object'
  },
  allowedHosts: {
    label: 'Allowed hosts',
    type: 'array',
    array: true,
    format: null
  },
  wasi: {
    type: 'box-bool',
    props: {
      description: 'Plugin is compiled targeting WASI (checked if you use Go or JS/TS as plugin language).',
    },
  }
};

export default {
  config_schema: {
    ...schema
  },
  config_flow: {
    otoroshi_full_flow: [
      'compiler_source',
      'raw_source',
      'functionName',
      'wasi',
      'memoryPages',
      'config',
      'allowedHosts'
    ],
    otoroshi_flow: [
      'compiler_source',
      'raw_source',
      'functionName',
      'wasi'
    ]
  }
};