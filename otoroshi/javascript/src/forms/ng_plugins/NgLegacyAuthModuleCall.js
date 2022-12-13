import { findAuthConfigById } from '../../services/BackOfficeServices';
import { SelectorWizardLauncher } from '../wizards/SelectorWizardLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.NgLegacyAuthModuleCall',
  config_schema: {
    module: {
      label: 'Legacy Authentication',
      type: 'AuthenticationWizard',
      props: {
        componentLauncher: SelectorWizardLauncher,
        componentsProps: {
          entityName: 'Authentication configuration',
          entityField: 'authentication',
          findById: findAuthConfigById,
        },
      },
    },
    public_patterns: {
      label: 'Public patterns',
      type: 'array',
      array: true,
      format: null,
    },
    private_patterns: {
      label: 'Private patterns',
      type: 'array',
      array: true,
      format: null,
    },
    pass_with_apikey: {
      type: 'box-bool',
      label: 'Pass with apikey',
      props: {
        description: 'Authentication config can only be called with an API key',
      },
    },
  },
  config_flow: ['public_patterns', 'private_patterns', 'module', 'pass_with_apikey'],
};
