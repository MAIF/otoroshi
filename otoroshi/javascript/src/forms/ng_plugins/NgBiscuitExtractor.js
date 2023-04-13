export default {
  id: 'cp:otoroshi.next.plugins.NgBiscuitExtractor',
  config_schema: {
    public_key: {
      type: 'string',
      label: 'Public key',
    },
    checks: {
      type: 'array',
      label: 'Checks',
      array: true,
      format: null,
    },
    facts: {
      type: 'array',
      label: 'Facts',
      array: true,
      format: null,
    },
    resources: {
      type: 'array',
      label: 'Resources',
      array: true,
      format: null,
    },
    rules: {
      type: 'array',
      label: 'Rules',
      array: true,
      format: null,
    },
    revocation_ids: {
      type: 'array',
      label: 'Revocation ids',
      array: true,
      format: null,
    },
    enforce: {
      type: 'bool',
      label: 'Enforce validation',
    },
    'extractor.type': {
      type: 'string',
      label: 'Extractor kind',
    },
    'extractor.name': {
      type: 'string',
      label: 'Extractor name',
    },
  },
  config_flow: [
    'public_key',
    'checks',
    'facts',
    'resources',
    'rules',
    'revocation_ids',
    'enforce',
    'extractor.type',
    'extractor.name',
  ],
};