export default {
  id: 'cp:otoroshi.next.plugins.OAuth2Caller',
  config_flow: [
    "kind",
    "url",
    "method",
    "headerName",
    "headerValueFormat",
    "jsonPayload",
    "clientId",
    "clientSecret",
    "scope",
    "audience",
    "user",
    "password",
    "cacheTokenSeconds",
    "tlsConfig"
  ],
  config_schema: {
    kind: {
      label: "Kind",
      type: "select",
      props: {
        defaultValue: "HmacSHA512",
        options: [
          { value: "client_credentials", label: "Client credentials" },
          { value: "password", label: "Password" }
        ]
      }
    },
    url: {
      type: 'string',
      label: "URL"
    },
    method: {
      type: 'string',
      label: "Method"
    },
    headerName: {
      type: 'string',
      label: "Header name"
    },
    headerValueFormat: {
      type: 'string',
      label: "Header value formatter"
    },
    jsonPayload: {
      type: 'bool',
      label: "JSON payload"
    },
    clientId: {
      type: 'string',
      label: "Client Id"
    },
    clientSecret: {
      type: 'string',
      label: "Client secret"
    },
    scope: {
      type: 'string',
      label: "Scope"
    },
    audience: {
      type: 'string',
      label: "Audience"
    },
    user: {
      type: 'string',
      label: "User"
    },
    password: {
      type: 'string',
      label: "Password"
    },
    cacheTokenSeconds: {
      type: 'string',
      label: "Cache Token Seconds"
    },
    tlsConfig: {
      label: 'Tls Config',
      type: 'form',
      flow: ['certs', 'trustedCerts', 'mtls', 'loose', 'trustAll'],
      schema: {
        certs: {
          type: 'array-select',
          label: 'Certificates',
          help: 'The certificate used when performing a mTLS call',
          props: {
            label: 'Certificates',
            optionsFrom: '/bo/api/proxy/api/certificates',
            optionsTransformer: {
              label: 'name',
              value: 'id',
            },
          },
        },
        trustedCerts: {
          type: 'array-select',
          label: 'Trusted Certificates',
          help: 'The trusted certificate used when performing a mTLS call',
          props: {
            label: 'Trusted certificates',
            optionsFrom: '/bo/api/proxy/api/certificates',
            optionsTransformer: {
              label: 'name',
              value: 'id',
            },
          },
        },
        mtls: {
          type: 'boolean',
          label: 'Enable MTls'
        },
        loose: {
          type: 'boolean',
          label: 'Loose'
        },
        trustAll: {
          type: 'boolean',
          label: 'Trust all'
        },
      },
    }
  }
};