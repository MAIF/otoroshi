import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import CodeInput from '../../components/inputs/CodeInput';
import { Help } from '../../components/inputs';

const extensionId = 'otoroshi.extensions.Workflows';

export function setupWorkflowsExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {
    class WorkflowTester extends Component {

      state = {
        input: this.props.rawValue.test_payload ? JSON.stringify(this.props.rawValue.test_payload, null, 2) : '{\n  "name": "foo"\n}',
        running: false,
        result: null,
        run: null,
        error: null,
      };

      componentDidMount() {
        console.log("tester", this.props)
      }

      run = () => {
        this.setState(
          {
            running: true,
            result: null,
            run: null,
            error: null,
          },
          () => {
            fetch('/extensions/workflows/_test', {
              method: 'POST',
              credentials: 'include',
              headers: {
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                input: this.state.input,
                workflow: this.props.rawValue.config,
              }),
            })
              .then((r) => r.json())
              .then((r) => {
                this.setState({
                  result: r.returned,
                  run: r.run,
                  error: r.error,
                  running: false,
                });
              });
          }
        );
      };

      clear = () => {
        this.setState({
          running: false,
          result: null,
          run: null,
          error: null,
        });
      };

      render() {
        return (
          <>
            <CodeInput
              mode="json"
              label="Input"
              height="150px"
              value={this.state.input}
              onChange={(e) => {
                this.setState({ input: e })
                this.props.rawOnChange({ ...this.props.rawValue, test_payload: JSON.parse(e) });
              }}
            />
            <div className="row mb-3">
              <label className="col-sm-2 col-form-label"></label>
              <div className="col-sm-10">
                <div className="btn-group">
                  {!this.state.running && (
                    <button type="button" className="btn btn-success" onClick={this.run}>
                      <i className="fas fa-play" /> run
                    </button>
                  )}
                  {this.state.running && (
                    <button type="button" className="btn btn-success" disabled>
                      <i className="fas fa-play" /> running ...
                    </button>
                  )}
                  <button type="button" className="btn btn-danger" onClick={this.clear}>
                    <i className="fas fa-times" /> clear
                  </button>
                </div>
              </div>
            </div>
            {(this.state.result || this.state.error) && (
              <CodeInput
                mode="json"
                label="Result"
                height="150px"
                value={JSON.stringify(
                  {
                    returned: this.state.result,
                    error: this.state.error,
                  },
                  null,
                  2
                )}
              />
            )}
            {this.state.run && (
              <CodeInput
                mode="json"
                label="Memory"
                height="400px"
                value={JSON.stringify(this.state.run.memory, null, 2)}
              />
            )}
            {this.state.run && (
              <CodeInput
                mode="json"
                label="Log"
                height="400px"
                value={JSON.stringify(this.state.run, null, 2)}
              />
            )}
          </>
        );
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
            height: '40vh',
          },
        },
        tester: {
          type: WorkflowTester,
        },
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
        '<<<Tester',
        //'>>>Tester',
        'tester',
      ];

      componentDidMount() {
        this.props.setTitle(`All Workflows`);
      }

      client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

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
                id: 'main',
                kind: 'workflow',
                steps: [
                  {
                    id: 'hello',
                    kind: 'call',
                    function: 'core.hello',
                    args: {
                      name: '${input.name}',
                    },
                    result: 'call_res',
                  },
                ],
                returned: {
                  $mem_ref: {
                    name: 'call_res',
                  },
                },
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
      categories: [],
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
