export default {
  id: 'cp:otoroshi.next.plugins.NgHasClientCertMatchingValidator',
  config_schema: {
    serial_numbers: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed serial numbers',
    },
    subject_dns: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed subject DNs',
    },
    issuer_dns: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed issuer DNs',
    },
    regex_subject_dns: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed subject DNs (regex)',
    },
    regex_issuer_dns: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed issuer DNs (regex)',
    },
  },
  config_flow: ['serial_numbers', 'subject_dns', 'issuer_dns', 'regex_subject_dns', 'regex_issuer_dns'],
};
