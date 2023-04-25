// import { findAuthConfigById } from '../../services/BackOfficeServices';
// import { SelectorWizardLauncher } from '../wizards/SelectorWizardLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.MultiAuthModule',
  config_schema: {
    auth_modules: {
      type: 'string',
      array: true
    },
    pass_with_apikey: {
      type: 'box-bool',
      label: 'Pass with apikey',
      props: {
        description: 'Authentication config can only be called with an API key',
      },
    },
  },
  config_flow: ['auth_modules', 'pass_with_apikey'],
};
