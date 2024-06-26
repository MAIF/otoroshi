export default {
  id: 'cp:otoroshi.next.plugins.JweExtractor',
  config_schema: {
    certId: {
      type: 'select',
      label: "KeyPair",
      props: {
        optionsFrom: "/bo/api/proxy/api/certificates?keypair=true",
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      }
    }
  },
  config_flow: ['certId'],
};
