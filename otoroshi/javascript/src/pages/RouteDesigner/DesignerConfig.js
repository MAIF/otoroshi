export const PLUGIN_INFORMATIONS_SCHEMA = {
  enabled: {
    type: 'bool',
    label: 'Enabled',
  },
  debug: {
    type: 'bool',
    label: 'Debug',
  },
  include: {
    label: 'Include',
    type: 'string',
    array: true,
  },
  exclude: {
    label: 'Exclude',
    type: 'string',
    array: true,
  },
};

export const EXCLUDED_PLUGINS = {
  plugin_visibility: ['internal'],
  ids: ['otoroshi.next.proxy.ProxyEngine'],
};

export const LEGACY_PLUGINS_WRAPPER = {
  app: 'cp:otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  transformer: 'cp:otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  validator: 'cp:otoroshi.next.plugins.wrappers.AccessValidatorWrapper',
  preroute: 'cp:otoroshi.next.plugins.wrappers.PreRoutingWrapper',
  sink: 'cp:otoroshi.next.plugins.wrappers.RequestSinkWrapper',
  composite: 'cp:otoroshi.next.plugins.wrappers.CompositeWrapper',
  listener: '',
  job: '',
  exporter: '',
  'request-handler': '',
};
