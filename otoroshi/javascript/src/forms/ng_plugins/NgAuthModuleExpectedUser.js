import { findAuthConfigById } from '../../services/BackOfficeServices';
import { SelectorWizardLauncher } from '../wizards/SelectorWizardLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.NgAuthModuleExpectedUser',
  config_schema: {
    only_from: {
      label: 'Only from',
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
  },
  config_flow: ['only_from'],
};
