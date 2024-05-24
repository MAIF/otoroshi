import React from 'react';
import { WasmSourcePath } from '../../pages/WasmPluginsPage';

// don't import this file in the index.js
// it served as template for the other Wasm plugin
const schema = {
  source: {
    label: 'Source',
    type: 'form',
    collapsable: false,
    collapsed: false,
    flow: (v) =>
      [
        'kind',
        'path',
        v.kind.toLowerCase() === 'http' && 'opts.headers',
        v.kind.toLowerCase() === 'http' && 'opts.timeout',
        v.kind.toLowerCase() === 'http' && 'opts.method',
        v.kind.toLowerCase() === 'http' && 'opts.followRedirect',
      ].filter((v) => !!v),
    schema: {
      kind: {
        label: 'Kind',
        type: 'select',
        props: {
          formatValue: (value) => {
            if (value === 'wasmmanager') return 'wasmo';
            return value;
          },
          label: 'Kind',
          options: ['Base64', 'Http', 'Wasmo', 'Local', 'File', 'ClassPath'].map((v) => ({
            label: v,
            value: v.toLowerCase(),
          })),
        },
      },
      opts: {
        headers: { type: 'object', label: 'Headers' },
        timeout: { type: 'number', label: 'Timeout', props: { suffix: 'millis.' } },
        method: { type: 'string', label: 'Method' },
        followRedirect: { type: 'bool', label: 'Follow redirects' },
      },
      path: {
        renderer: (props) => <WasmSourcePath {...props} />,
      },
    },
  },
  memoryPages: {
    type: 'number',
    label: 'Max number of pages',
    props: {
      defaultValue: 4,
      subTitle:
        'Configures memory for the Wasm runtime. Memory is described in units of pages (64Kb) and represent contiguous chunks of addressable memory',
    },
  },
  functionName: {
    type: 'string',
    label: 'Name of the exported function to invoke',
  },
  config: {
    label: 'Static configuration',
    type: 'object',
  },
  allowedHosts: {
    label: 'Allowed hosts',
    type: 'array',
    array: true,
    format: null,
  },
  allowedPaths: {
    label: 'Allowed paths',
    type: 'object',
  },
  wasi: {
    type: 'box-bool',
    label: 'Add WASI imports',
    props: {
      description:
        'Plugin is compiled targeting WASI (checked if you use Go or JS/TS as plugin language).',
    },
  },
  instances: {
    type: 'Number',
    label: 'Instances',
    props: {
      help: 'the number of VM instances.',
    },
  },
  lifetime: {
    type: 'select',
    label: 'VM lifetime',
    props: {
      label: 'VM lifetime',
      description: 'Doesnt stop the wasm vm during a request',
      help: 'Doesnt stop the wasm vm during a request',
      options: ['Invocation', 'Request', 'Forever'].map((v) => ({ label: v, value: v })),
    },
  },
  opa: {
    type: 'box-bool',
    label: 'OPA',
    props: {
      description: 'The WASM source is an OPA rego policy compiled to WASM',
    },
  },
  httpwasm: {
    type: 'box-bool',
    label: 'HTTP-WASM',
    props: {
      description: 'The WASM source is a HTTP WASM',
    },
  },
  authorizations: {
    label: 'Host functions authorizations',
    type: 'form',
    collapsable: true,
    collapsed: false,
    flow: [
      'httpAccess',
      'globalDataStoreAccess.read',
      'pluginDataStoreAccess.write',
      'globalMapAccess.read',
      'globalMapAccess.write',
      'pluginMapAccess.read',
      'pluginMapAccess.write',
      'proxyStateAccess',
      'configurationAccess',
    ],
    schema: {
      httpAccess: {
        type: 'box-bool',
        label: 'HTTP client',
        props: {
          description: 'Add function to call http services',
        },
      },
      globalDataStoreAccess: {
        read: {
          label: 'Can read from global persistent key/value storage',
          type: 'box-bool',
          props: {
            description: 'Add function to read the global datastore',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write to global persistent key/value storage',
          props: {
            description: 'Add function to read and write the global datastore',
          },
        },
      },
      pluginDataStoreAccess: {
        read: {
          label: 'Can read from plugin scoped persistent key/value storage',
          type: 'box-bool',
          props: {
            description: 'Add function to read the plugin datastore',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write to plugin scoped persistent key/value storage',
          props: {
            description: 'Add function to read and write the plugin datastore',
          },
        },
      },
      globalMapAccess: {
        read: {
          label: 'Can read from plugin global in-memory key/value storage',
          type: 'box-bool',
          props: {
            description:
              'Add functions to read a map to store stuff in current otoroshi instance memory between invocations',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write to plugin global in-memory key/value storage',
          props: {
            description:
              'Add functions to write a map to store stuff in current otoroshi instance memory between invocations',
          },
        },
      },
      pluginMapAccess: {
        read: {
          label: 'Can read from plugin scoped in-memory key/value storage',
          type: 'box-bool',
          props: {
            description:
              'Add functions to read a map to store stuff in current otoroshi instance memory between invocations. Each plugim has its own map.',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write to plugin scoped in-memory key/value storage',
          props: {
            description:
              'Add functions to write a map to store stuff in current otoroshi instance memory between invocations. Each plugim has its own map.',
          },
        },
      },
      proxyStateAccess: {
        type: 'box-bool',
        label: 'Proxy state access',
        props: {
          description: 'Add function to access proxy state',
        },
      },
      configurationAccess: {
        type: 'box-bool',
        label: 'Configuration access',
        props: {
          description: 'Add function to access some useful configuration about otoroshi instance',
        },
      },
    },
  },
  killOptions: {
    label: 'wasm vm kill options',
    type: 'form',
    collapsable: true,
    collapsed: false,
    flow: ['max_calls', 'max_memory_usage', 'max_avg_call_duration', 'max_unused_duration'],
    schema: {
      max_calls: {
        type: 'number',
        label: 'Max calls',
        suffix: 'calls',
        props: {
          help: 'The maximum number of calls before killing a wasm vm (the pool will reinstantiate a new one)',
        },
      },
      max_memory_usage: {
        type: 'number',
        label: 'Max memory usage',
        suffix: '%',
        props: {
          help: 'The maximum memory usage allowed before killing the wasm vm (the pool will reinstantiate a new one)',
        },
      },
      max_avg_call_duration: {
        type: 'number',
        label: 'Max unused duration',
        suffix: 'ms.',
        props: {
          help: 'The maximum time allowed for a vm call before killing the wasm vm (the pool will reinstantiate a new one)',
        },
      },
      max_unused_duration: {
        type: 'number',
        label: 'Max unused duration',
        suffix: 'ms.',
        props: {
          help: 'The maximum time otoroshi waits before killing a wasm vm that is not called anymore (the pool will reinstantiate a new one)',
        },
      },
    },
  },
};

export default {
  config_schema: {
    ...schema,
  },
  config_flow: (v) =>
    [
      'source',
      'functionName',
      v.source.kind.toLowerCase() !== 'local' && 'wasi',
      // v.source.kind.toLowerCase() !== 'local' && 'lifetime',
      v.source.kind.toLowerCase() !== 'local' && 'authorizations',
      v.source.kind.toLowerCase() !== 'local' && 'killOptions',
      'httpwasm',
      v.source.kind.toLowerCase() !== 'local' && {
        type: 'group',
        name: 'Advanced settings',
        fields: ['memoryPages', 'config', 'allowedHosts', 'allowedPaths'],
      },
    ].filter((v) => !!v),
};
