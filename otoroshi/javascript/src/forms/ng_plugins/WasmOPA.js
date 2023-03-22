import React from 'react';
import { WasmSourcePath } from '../../pages/WasmPluginsPage'; 

export default {
  id: 'cp:otoroshi.next.plugins.WasmOPA',
  config_schema: {
    source: {
      label: 'Source',
      type: 'form',
      collapsable: false,
      collapsed: false,
      flow: (v) => [
        'kind',
        "path",
        v.kind.toLowerCase() === 'http' && 'opts.headers',
        v.kind.toLowerCase() === 'http' && 'opts.timeout',
        v.kind.toLowerCase() === 'http' && 'opts.method',
        v.kind.toLowerCase() === 'http' && 'opts.followRedirect',
      ].filter(v => !!v),
      schema: {
        kind: {
          label: "Kind",
          type: 'select',
          props: {
            label: 'Kind',
            options: ['Base64', 'Http', 'WasmManager', 'Local', 'File'].map(v => ({ label: v, value: v.toLowerCase() })),
          },
        },
        opts: {
          headers: { type: 'object', label: 'Headers' },
          timeout: { type: 'number', label: 'Timeout', props: { suffix: 'millis.' } },
          method: { type: 'string', label: 'Method' },
          followRedirect: { type: 'bool', label: 'Follow redirects' },
        },
        path: {
          renderer: (props) => <WasmSourcePath {...props} />
        }
      }
    },
    memoryPages: {
      type: 'number',
      label: 'Max number of pages',
      props: {
        defaultValue: 4,
        subTitle:
          'Configures memory for the Wasm runtime. Memory is described in units of pages (64KB) and represent contiguous chunks of addressable memory',
      },
    }
  },
  config_flow: (v) => [
    'source',
    v.source.kind.toLowerCase() !== 'local' && {
      type: 'group',
      name: 'Advanced settings',
      fields: [
        'memoryPages',
      ]
    }
  ].filter(v => !!v),
};
