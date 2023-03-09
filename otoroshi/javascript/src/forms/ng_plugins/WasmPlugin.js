import React from 'react';
import { useEffect } from 'react';
import { useState } from 'react';
import { LabelAndInput, NgSelectRenderer } from '../../components/nginputs';
import { WasmSourcePath } from '../../pages/WasmPluginsPage'; 

// don't import this file in the index.js
// it served as template for the other Wasm plugin
const schema = {
  compiler_source: {
    // label: 'Compiler source',
    renderer: (props) => {
      const [plugins, setPlugins] = useState([]);

      useEffect(() => {
        fetch('/bo/api/plugins/wasm', {
          headers: {
            credentials: 'include',
            headers: {
              Accept: 'application/json',
              'Content-Type': 'application/json',
            },
          },
        })
          .then((r) => r.json())
          .then((plugins) => {
            setPlugins(
              plugins
                .map((plugin) => plugin.versions || [])
                .flat()
                .map((plugin) => {
                  const parts = plugin.split('.wasm');
                  return {
                    filename: parts[0],
                    wasm: plugin,
                  };
                })
            );
          });
      }, []);

      return (
        <LabelAndInput label="Compiler source">
          <NgSelectRenderer
            value={props.value}
            placeholder="Select a plugin"
            label={' '}
            ngOptions={{
              spread: true,
            }}
            onChange={props.onChange}
            margin={0}
            style={{ flex: 1 }}
            options={plugins}
            optionsTransformer={(arr) =>
              arr.map((item) => ({ label: item.filename, value: item.wasm }))
            }
          />
        </LabelAndInput>
      );
    },
  },
  raw_source: {
    type: 'string',
    label: 'Raw source',
  },
  source: {
    // label: 'Source',
    // type: 'form',
    // collapsable: false,
    // collapsed: false,
    // flow: ['kind', "path"],
    //schema: {
      kind: {
        label: "Kind",
        type: 'select',
        props: {
          label: 'Kind',
          options: ['Base64', 'Http', 'WasmManager', 'Local', 'File'].map(v => ({ label: v, value: v.toLowerCase() })),
        },
      },
      path: {
        renderer: (props) => <WasmSourcePath {...props} />
      }
    //}
  },
  memoryPages: {
    type: 'number',
    label: 'Max number of pages',
    props: {
      defaultValue: 4,
      subTitle:
        'Configures memory for the Wasm runtime. Memory is described in units of pages (64KB) and represent contiguous chunks of addressable memory',
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
  wasi: {
    type: 'box-bool',
    label: 'Add WASI imports',
    props: {
      description:
        'Plugin is compiled targeting WASI (checked if you use Go or JS/TS as plugin language).',
    },
  },
  preserve: {
    type: 'box-bool',
    label: 'Preserve VMs',
    props: {
      description:
        'Doesnt stop the wasm vm during a request',
    },
  },
  accesses: {
    label: 'Host functions',
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
          description:
            'Add function to call http services',
        },
      },
      globalDataStoreAccess: {
        read: {
          label: "Can read global datastore",
          type: 'box-bool',
          props: {
            description:
              'Add function to read the global datastore',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write global datastore',
          props: {
            description:
              'Add function to read and write the global datastore',
          },
        }
      },
      pluginDataStoreAccess: {
        read: {
          label: "Can read plugin datastore",
          type: 'box-bool',
          props: {
            description:
              'Add function to read the plugin datastore',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Can write plugin datastore',
          props: {
            description:
              'Add function to read and write the plugin datastore',
          },
        }
      },
      globalMapAccess: {
        read: {
          label: "Read plugin global map",
          type: 'box-bool',
          props: {
            description:
              'Add functions to read a map to store stuff in current otoroshi instance memory between invocations',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Write plugin global map',
          props: {
            description:
              'Add functions to write a map to store stuff in current otoroshi instance memory between invocations',
          },
        }
      },
      pluginMapAccess: {
        read: {
          label: "Read plugin map",
          type: 'box-bool',
          props: {
            description:
              'Add functions to read a map to store stuff in current otoroshi instance memory between invocations. Each plugim has its own map.',
          },
        },
        write: {
          type: 'box-bool',
          label: 'Write plugin map',
          props: {
            description:
              'Add functions to write a map to store stuff in current otoroshi instance memory between invocations. Each plugim has its own map.',
          },
        }
      },
      proxyStateAccess: {
        type: 'box-bool',
        label: 'Proxy state access',
        props: {
          description:
            'Add function to access proxy state',
        },
      },
      configurationAccess: {
        type: 'box-bool',
        label: 'Configuration access',
        props: {
          description:
            'Add function to access some useful configuration about otoroshi instance',
        },
      }
    }
  }
};

export default {
  config_schema: {
    ...schema,
  },
  config_flow: [
    'source.kind',
    'source.path',
    'functionName',
    'wasi',
    'preserve',
    'accesses',
    {
      type: 'group',
      name: 'Advanced settings',
      fields: [
        'memoryPages',
        'config',
        'allowedHosts',
      ]
    }
  ]
};
