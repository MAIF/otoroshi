export default {
  id: 'cp:otoroshi.next.plugins.WasmAccessValidator',
  config_schema: {
    source: {
      type: 'string',
      label: 'Source',
      props: {
        subTitle: `http://xxx.xxx or https://xxx.xxx or file://path or base64://encodedstring`
      }
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
    'source',
    'memoryPages',  
    'functionName',
    'config',
    'allowedHosts'
  ]
};
