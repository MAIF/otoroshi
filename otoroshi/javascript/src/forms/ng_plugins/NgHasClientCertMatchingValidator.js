export default {
  id: 'cp:otoroshi.next.plugins.NgHasClientCertMatchingValidator',
  config_schema: {
    serialNumbers: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed serial numbers',
    },
    subjectDNs: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed subject DNs',
    },
    issuerDNs: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed issuer DNs',
    },
    regexSubjectDNs: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed subject DNs (regex)',
    },
    regexIssuerDNs: {
      type: 'array',
      array: true,
      format: null,
      label: 'Allowed issuer DNs (regex)',
    },
  },
  config_flow: ['serialNumbers', 'subjectDNs', 'issuerDNs', 'regexSubjectDNs', 'regexIssuerDNs'],
};
