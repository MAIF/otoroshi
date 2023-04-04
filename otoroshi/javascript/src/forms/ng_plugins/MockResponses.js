import React from 'react';
import MocksDesigner from '../../pages/RouteDesigner/MocksDesigner';

export default {
  id: 'cp:otoroshi.next.plugins.MockResponses',
  config_schema: ({ showAdvancedDesignerView }) => ({
    turn_view: {
      renderer: () => (
        <button
          type="button"
          className="btn btn-sm btn-primary mb-3"
          onClick={() => showAdvancedDesignerView(MocksDesigner)}>
          Edit with the Mocks Designer
        </button>
      ),
    },
    form_data: {
      label: 'form_data',
      type: 'form',
      collapsable: true,
      collapsed: true,
      visible: false,
      schema: {
        endpoints: {
          label: 'endpoints',
          type: 'array',
          array: true,
          format: 'form',
          schema: {
            resource_list: {
              label: 'resource_list',
              type: 'bool',
            },
            path: {
              label: 'path',
              type: 'string',
            },
            headers: {
              label: 'headers',
              type: 'string',
            },
            method: {
              label: 'method',
              type: 'string',
            },
            resource: {
              label: 'resource',
              type: 'string',
            },
            body: {
              type: 'code',
              props: {
                label: 'body',
                editorOnly: true,
                mode: 'json',
              },
            },
            status: {
              label: 'status',
              type: 'number',
            },
          },
          flow: ['resource_list', 'path', 'headers', 'method', 'resource', 'body', 'status'],
        },
        resources: {
          label: 'resources',
          type: 'array',
          array: true,
          format: 'form',
          schema: {
            schema: {
              label: 'schema',
              type: 'array',
              array: true,
              format: 'form',
              schema: {
                field_name: {
                  label: 'field_name',
                  type: 'string',
                },
                field_type: {
                  label: 'field_type',
                  type: 'string',
                },
                value: {
                  label: 'value',
                  type: 'object',
                },
              },
              flow: ['field_name', 'field_type', 'value'],
            },
            additional_data: {
              label: 'additional_data',
              type: 'string',
            },
            name: {
              label: 'name',
              type: 'string',
            },
          },
          flow: ['schema', 'additional_data', 'name'],
        },
      },
      flow: ['endpoints', 'resources'],
    },
    pass_through: {
      label: 'pass_through',
      type: 'bool',
    },
    responses: {
      label: 'responses',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        path: {
          label: 'path',
          type: 'string',
        },
        body: {
          type: 'code',
          props: {
            label: 'body',
            editorOnly: true,
            mode: 'json',
          },
        },
        status: {
          label: 'status',
          type: 'number',
        },
        method: {
          label: 'method',
          type: 'string',
        },
        headers: {
          label: 'headers',
          type: 'object',
        },
      },
      flow: ['path', 'body', 'status', 'method', 'headers'],
    },
  }),
  config_flow: [
    'turn_view',
    // "responses",
    // "pass_through",
    // "form_data"
  ],
};
