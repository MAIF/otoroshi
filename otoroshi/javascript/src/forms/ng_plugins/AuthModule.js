import { findAuthConfigById } from '../../services/BackOfficeServices';
import { SelectorWizardLauncher } from '../wizards/SelectorWizardLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.AuthModule',
  config_schema: {
    module: {
      label: 'Authentication module',
      type: 'AuthenticationWizard',
      props: {
        componentLauncher: SelectorWizardLauncher,
        componentLauncherProps: {
          entityName: 'Authentication configuration',
          entityField: 'authentication',
          findById: findAuthConfigById,
        },
      },
    },
    pass_with_apikey: {
      type: 'box-bool',
      label: 'Pass with apikey',
      props: {
        description: 'Authentication config can only be called with an API key',
      },
    },
  },
  config_flow: ['module', 'pass_with_apikey'],
};
