import React from 'react';
import GraphQLForm from "../GraphQLForm";

export default {
  "id": "cp:otoroshi.next.plugins.GraphQLBackend",
  "name": "GraphQL Composer",
  "description": "This plugin exposes a GraphQL API that you can compose with whatever you want",
  "default_config": {
    "schema": "\n   type User {\n     name: String!\n     firstname: String!\n   }\n   schema {\n    query: Query\n   }\n\n   type Query {\n    users: [User] @json(data: \"[{ \\\"firstname\\\": \\\"Foo\\\", \\\"name\\\": \\\"Bar\\\" }, { \\\"firstname\\\": \\\"Bar\\\", \\\"name\\\": \\\"Foo\\\" }]\")\n   }\n  ",
    "permissions": [],
    "initial_data": null,
    "max_depth": 15
  },
  "config_schema": ({ showAdvancedDesignerView }) => ({
    "schema": {
      "type": "code",
      "props": {
        "editorOnly": true
      }
    },
    "maxDepth": {
      "label": "Max depth",
      "type": "number"
    },
    "initialData": {
      "label": "Predefined data",
      "type": "json"
    },
    permissions: {
      type: 'string',
      array: true,
      label: 'Permissions paths',
    },
    turn_view: {
      renderer: () => (
        <button
          type="button"
          className="btn btn-sm btn-info mb-3"
          onClick={() => showAdvancedDesignerView(GraphQLForm)}>
          Edit with the GraphQL Designer
        </button>
      ),
    }
  }),
  "config_flow": [
    'turn_view',
    'schema',
    'initialData',
    'maxDepth',
    'permissions'
  ],
}
