export default {
  id: 'cp:otoroshi.next.plugins.WasmRequestTransformer',
  config_schema: {
    compiler_source: {
      type: 'select',
      label: 'Compiler source',
      props: {
        optionsFrom: '/bo/api/plugins/wasm',
        optionsTransformer: {
          label: 'filename',
          value: 'pluginId',
        },
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
      label: 'The name of the exported function to invoke'
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
    }
  },
  config_flow: [
    'compiler_source',
    'raw_source',
    'memoryPages',  
    'functionName',
    'config',
    'allowedHosts'
  ]
};
