export default {
  id: 'cp:otoroshi.next.plugins.NgDiscoverySelfRegistrationSink',
  config_schema: {
    hosts: {
      type: 'array',
      array: true,
      format: null,
      label: 'Hosts',
    },
    target_template: {
      type: 'object',
      lable: 'Target template',
    },
    registration_ttl: {
      type: 'number',
      label: 'Registration TTLS',
    },
  },
  config_flow: ['hosts', 'target_template', 'registration_ttl'],
};
