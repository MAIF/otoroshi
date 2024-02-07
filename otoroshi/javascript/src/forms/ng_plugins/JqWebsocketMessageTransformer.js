export default {
  id: 'cp:otoroshi.next.plugins.JqWebsocketMessageTransformer',
  config_schema: {
    request_filter: {
      label: 'Request JQ filter',
      type: 'string',
    },
    response_filter: {
      label: 'Response JQ filter',
      type: 'string',
    },
  },
  config_flow: ['request_filter', 'response_filter'],
};
