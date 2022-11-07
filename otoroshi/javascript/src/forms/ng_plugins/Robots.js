export default {
  id: 'cp:otoroshi.next.plugins.Robots',
  config_schema: {
    meta_content: {
      label: 'meta_content',
      type: 'string',
    },
    meta_enabled: {
      label: 'meta_enabled',
      type: 'bool',
    },
    robot_enabled: {
      label: 'robot_enabled',
      type: 'bool',
    },
    robot_txt_content: {
      label: 'robot_txt_content',
      type: 'string',
    },
    header_enabled: {
      label: 'header_enabled',
      type: 'bool',
    },
    header_content: {
      label: 'header_content',
      type: 'string',
    },
  },
  config_flow: [
    'meta_enabled',
    'header_enabled',
    'header_content',
    'robot_enabled',
    'meta_content',
    'robot_txt_content',
  ],
};
