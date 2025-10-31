import { JwtVerifierLauncher } from '../wizards/JwtVerifierLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.NgJwtUserExtractor',
  config_schema: {
    verifier: {
      label: 'JWT verifier id',
      type: 'JwtVerifierWizard',
      props: {
        componentLauncher: JwtVerifierLauncher,
        componentsProps: {
          allowedNewStrategy: 'Generate',
        },
      },
    },
    strict: {
      type: 'bool',
      label: 'Strict validation',
    },
    strip: {
      type: 'bool',
      label: 'Stripped token',
    },
    name_path: {
      type: 'string',
      label: 'Path to extract username',
    },
    email_path: {
      type: 'string',
      label: 'Path to extract user email',
    },
    meta_path: {
      type: 'string',
      label: 'Path to extract user metadata',
    },
  },
  config_flow: ['verifier', 'strict', 'strip', 'name_path', 'email_path', 'meta_path'],
};
