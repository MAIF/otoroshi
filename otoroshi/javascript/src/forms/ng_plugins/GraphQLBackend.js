import React from 'react';
import GraphQLForm from '../../pages/RouteDesigner/GraphQLForm';

export default {
  id: 'cp:otoroshi.next.plugins.GraphQLBackend',
  name: 'GraphQL Composer',
  description: 'This plugin exposes a GraphQL API that you can compose with whatever you want',
  config_schema: ({ showAdvancedDesignerView }) => ({
    designer: {
      renderer: () => (
        <button
          type="button"
          className="btn btn-sm btn-info mb-3"
          onClick={() => showAdvancedDesignerView(GraphQLForm)}>
          Edit with the GraphQL Designer
        </button>
      ),
    },
  }),
  config_flow: ['designer'],
};
