import React from 'react';

export default {
    id: 'Frontend',
    icon: 'user',
    plugin_steps: [],
    description: null,
    field: 'frontend',
    schema: {
        description: {
            renderer: () => <p>
                You can't edit the frontend here; it is just a visual box to show you the position of the frontend in the flow.
                However, you can create a route composed of plugins, a backend, and a frontend in your API's dashboard.
            </p>
        }
    },
    flow: ['description'],
};
