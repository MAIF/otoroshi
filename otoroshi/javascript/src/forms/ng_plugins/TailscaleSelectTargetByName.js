export default {
  id: 'cp:otoroshi.next.plugins.TailscaleSelectTargetByName',
  config_schema: {
    machine_name: {
      type: 'string',
      label: 'Machine name',
      placeholder: 'The name of the machine in your tailnet',
    },
    use_ip_address: {
      type: 'bool',
      label: 'Use IP address to avoid DNS resolution',
    },
  },
  config_flow: ['machine_name', 'use_ip_address'],
};
