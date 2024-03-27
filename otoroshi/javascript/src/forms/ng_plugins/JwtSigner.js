import { JwtVerifierLauncher } from '../wizards/JwtVerifierLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.JwtSigner',
  config_schema: {
    fail_if_present: {
      type: 'box-bool',
      label: 'Fail if present',
      props: {
        description:
          'If a token is present in the incoming request, the plugin will reject the call.',
      },
    },
    replace_if_present: {
      type: 'box-bool',
      label: 'Replace if present',
      props: {
        description:
          'If a token is present in the incoming request, the plugin will always replace the token with a new one.',
      },
    },
    verifier: {
      label: 'Verifier',
      type: 'JwtVerifierWizard',
      props: {
        componentLauncher: JwtVerifierLauncher,
        componentsProps: {
          allowedNewStrategy: 'Generate',
          allowedStrategy: 'DefaultToken'
        },
      },
    },
  },
  config_flow: ['verifier', 'replace_if_present', 'fail_if_present'],
};
