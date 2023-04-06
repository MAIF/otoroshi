export default {
  id: 'cp:otoroshi.next.plugins.ApikeyAuthModule',
  config_schema: {
    realm: {
      label: 'Realm',
      type: 'string'
    },
    matcher: {
      label: 'Apikey Matcher',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        noneMetaKeysIn: {
          label: 'None meta. keys in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should not have one of the following keys in metadata',
        },
        allMetaKeysIn: {
          label: 'All meta. keys in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have all of the following keys in metadata',
        },
        allMetaIn: {
          label: 'All meta. in',
          type: 'object',
          help: 'Api used should have all of the following metadata entries',
        },
        noneMetaIn: {
          label: 'None meta. in',
          type: 'object',
          help: 'Api used should not have one of the following metadata entries',
        },
        oneTagIn: {
          label: 'One tag in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have at least one of the following tags',
        },
        enabled: {
          label: 'Enabled',
          type: 'bool',
        },
        oneMetaIn: {
          label: 'One meta. in',
          type: 'object',
          help: 'Api used should have at least one of the following metadata entries',
        },
        allTagsIn: {
          label: 'All tags in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have all of the following tags',
        },
        oneMetaKeyIn: {
          label: 'One meta. key in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have at least one of the following key in metadata',
        },
        noneTagIn: {
          label: 'None tag in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should not have one of the following tags',
        },
      },
      flow: [
        'enabled',
        'noneMetaKeysIn',
        'allMetaKeysIn',
        'allMetaIn',
        'noneMetaIn',
        'oneTagIn',
        'oneMetaIn',
        'allTagsIn',
        'oneMetaKeyIn',
        'noneTagIn',
      ],
    }
  },
  config_flow: ['realm', 'matcher']
};
