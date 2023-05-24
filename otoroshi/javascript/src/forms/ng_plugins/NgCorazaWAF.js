export default {
  id: 'cp:otoroshi.wasm.proxywasm.NgCorazaWAF',
  config_schema: {
    ref: {
      type: 'select',
      label: 'Coraza WAF config.',
      props: {
        optionsFrom: '/bo/api/proxy/apis/coraza-waf.extensions.otoroshi.io/v1/coraza-configs',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        }
      }
    },
  },
  config_flow: ["ref"],
};
