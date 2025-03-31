import React from 'react';

export default {
  id: 'Backend',
  icon: 'bullseye',
  group: 'Targets',
  field: 'backend',
  schema: {
    description: {
      renderer: () => (
        <p>
          You can't edit the backend here; it is just a visual box to show you the position of the
          backend in the flow. However, you can create a route composed of plugins, a backend, and a
          frontend in your API's dashboard.
        </p>
      ),
    },
  },
  flow: ['description'],
};
