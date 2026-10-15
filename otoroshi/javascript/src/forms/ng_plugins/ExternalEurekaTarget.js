export default {
  id: 'cp:otoroshi.next.plugins.ExternalEurekaTarget',
  config_schema: {
    service_url_default_zone: {
      label: 'service_url_default_zone',
      type: 'string',
    },
    eureka_app: {
      label: 'eureka_app',
      type: 'string',
    },
  },
  config_flow: ['eureka_app', 'service_url_default_zone'],
};
