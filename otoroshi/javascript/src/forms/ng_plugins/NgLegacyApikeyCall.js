export default {
  id: 'cp:otoroshi.next.plugins.NgLegacyApikeyCall',
  config_schema: {
    public_patterns: {
      label: 'Public patterns',
      type: 'array',
      array: true,
      format: null,
    },
    private_patterns: {
      label: 'Private patterns',
      type: 'array',
      array: true,
      format: null,
    },
    wipe_backend_request: {
      label: 'wipe_backend_request',
      type: 'bool',
    },
    routing: {
      label: 'routing',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        none_meta_keys_in: {
          label: 'none_meta_keys_in',
          type: 'array',
          array: true,
          format: null,
        },
        all_meta_keys_in: {
          label: 'all_meta_keys_in',
          type: 'array',
          array: true,
          format: null,
        },
        all_meta_in: {
          label: 'all_meta_in',
          type: 'object',
        },
        none_meta_in: {
          label: 'none_meta_in',
          type: 'object',
        },
        one_tag_in: {
          label: 'one_tag_in',
          type: 'array',
          array: true,
          format: null,
        },
        enabled: {
          label: 'enabled',
          type: 'bool',
        },
        one_meta_in: {
          label: 'one_meta_in',
          type: 'object',
        },
        all_tags_in: {
          label: 'all_tags_in',
          type: 'array',
          array: true,
          format: null,
        },
        one_meta_key_in: {
          label: 'one_meta_key_in',
          type: 'array',
          array: true,
          format: null,
        },
        none_tag_in: {
          label: 'none_tag_in',
          type: 'array',
          array: true,
          format: null,
        },
      },
      flow: [
        'enabled',
        'none_meta_keys_in',
        'all_meta_keys_in',
        'all_meta_in',
        'none_meta_in',
        'one_tag_in',
        'one_meta_in',
        'all_tags_in',
        'one_meta_key_in',
        'none_tag_in',
      ],
    },
    update_quotas: {
      label: 'update_quotas',
      type: 'bool',
    },
    extractors: {
      label: 'extractors',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        jwt: {
          label: 'jwt',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            include_request_attrs: {
              label: 'include_request_attrs',
              type: 'bool',
            },
            query_name: {
              label: 'query_name',
              type: 'string',
            },
            header_name: {
              label: 'header_name',
              type: 'string',
            },
            key_pair_signed: {
              label: 'key_pair_signed',
              type: 'bool',
            },
            secret_signed: {
              label: 'secret_signed',
              type: 'bool',
            },
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
            cookie_name: {
              label: 'cookie_name',
              type: 'string',
            },
          },
          flow: [
            'enabled',
            'include_request_attrs',
            'query_name',
            'header_name',
            'key_pair_signed',
            'secret_signed',
            'cookie_name',
          ],
        },
        basic: {
          label: 'basic',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            query_name: {
              label: 'query_name',
              type: 'string',
            },
            header_name: {
              label: 'header_name',
              type: 'string',
            },
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
          },
          flow: ['enabled', 'query_name', 'header_name'],
        },
        client_id: {
          label: 'client_id',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            query_name: {
              label: 'query_name',
              type: 'string',
            },
            header_name: {
              label: 'header_name',
              type: 'string',
            },
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
          },
          flow: ['enabled', 'query_name', 'header_name'],
        },
        custom_headers: {
          label: 'custom_headers',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            client_secret_header_name: {
              label: 'client_secret_header_name',
              type: 'string',
            },
            client_id_header_name: {
              label: 'client_id_header_name',
              type: 'string',
            },
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
          },
          flow: ['enabled', 'client_secret_header_name', 'client_id_header_name'],
        },
      },
      flow: ['jwt', 'basic', 'client_id', 'custom_headers'],
    },
    pass_with_user: {
      label: 'pass_with_user',
      type: 'bool',
    },
    mandatory: {
      label: 'mandatory',
      type: 'bool',
    },
    validate: {
      label: 'validate',
      type: 'bool',
    },
  },
  config_flow: [
    'public_patterns',
    'private_patterns',
    'validate',
    'mandatory',
    'pass_with_user',
    'update_quotas',
    'wipe_backend_request',
    'routing',
    'extractors',
  ],
};
