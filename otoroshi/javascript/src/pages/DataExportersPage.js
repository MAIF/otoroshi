import React, { Component } from 'react';
import faker from 'faker';

import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, SelectInput, ArrayInput, Form } from '../components/inputs';
import { Collapse } from '../components/inputs/Collapse';
import { Separator } from '../components/Separator';

export class DataExportersPage extends Component {
  state = {
    config: {},
    dataExporters: []
  }

  componentDidMount() {
    this.props.setTitle(`Data exporters`);
    BackOfficeServices.getGlobalConfig().then(config =>
      this.setState({ config, dataExporters: config.dataExporters }, () => this.table.update())
    );
    this.mountShortcuts();
  }

  componentWillUnmount() {
    this.unmountShortcuts();
  }

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  saveShortcut = e => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.changed) {
        this.saveGlobalConfig();
      }
    }
  };

  saveGlobalConfig = e => {
    if (e && e.preventDefault) e.preventDefault();

    const config = { ...this.state.config, dataExporters: this.state.dataExporters }
    BackOfficeServices.updateGlobalConfig(config)
      .then(() => {
        this.setState({ config });
      });
  };

  addExporter = e => {
    if (e && e.preventDefault) e.preventDefault();
    window.popup('New Certificate', (ok, cancel) => <NewExporterForm ok={ok} cancel={cancel} />, {
      style: { width: '100%', height: '80%', overflow: "scroll" },
    })
      .then(exporter => this.setState({ dataExporters: [...this.state.dataExporters, exporter] },
        () => this.saveGlobalConfig()
          .then(() => this.table.update())))
  };

  updateExporter = (item, table) => {
    window.popup('New Certificate', (ok, cancel) => <NewExporterForm ok={ok} cancel={cancel} exporter={item} />, {
      style: { width: '100%', height: '80%', overflow: "scroll" },
    })
      .then(exporter => this.setState({ dataExporters: [...this.state.dataExporters.filter(e => e.id !== exporter.id), exporter] },
        () => this.saveGlobalConfig()
          .then(() => this.table.update())))
  }

  deleteExporter = (item, table) => {
    window
      .newConfirm('Are you sure you want to delete this exporter ?')
      .then(confirmed => {
        if (confirmed) {
          this.setState({ dataExporters: [...this.state.dataExporters.filter(e => e.id !== exporter.id), exporter] },
            () => this.saveGlobalConfig()
              .then(() => this.table.update()))
        }
      });
  }

  nothing() {
    return null;
  }

  columns = [
    {
      title: 'type',
      content: item => item.type,
    },
    {
      title: 'Event filters',
      content: item => item.eventsFilters
    },
    {
      title: 'Actions',
      style: { textAlign: 'right', width: 100 },
      notFilterable: true,
      content: item => item.enabled,
      cell: (v, item, table) => {
        return (
          <div>
            <button
              type="button"
              className="btn btn-danger btn-sm"
              disabled={this.state && this.state.env && this.state.env.adminApiId === item.id}
              onClick={e => this.updateExporter(item, table)}>
              <i className="glyphicon glyphicon-edit" />
            </button>
            <button
              type="button"
              className="btn btn-danger btn-sm"
              disabled={this.state && this.state.env && this.state.env.adminApiId === item.id}
              onClick={e => this.deleteExporter(item, table)}>
              <i className="glyphicon glyphicon-trash" />
            </button>
          </div>
        );
      },
    },
  ];

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="data-exporters"
          defaultTitle="Data exporters"
          defaultValue={() => ({})}
          itemName="data-exporters"
          columns={this.columns}
          fetchItems={() => Promise.resolve(this.state.dataExporters)}
          updateItem={this.nothing}
          deleteItem={this.nothing}
          createItem={this.nothing}
          showActions={false}
          showLink={false}
          rowNavigation={false}
          firstSort={0}
          extractKey={item => item.id}
          injectTable={ref => this.table = ref}
          injectTopBar={() => (
            <>
              <div className="btn-group" style={{ marginRight: 5 }}>
                <button
                  type="button"
                  onClick={this.addExporter}
                  style={{ marginRight: 0 }}
                  className="btn btn-primary">
                  <i className="glyphicon glyphicon-plus-sign" /> Create new exporter
                </button>
              </div>
            </>
          )}
        />
      </div>
    )
  }
}

export class NewExporterForm extends Component {
  state = {
    id: faker.random.alphaNumeric(64),
    type: undefined,
    eventsFilters: [],
    config: undefined
  };

  componentDidMount() {
    if (this.props.exporter) {
      this.setState({
        id: this.props.exporter.id,
        type: this.props.exporter.type,
        eventsFilters: this.props.exporter.eventsFilters,
        config: this.props.exporter.config
      })
    }
  }

  updateType = type => {
    const config = possibleExporterConfigFormValues[type].default
    this.setState({ type, config })
  }

  render() {
    return (
      <>
        <div className="modal-body">
          <form className="form-horizontal">
            <SelectInput
              label="Type"
              placeholder="The type of exporter"
              value={this.state.type}
              onChange={e => this.updateType(e)}
              disabled={!!(this.state.env && this.state.env.staticExposedDomain)}
              possibleValues={Object.keys(possibleExporterConfigFormValues)}
              value={this.state.type}
              help="You can export otoroshi events ..."
            />
            <ArrayInput
              label="Event filters"
              placeholder="Choose a event type or type a regex"
              value={this.state.eventsFilters}
              values={["GatewayEvent", "TcpEvent", "ApiKeySecretWillRotate"]}
              help="Todo"
              onChange={e => this.setState({ eventsFilters: e })}
            />
            {this.state.type && (
              <Collapse collapsed={this.state.allCollapsed} initCollapsed={false} label="Config">
                <Form
                  value={this.state.config}
                  onChange={config => this.setState({ config })}
                  flow={possibleExporterConfigFormValues[this.state.type].flow}
                  schema={possibleExporterConfigFormValues[this.state.type].schema}
                  style={{ marginTop: 50 }}
                />
              </Collapse>)
            }
          </form>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-success"
            onClick={e => this.props.ok(this.state)}>
            Create
          </button>
        </div>
      </>
    );
  }
}

const possibleExporterConfigFormValues = {
  elastic: {
    default: {
      clusterUri: undefined,
      index: undefined,
      type: undefined,
      user: undefined,
      password: undefined,
      mtlsConfig: {
        mtls: false,
        loose: false,
        trustAll: false,
        certs: [],
        trustedCerts: []
      }
    },
    flow: [
      'clusterUri',
      'index',
      'type',
      'user',
      'password',
      'mtlsConfig.mtls',
      'mtlsConfig.loose',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
    ],
    schema: {
      clusterUri: {
        type: 'string',
        props: { label: 'Cluster URI', placeholder: 'Elastic cluster URI' },
      },
      index: {
        type: 'string',
        props: { label: 'Index', placeholder: 'Elastic index' },
      },
      type: {
        type: 'string',
        props: { label: 'Type', placeholder: 'Event type' },
      },
      user: {
        type: 'string',
        props: { label: 'User', placeholder: 'Elastic User (optional)' },
      },
      password: {
        type: 'string',
        props: { label: 'Password', placeholder: 'Elastic password (optional)', type: 'password' },
      },
      'mtlsConfig.mtls': {
        type: 'bool',
        props: { label: 'Use mTLS' },
      },
      'mtlsConfig.loose': {
        type: 'bool',
        props: { label: 'TLS loose' },
      },
      'mtlsConfig.trustAll': {
        type: 'bool',
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.certs': {
        type: 'array',
        props: {
          label: 'Client certificates',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: a => ({
            value: a.id,
            label: (
              <span>
                <span className="label label-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'mtlsConfig.trustedCerts': {
        type: 'array',
        props: {
          label: 'Trusted certificates',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: a => ({
            value: a.id,
            label: (
              <span>
                <span className="label label-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
    }
  },
  webhook: {
    default: {
      url: undefined,
      headers: {},
      mtlsConfig: {
        mtls: false,
        loose: false,
        trustAll: false,
        certs: [],
        trustedCerts: []
      }
    },
    flow: [
      'url',
      'headers',
      'mtlsConfig.mtls',
      'mtlsConfig.loose',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
    ],
    schema: {
      url: {
        type: 'string',
        props: { label: 'Alerts hook URL', placeholder: 'URL of the webhook target' },
      },
      headers: {
        type: 'object',
        props: {
          label: 'Hook Headers',
          placeholderKey: 'Name of the header',
          placeholderValue: 'Value of the header',
        },
      },
      'mtlsConfig.mtls': {
        type: 'bool',
        props: { label: 'Use mTLS' },
      },
      'mtlsConfig.loose': {
        type: 'bool',
        props: { label: 'TLS loose' },
      },
      'mtlsConfig.trustAll': {
        type: 'bool',
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.certs': {
        type: 'array',
        props: {
          label: 'Client certificates',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: a => ({
            value: a.id,
            label: (
              <span>
                <span className="label label-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'mtlsConfig.trustedCerts': {
        type: 'array',
        props: {
          label: 'Trusted certificates',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: a => ({
            value: a.id,
            label: (
              <span>
                <span className="label label-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
    }
  }
}