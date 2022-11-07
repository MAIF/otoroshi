export default {
  id: 'cp:otoroshi.next.tunnel.TunnelPlugin',
  config_schema: {
    tunnel_id: {
      type: 'select',
      props: {
        label: 'Tunnel ID',
        optionsFrom: '/bo/api/proxy/api/tunnels',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      },
    },
  },
  config_flow: ['tunnel_id'],
};
