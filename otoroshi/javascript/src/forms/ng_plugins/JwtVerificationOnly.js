import { JwtVerifierLauncher } from '../wizards/JwtVerifierLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.JwtVerificationOnly',
  config_schema: {
    custom_response: {
      type: 'bool',
      label: 'Custom error',
    },
    custom_response_status: {
      type: 'number',
      label: 'Custom error status',
    },
    custom_response_headers: {
      type: 'object',
      label: 'Custom error headers',
    },
    custom_response_body: {
      type: 'code',
      label: 'Custom error body',
    },
    verifier: {
      label: 'Verifier',
      type: 'JwtVerifierWizard',
      props: {
        componentLauncher: JwtVerifierLauncher,
        componentsProps: {
          // allowedStrategy: 'PassThrough',
          // allowedNewStrategy: 'PassThrough',
        },
      },
    },
    fail_if_absent: {
      type: 'box-bool',
      label: 'Fail if absent',
      props: {
        description: 'If a token is absent in the incoming request, the call will fail.',
      },
    },
  },
  config_flow: [
    'verifier',
    'fail_if_absent',
    'custom_response',
    'custom_response_status',
    'custom_response_headers',
    'custom_response_body',
  ],
};
