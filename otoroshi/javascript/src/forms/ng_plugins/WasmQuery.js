export default {
  id: 'cp:otoroshi.next.plugins.WasmQuery',
  config_schema: {
    source: {
      type: 'string',
      label: 'Source'
    },
    memoryPages: {
      type: 'number',
      defaultValue: 4,
      label: 'Max number of pages',
      props: {
        description: 'Configures memory for the Wasm runtime. Memory is described in units of pages (64KB) and represent contiguous chunks of addressable memory'
      }
    },
    functionName: {
      type: 'string',
      label: 'The name of the exported function to invoke'
    },
    config: {
      label: 'Static configuration',
      type: 'object',
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
