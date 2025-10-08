import { findAuthConfigById } from '../../services/BackOfficeServices';
import { SelectorWizardLauncher } from '../wizards/SelectorWizardLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.NgAuthModuleExpectedUser',
  config_schema: {
    only_from: {
      label: 'Only from',
      type: 'array-select',
      props: {
        optionsFrom: '/bo/api/proxy/api/auths',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      }
    },
  },
  config_flow: ['only_from'],
};
