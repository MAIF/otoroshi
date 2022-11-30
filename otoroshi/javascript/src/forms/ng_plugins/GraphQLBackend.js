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
    permissions: {
      type: 'string',
      array: true,
      label: 'Permissions'
    },
    maxDepth: {
      type: 'number',
      label: 'Max depth',
      props: {
        subTitle: 'By analyzing the query document’s abstract syntax tree (AST), Otoroshi is able to reject or accept a request based on its depth.'
      }
    }
  }),
  config_flow: [
    'designer',
    'permissions',
    {
      type: 'group',
      name: 'Advanced settings',
      fields: ['maxDepth']
    }
  ]
};
