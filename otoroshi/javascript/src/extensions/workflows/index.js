import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import CodeInput from '../../components/inputs/CodeInput';
import { WorkflowsContainer as WorkflowsDesigner } from './WorkflowsContainer';
import { WorkflowSidebar } from './WorkflowSidebar';
import { Link } from "react-router-dom";

const extensionId = 'otoroshi.extensions.Workflows';

export function setupWorkflowsExtension(registerExtension) {

  registerExtension(extensionId, true, (ctx) => {

    class WorkflowTester extends Component {
      state = {
        input: this.props.rawValue.test_payload
          ? JSON.stringify(this.props.rawValue.test_payload, null, 2)
          : '{\n  "name": "foo"\n}',
        running: false,
        result: null,
        run: null,
        error: null,
      };

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
                workflow_id: this.props.rawValue.id,
                workflow: this.props.rawValue.config,
                functions: this.props.rawValue.functions,
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
                this.setState({ input: e });
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
        orphans: {
          type: 'jsonobjectcode',
          props: {
            label: 'Orphans',
            height: '40vh',
          },
        },
        tester: {
          type: WorkflowTester,
        },
        'job.enabled': {
          type: 'bool',
          props: { label: 'Schedule' },
        },
        'job.kind': {
          type: 'select',
          props: {
            label: 'Kind',
            possibleValues: [
              { label: 'Interval', value: 'ScheduledEvery' },
              { label: 'Cron', value: 'Cron' },
            ]
          }
        },
        'job.instantiation': {
          type: 'select',
          props: {
            label: 'Instantiation',
            possibleValues: [
              { label: 'One Instance Per Otoroshi Instance', value: 'OneInstancePerOtoroshiInstance' },
              { label: 'One Instance Per Otoroshi Worker Instance', value: 'OneInstancePerOtoroshiWorkerInstance' },
              { label: 'One Instance Per Otoroshi Leader Instance', value: 'OneInstancePerOtoroshiLeaderInstance' },
              { label: 'One Instance Per Otoroshi Cluster', value: 'OneInstancePerOtoroshiCluster' },
            ]
          }
        },
        'job.initial_delay': {
          type: 'number',
          props: {
            label: 'Initial Delay',
            suffix: 'ms.'
          }
        },
        'job.interval': {
          type: 'number',
          props: {
            label: 'Interval',
            suffix: 'ms.'
          }
        },
        'job.cron_expression': {
          type: 'string',
          props: {
            label: 'Cron Expression',
            placeholder: '0 0/5 8-20 ? * MON-SAT *',
          }
        },
        'job.config': {
          type: 'jsonobjectcode',
          props: {
            label: 'Workflow input'
          }
        },
        'functions': {
          type: 'jsonobjectcode',
          props: {
            label: 'Functions'
          }
        }
      };

      columns = [
        {
          title: 'Name',
          filterId: 'name',
          content: (item) => item.name,
        },
        { title: 'Description', filterId: 'description', content: (item) => item.description },
        {
          title: 'Sessions', content: (item) => (
            <Link className="btn btn-sm btn-primary me-2" to={`/extensions/workflows/${item.id}/sessions`}
              onClick={e => e.stopPropagation()}>Sessions</Link>
          )
        },
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
        '>>>Local Functions',
        'functions',
        '<<<Tester',
        //'>>>Tester',
        'tester',
        '>>>Debug',
        'orphans',
        '>>>Scheduling',
        'job.enabled',
        'job.kind',
        'job.instantiation',
        'job.initial_delay',
        'job.interval',
        'job.cron_expression',
        'job.config',
      ];

      client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

      componentDidMount() {
        if (this.props.location.pathname === '/extensions/workflows')
          this.props.setSidebarContent(null)
        else {
          this.client
            .findById(this.props.match.params.titem)
            .then(workflow => this.props.setSidebarContent(<WorkflowSidebar {...this.props} workflow={workflow} />))
            .catch(console.log)
        }
        this.props.setTitle('Workflows')
      }

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/workflows',
            defaultTitle: 'Workflows',
            defaultValue: () => ({
              id: 'workflow_' + uuid(),
              name: 'New Workflow',
              description: 'New Workflow',
              tags: [],
              metadata: {},
              functions: {},
              job: {
                enabled: false,
                kind: 'ScheduledEvery',
                instantiation: 'OneInstancePerOtoroshiInstance',
                initial_delay: 1000,
                interval: 60000,
                cron_expression: '',
                config: {
                  name: 'Job'
                }
              },
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
            navigateTo: (item) => this.props.history.push(`/extensions/workflows/${item.id}/designer`),
            navigateOnEdit: (item) => this.props.history.push(`/extensions/workflows/edit/${item.id}`),
            itemUrl: (item) => `/bo/dashboard/extensions/workflows/${item.id}/designer`,
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

    class SessionResumeModal extends Component {

      state = {
        data: '{}',
        result: null,
        error: null
      }

      deleteSession = () => {
        window.newConfirm('Are you sure to delete this session ?').then((ok) => {
          if (ok)
            fetch(`/bo/api/proxy/apis/extensions/otoroshi.extensions.workflows/sessions/${this.props.workflowId}/${this.props.session.id}`, {
              method: 'DELETE',
              credentials: 'include',
            }).then(() => {
              this.props.cancel();
            });
        });
      }

      resume = () => {
        this.setState({ result: null, error: null });
        fetch(`/bo/api/proxy/apis/extensions/otoroshi.extensions.workflows/sessions/${this.props.workflowId}/${this.props.session.id}/_resume`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'content-type': 'application/json',
          },
          body: this.state.data
        }).then(r => {
          r.json().then(result => {
            if (r.status !== 200) {
              this.setState({ error: result.error });
            } else {
              this.setState({ result });
            }
          })
        });
      }

      render() {
        return (
          <>
            <div className="modal-body">
              <CodeInput
                label="Input data"
                mode="json"
                value={this.state.data}
                onChange={(e) => {
                  this.setState({ data: e })
                }}
              />
              <div style={{ width: '100%', display: 'flex', justifyContent: 'flex-end', marginTop: 10, marginBottom: 10 }}>
                <div className="btn-group">
                  <button className="btn btn-success" onClick={this.resume}><span className="fas fa-play" /> resume</button>
                  <button className="btn btn-danger" onClick={this.deleteSession}><span className="fas fa-trash" /> delete</button>
                </div>
              </div>
              {this.state.error && (
                <div className="alert alert-danger" role="alert">
                  {this.state.error}
                </div>
              )}
              {this.state.result && (
                <CodeInput
                  label="result"
                  mode="json"
                  value={JSON.stringify(this.state.result, null, 2)}
                  onChange={(e) => ({})}
                />
              )}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
                Close
              </button>
            </div>
          </>
        );
      }
    }

    class WorkflowSessionsPage extends Component {

      state = {}

      client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

      columns = [
        {
          title: 'ID',
          filterId: 'id',
          content: (item) => item.id,
        },
        { title: 'Created at', filterId: 'created_at', content: (item) => item.created_at },
        { title: 'From', filterId: 'from', content: (item) => item.from.join(".") },
        {
          title: 'Actions', content: (item) => (
            <div className="btn-group">
              <button className="btn btn-sm btn-success btn-block btn-sm" onClick={e => this.resumeSession(item)}><span className="fas fa-play" /> Resume</button>
              <button className="btn btn-sm btn-danger btn-block btn-sm" onClick={e => this.deleteSession(item.id)}><span className="fas fa-trash" /> Delete</button>
            </div>
          )
        },
      ];

      deleteSession = (id) => {
        window.newConfirm('Are you sure to delete this session ?').then((ok) => {
          if (ok)
            fetch(`/bo/api/proxy/apis/extensions/otoroshi.extensions.workflows/sessions/${this.props.match.params.workflowId}/${id}`, {
              method: 'DELETE',
              credentials: 'include',
            }).then(r => {
              this.table.update();
            })
        });
      }

      resumeSession = (item) => {
        window.popup('Session resume', (ok, cancel) => (
          <SessionResumeModal
            ok={ok}
            cancel={cancel}
            session={item}
            workflow={this.state.workflow}
            workflowId={this.props.match.params.workflowId}
          />
        ), { additionalClass: 'modal-xl' }).then(() => {
          this.table.update();
        });
      }

      componentDidMount() {
        this.client.findById(this.props.match.params.workflowId).then(r => {
          if (r) {
            this.setState({ workflow: r })
            this.props.setTitle(`Workflow sessions for ${r.name}`)
          }
        })
      }

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/workflows',
            defaultTitle: 'Workflow sessions',
            itemName: 'Workflow session',
            columns: this.columns,
            fetchItems: () => {
              return fetch(`/bo/api/proxy/apis/extensions/otoroshi.extensions.workflows/sessions/${this.props.match.params.workflowId}`, {
                method: 'GET',
                credentials: 'include',
              }).then(r => r.json())
            },
            showActions: false,
            showLink: false,
            rowNavigation: false,
            extractKey: (item) => item.id,
            injectTable: (table) => this.table = table
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      categories: [
        {
          title: 'Workflows',
          description: 'All the features related to Otoroshi Workflows',
          features: [
            {
              title: 'Workflows',
              description: 'All your Workflows',
              absoluteImg: '',
              link: '/extensions/workflows',
              display: () => true,
              icon: () => 'fa-cubes',
            },
          ],
        },
      ],
      sidebarItems: [],
      creationItems: [],
      dangerZoneParts: [],
      features: [
        {
          title: 'Workflows',
          description: 'All your Workflows',
          img: 'private-apps',
          link: '/extensions/workflows',
          display: () => true,
          icon: () => 'fa-cubes',
          tag: <span className="badge bg-xs bg-warning">ALPHA</span>,
        },
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/extensions/workflows`;
          },
          env: <span className="fas fa-cubes" />,
          label: 'Workflows',
          value: 'workflows',
        },
      ],
      routes: [
        {
          path: '/extensions/workflows/:workflowId/sessions',
          component: (props) => {
            return <WorkflowSessionsPage {...props} />;
          },
        },
        {
          path: '/extensions/workflows/:workflowId/designer',
          component: (props) => {
            return <WorkflowsDesigner {...props} />;
          },
        },
        {
          path: '/extensions/workflows/:taction/:titem',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        },
        {
          path: '/extensions/workflows/:taction',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        },
        {
          path: '/extensions/workflows',
          component: (props) => {
            return <WorkflowsPage {...props} />;
          },
        }
      ],
    };
  });
}
