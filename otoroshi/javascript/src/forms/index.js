import { Plugins } from './ng_plugins';
import { Wizards } from './wizards';

const ClassifiedForms = {
  plugins: Plugins('ClassifiedForms').reduce((acc, c) => {
    return {
      ...acc,
      [c.id.split('.').slice(-1)]: c,
    };
  }, {}),
  wizards: Wizards,
};

const Forms = {
  ...ClassifiedForms.plugins,
  ...ClassifiedForms.wizards,
};

export { ClassifiedForms, Forms };
