export default {
  id: "cp:otoroshi.next.plugins.IzanamiV2Proxy",
  config_schema: {
    tls: {
      label: "Custom TLS setup",
      type: "form",
      collapsable: true,
      collapsed: true,
      schema: Object.entries({
        enabled: {
          label: "Enabled",
          type: "box-bool",
          props: {
            description:
              "If enabled, Otoroshi will try to provide client certificate trusted by the target server, trust all servers, etc.",
          },
        },
        certs: {
          type: "array-select",
          help: "The certificate used when performing a mTLS call",
          props: {
            label: "Certificates",
            optionsFrom: "/bo/api/proxy/api/certificates",
            optionsTransformer: {
              label: "name",
              value: "id",
            },
          },
        },
        trusted_certs: {
          type: "array-select",
          help: "The trusted certificate used when performing a mTLS call",
          props: {
            label: "Trusted certificates",
            optionsFrom: "/bo/api/proxy/api/certificates",
            optionsTransformer: {
              label: "name",
              value: "id",
            },
          },
        },
        loose: {
          label: "Loose",
          type: "box-bool",
          props: {
            description:
              "If enabled, Otoroshi will accept any certificate and disable hostname verification",
          },
        },
        trust_all: {
          label: "Trust all",
          type: "box-bool",
          props: {
            description:
              "If enabled, Otoroshi will accept trust all certificates",
          },
        },
      }).reduce((obj, entry) => {
        if (entry[0] === "enabled")
          return {
            ...obj,
            [entry[0]]: entry[1],
          };
        else
          return {
            ...obj,
            [entry[0]]: {
              ...entry[1],
              visible: (value) => value?.enabled,
            },
          };
      }, {}),
      flow: ["enabled", "loose", "trust_all", "certs", "trusted_certs"],
    },
    url: {
      label: "Izanami URL",
      type: "string",
    },
    clientId: {
      label: "Izanami client ID",
      type: "string",
    },
    clientSecret: {
      label: "Izanami client secret",
      type: "string",
    },
    timeout: {
      label: "Call timeout",
      type: "number",
      props: {
        defaultValue: 5000,
      },
    },
    context: {
      label: "Izanami context (optional)",
      type: "string",
    },
  },
  config_flow: ["url", "clientId", "clientSecret", "context", "timeout", "tls"],
};
