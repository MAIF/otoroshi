export default {
  id: 'cp:otoroshi.next.plugins.NgClientCertChainHeader',
  config_schema: {
    send_pem: {
      type: "bool",
      label: "Send client cert. as PEM",
    },
    pem_header_name: {
      type: "string",
      label: "Cert. PEM header name",
    },
    send_dns: {
      type: "bool",
      label: "Send client cert. DNs",
    },
    dns_header_name: {
      type: "string",
      label: "Cert. DNs header name",
    },
    send_chain: {
      type: "bool",
      label: "Send client cert. chain",
    },
    chain_header_name: {
      type: "string",
      label: "Cert. chain header name",
    },
  },
  config_flow: [
    "send_pem",
    "pem_header_name",
    "send_dns",
    "dns_header_name",
    "send_chain",
    "chain_header_name",
  ],
};
