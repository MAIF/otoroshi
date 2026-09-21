export default {
  id: 'cp:otoroshi.next.plugins.IpAddressBlockList',
  config_schema: {
    addresses: {
      label: 'addresses',
      type: 'array',
      array: true,
      format: null,
    },
    match_forwarded_chain: {
      label: 'Match the whole proxy chain',
      type: 'box-bool',
      props: {
        description:
          'Also block the request when a blocked address appears anywhere in the proxy chain of the client address header, not only when it is the resolved client address. The client writes part of that chain: this catches the intermediaries that disclose the address they forward, not a client hiding its own.',
      },
    },
  },
  config_flow: ['addresses', 'match_forwarded_chain'],
};
