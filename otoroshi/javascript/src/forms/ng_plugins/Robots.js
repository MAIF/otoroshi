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
      label: 'robot_txt_enabled',
      type: 'bool',
    },
    robot_txt_content: {
      label: 'robot_txt_content',
      type: 'text',
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
    'meta_content',
    'header_enabled',
    'header_content',
    'robot_txt_enabled',
    'robot_txt_content',
  ],
};
