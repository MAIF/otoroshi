export default {
  id: 'cp:otoroshi.next.plugins.ApikeyCalls',
  config_schema: {
    wipe_backend_request: {
      label: 'Wipe backend request',
      type: 'box-bool',
      props: {
        description: 'Remove the apikey from the call made to downstream service',
      },
    },
    routing: {
      label: 'Routing',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        none_meta_keys_in: {
          label: 'None meta keys in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should not have one of the following keys in metadata',
        },
        all_meta_keys_in: {
          label: 'All meta keys in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have all of the following keys in metadata',
        },
        all_meta_in: {
          label: 'All meta in',
          type: 'object',
          help: 'Api used should have all of the following metadata entries',
        },
        none_meta_in: {
          label: 'None meta in',
          type: 'object',
          help: 'Api used should not have one of the following metadata entries',
        },
        one_tag_in: {
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
        one_meta_in: {
          label: 'One meta in',
          type: 'object',
          help: 'Api used should have at least one of the following metadata entries',
        },
        all_tags_in: {
          label: 'All tags in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have all of the following tags',
        },
        one_meta_key_in: {
          label: 'One meta key in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should have at least one of the following key in metadata',
        },
        none_tag_in: {
          label: 'None tag in',
          type: 'array',
          array: true,
          format: null,
          help: 'Api used should not have one of the following tags',
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
      label: 'Update quotas',
      type: 'box-bool',
      props: {
        description: 'Each call with an apikey will update its quota',
      },
    },
    extractors: {
      label: 'Extractors',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        jwt: {
          label: 'JWT',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            include_request_attrs: {
              label: 'Include request attrs',
              type: 'box-bool',
              props: {
                description:
                  'If enabled, you have to put the following fields in the JWT token corresponding to the current http call (httpPath, httpVerb, httpHost)',
              },
            },
            query_name: {
              label: 'Query name',
              type: 'string',
              help: 'The name of the query param to get the jwt token',
            },
            header_name: {
              label: 'Header name',
              type: 'string',
              help: 'The name of the header to get the jwt token',
            },
            path_name: {
              label: 'Path name',
              type: 'string',
              help: 'The name of the path parameter to get the jwt token',
            },
            key_pair_signed: {
              label: 'Keypair signed',
              type: 'box-bool',
              props: {
                description: 'JWT can be signed by an otoroshi managed keypair using RSA/EC algo.',
              },
            },
            secret_signed: {
              label: 'Secret signed',
              type: 'box-bool',
              props: {
                description: 'JWT can be signed by apikey secret using HMAC algo.',
              },
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              props: {
                description:
                  "You can pass the api key using a JWT token (ie. from 'Authorization: Bearer xxx' header)",
              },
            },
            cookie_name: {
              label: 'Cookie name',
              type: 'string',
              help: 'The name of the cookie to get the jwt token',
            },
          },
          flow: [
            'enabled',
            'query_name',
            'header_name',
            'cookie_name',
            'path_name',
            'include_request_attrs',
            'secret_signed',
            'key_pair_signed',
          ],
        },
        oto_bearer: {
          label: 'Otoroshi Bearer',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            query_name: {
              label: 'Query name',
              type: 'string',
              help: 'The name of the query param to get the bearer token',
            },
            header_name: {
              label: 'Header name',
              type: 'string',
              help: 'The name of the header to get the bearer token',
            },
            path_name: {
              label: 'Path name',
              type: 'string',
              help: 'The name of the path parameter to get the bearer token',
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              props: {
                description:
                  "You can pass the api key using a bearer token (ie. from 'Authorization: Bearer xxx' header)",
              },
            },
            cookie_name: {
              label: 'Cookie name',
              type: 'string',
              help: 'The name of the cookie to get the bearer token',
            },
          },
          flow: ['enabled', 'query_name', 'header_name', 'cookie_name', 'path_name'],
        },
        basic: {
          label: 'basic',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            path_name: {
              label: 'Path name',
              type: 'string',
              help: 'The name of the path parameter to get the basic auth. value',
            },
            cookie_name: {
              label: 'Cookie name',
              type: 'string',
              help: 'The name of the cookie to get the basic auth. value',
            },
            query_name: {
              label: 'Query name',
              type: 'string',
              help: 'The name of the query param to get Authorization',
            },
            header_name: {
              label: 'Header name',
              type: 'string',
              help: 'The name of the header to get Authorization',
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              props: {
                description:
                  "You can pass the api key in Authorization header (ie. from 'Authorization: Basic xxx' header)",
              },
            },
          },
          flow: ['enabled', 'query_name', 'header_name', 'cookie_name', 'path_name'],
        },
        client_id: {
          label: 'Client id',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            path_name: {
              label: 'Path name',
              type: 'string',
              help: 'The name of the path parameter to get the basic auth. value',
            },
            cookie_name: {
              label: 'Cookie name',
              type: 'string',
              help: 'The name of the cookie to get the basic auth. value',
            },
            query_name: {
              label: 'Query name',
              type: 'string',
              help: 'The name of the query param to get the client id',
            },
            header_name: {
              label: 'Header name',
              type: 'string',
              help: 'The name of the header to get the client id',
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              props: {
                description:
                  'You can pass the api key using client id only (ie. from Otoroshi-Token header)',
              },
            },
          },
          flow: ['enabled', 'query_name', 'header_name', 'cookie_name', 'path_name'],
        },
        custom_headers: {
          label: 'Custom headers',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            client_secret_header_name: {
              label: 'Client secret header name',
              type: 'string',
              help: 'The name of the header to get the client secret',
            },
            client_id_header_name: {
              label: 'Client id header name',
              type: 'string',
              help: 'The name of the header to get the client id',
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              props: {
                description:
                  'You can pass the api key using custom headers (ie. Otoroshi-Client-Id and Otoroshi-Client-Secret headers)',
              },
            },
          },
          flow: ['enabled', 'client_id_header_name', 'client_secret_header_name'],
        },
      },
      flow: ['jwt', 'basic', 'client_id', 'custom_headers', 'oto_bearer'],
    },
    pass_with_user: {
      label: 'Pass with user',
      type: 'box-bool',
      props: {
        description: 'Allow the path to be accessed via an Authentication module',
      },
    },
    mandatory: {
      label: 'Mandatory',
      type: 'box-bool',
      props: {
        description:
          'Allow an apikey and and authentication module to be used on a same path. If disabled, the route can be called without apikey.',
      },
    },
    validate: {
      label: 'Validate',
      type: 'box-bool',
      props: {
        description:
          'Check that the api key has not expired, has not reached its quota limits and is authorized to call the Otoroshi service',
      },
    },
  },
  config_flow: [
    'validate',
    'mandatory',
    'pass_with_user',
    'update_quotas',
    'wipe_backend_request',
    'routing',
    'extractors',
  ],
};
