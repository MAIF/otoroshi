export default {
  id: 'cp:otoroshi.next.tunnel.TunnelPlugin',
  config_schema: {
    tunnel_id: {
      type: 'select',
      label: 'Tunnel ID',
      props: {
        optionsFrom: '/bo/api/proxy/api/tunnels',
        optionsTransformer: {
          label: 'name',
          value: 'tunnel_id',
        },
      },
    },
  },
  config_flow: ['tunnel_id'],
};
