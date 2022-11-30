import { findAuthConfigById } from "../../services/BackOfficeServices";
import { SelectorWizardLauncher } from "../wizards/SelectorWizardLauncher";

export default {
  id: 'cp:otoroshi.next.plugins.NgAuthModuleUserExtractor',
  config_schema: {
    module: {
      type: 'AuthenticationWizard',
      props: {
        componentLauncher: SelectorWizardLauncher,
        componentsProps: {
          entityName: 'Authentication configuration',
          entityField: 'authentication',
          findById: findAuthConfigById,
        }
      }
    }
  },
  config_flow: ['module'],
};
