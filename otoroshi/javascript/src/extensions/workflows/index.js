import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';

const extensionId = 'otoroshi.extensions.Workflows';

export function setupWorkflowsExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {

    class WorkflowTester extends Component {
      render() {
        return (
          <h1>Tester</h1>
        )
      }
    }

    class WorkflowsPage extends Component {
      formSchema = {
        _loc: {
          type: 'location',
          props: {},
        },
        id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
        name: {
          type: 'string',
          props: { label: 'Name', placeholder: 'New Workflow' },
        },
        description: {
          type: 'string',
          props: { label: 'Description', placeholder: 'New Workflow' },
        },
        metadata: {
          type: 'object',
          props: { label: 'Metadata' },
        },
        tags: {
          type: 'array',
          props: { label: 'Tags' },
        },
        config: {
          type: 'jsonobjectcode',
          props: {
            label: 'Workflow',
          },
        },
        tester: {
          type: WorkflowTester
        }
      };

      columns = [
        {
          title: 'Name',
          filterId: 'name',
          content: (item) => item.name,
        },
        { title: 'Description', filterId: 'description', content: (item) => item.description },
      ];

      formFlow = [
        '_loc',
        'id',
        'name',
        'description',
        'tags',
        'metadata',
        '<<<Workflow',
        'config',
        '>>>Tester',
        'tester',
      ];

      componentDidMount() {
        this.props.setTitle(`All Workflows`);
      }

      client = BackOfficeServices.apisClient(
        'plugins.otoroshi.io',
        'v1',
        'workflows'
      );

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/workflows/workflows',
            defaultTitle: 'All Workflows',
            defaultValue: () => ({
              id: 'workflow_' + uuid(),
              name: 'New Workflow',
              description: 'New Workflow',
              tags: [],
              metadata: {},
              config: {
                "id": "main",
                "kind": "workflow",
                "steps": [
                  {
                    "id": "hello", 
                    "kind": "call",
                    "function": "core.hello",
                    "args": {
                      "name": "Otoroshi"
                    },
                    "result": "call_res"
                  },
                ],
                "returned": {
                  "$mem_ref": {
                    "name": "call_res"
                  }
                }
              },
            }),
            itemName: 'Workflow',
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: (paginationState) => this.client.findAll(),
            updateItem: (content) => this.client.update(content),
            createItem: (content) => this.client.create(content),
            deleteItem: this.client.delete,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/workflows/workflows/edit/${item.id}`;
            },
            itemUrl: (item) => `/bo/dashboard/extensions/workflows/workflows/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: 'plugins.otoroshi.io/Workflow',
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      categories:[],
      // categories:[{
      //   title: 'Workflows',
      //   description: 'All the features related to Otoroshi Workflows',
      //   features: [
      //     {
      //       title: 'Workflows',
      //       description: 'All your Workflows',
      //       absoluteImg: '',
      //       link: '/extensions/workflows/workflows',
      //       display: () => true,
      //       icon: () => 'fa-cubes',
      //     }
      //   ]
      // }],
      sidebarItems: [],
      creationItems: [],
      dangerZoneParts: [],
      features: [],
      //features: [
      //  {
      //    title: 'Workflows',
      //    description: 'All your Workflows',
      //    img: 'private-apps',
      //    link: '/extensions/workflows/workflows',
      //    display: () => true,
      //    icon: () => 'fa-cubes',
      //  },
      //],
      searchItems: [],
      //searchItems: [
      //  {
      //    action: () => {
      //      window.location.href = `/bo/dashboard/extensions/workflows/workflows`;
      //    },
      //    env: <span className="fas fa-cubes" />,
      //    label: 'Workflows',
      //    value: 'workflows',
      //  },
      //],
      routes: [
        {
          path: '/extensions/workflows/workflows/:taction/:titem',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        },
        {
          path: '/extensions/workflows/workflows/:taction',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        },
        {
          path: '/extensions/workflows/workflows',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        },
      ],
    };
  });
}
