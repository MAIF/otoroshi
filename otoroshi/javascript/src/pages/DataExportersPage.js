import React, { Component } from 'react';
import faker from 'faker';

import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, SelectInput, ArrayInput, Form, BooleanInput, TextInput, ObjectInput } from '../components/inputs';
import { Collapse } from '../components/inputs/Collapse';
import Creatable from 'react-select/lib/Creatable';

function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}
class Mailer extends Component {
  genericFormFlow = ['url', 'headers'];
  mailgunFormFlow = ['eu', 'apiKey', 'domain'];
  mailjetFormFlow = ['apiKeyPublic', 'apiKeyPrivate'];
  sendgridFormFlow = ['apiKey'];
  genericFormSchema = {
    url: {
      type: 'string',
      props: {
        label: 'Mailer url',
        placeholder: 'Mailer url',
      },
    },
    headers: {
      type: 'object',
      props: {
        label: 'Headers',
      },
    },
  };
  sendgridSchema = {
    apiKey: {
      type: 'string',
      props: {
        label: 'Sendgrid api key',
        placeholder: 'Sendgrid api key',
      },
    }
  };
  mailgunFormSchema = {
    eu: {
      type: 'bool',
      props: {
        label: 'EU',
      },
    },
    apiKey: {
      type: 'string',
      props: {
        label: 'Mailgun api key',
        placeholder: 'Mailgun api key',
      },
    },
    domain: {
      type: 'string',
      props: {
        label: 'Mailgun domain',
        placeholder: 'Mailgun domain',
      },
    },
  };
  mailjetFormSchema = {
    apiKeyPublic: {
      type: 'string',
      props: {
        label: 'Public api key',
        placeholder: 'Public api key',
      },
    },
    apiKeyPrivate: {
      type: 'string',
      props: {
        label: 'Private api key',
        placeholder: 'Private api key',
      },
    },
  };
  render() {
    const settings = this.props.value;
    const type = settings.type;

    return (
      <div>
        <SelectInput
          label="Type"
          value={type}
          onChange={e => {
            switch (e) {
              case 'console':
                this.props.onChange({
                  type: 'console',
                });
                break;
              case 'generic':
                this.props.onChange({
                  type: 'generic',
                  url: 'https://my.mailer.local/emails/_send',
                  headers: {},
                });
                break;
              case 'mailgun':
                this.props.onChange({
                  type: 'mailgun',
                  eu: false,
                  apiKey: '',
                  domain: '',
                });
                break;
              case 'mailjet':
                this.props.onChange({
                  type: 'mailjet',
                  apiKeyPublic: '',
                  apiKeyPrivate: '',
                });
                break;
            }
          }}
          possibleValues={[
            { label: 'Console', value: 'console' },
            { label: 'Generic', value: 'generic' },
            { label: 'Mailgun', value: 'mailgun' },
            { label: 'Mailjet', value: 'mailjet' },
            { label: 'Sendgrid', value: 'sendgrid' },
          ]}
          help="..."
        />
        {type === 'generic' && (
          <Form
            value={settings}
            onChange={this.props.onChange}
            flow={this.genericFormFlow}
            schema={this.genericFormSchema}
            style={{ marginTop: 5 }}
          />
        )}
        {type === 'mailgun' && (
          <Form
            value={settings}
            onChange={this.props.onChange}
            flow={this.mailgunFormFlow}
            schema={this.mailgunFormSchema}
            style={{ marginTop: 5 }}
          />
        )}
        {type === 'mailjet' && (
          <Form
            value={settings}
            onChange={this.props.onChange}
            flow={this.mailjetFormFlow}
            schema={this.mailjetFormSchema}
            style={{ marginTop: 5 }}
          />
        )}
        {type === 'sendgrid' && (
          <Form
            value={settings}
            onChange={this.props.onChange}
            flow={this.sendgridFormFlow}
            schema={this.sendgridFormSchema}
            style={{ marginTop: 5 }}
          />
        )}
      </div>
    );
  }
}

export class DataExportersPage extends Component {
  state = {
    dataExporters: []
  }

  componentDidMount() {
    this.props.setTitle(`Data exporters`);
    BackOfficeServices.findAllDataExporterConfigs().then(dataExporters =>
      this.setState({ dataExporters }, () => this.table.update())
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
      //todo: be smart
    }
  };

  addExporter = e => {
    if (e && e.preventDefault) e.preventDefault();
    window.popup('New Exporter', (ok, cancel) => <NewExporterForm ok={ok} cancel={cancel} />, {
      style: { width: '100%', height: '80%', overflow: "scroll" },
    })
      .then(config => {
        if (config) {
          BackOfficeServices.createDataExporterConfig(config)
            .then(config => this.setState({ dataExporters: [...this.state.dataExporters, config] },
              () => this.table.update()))
        }
      })
  };

  updateExporter = (config, table) => {
    window.popup('Update data exporter config', (ok, cancel) => <NewExporterForm ok={ok} cancel={cancel} exporter={config} />, {
      style: { width: '100%', height: '80%', overflow: "scroll" },
    })
      .then(config => BackOfficeServices.updateDataExporterConfig(config))
      .then(config => this.setState({ dataExporters: [...this.state.dataExporters.filter(e => e.id !== config.id), config] },
        () => this.table.update()))
  }

  deleteExporter = (config, table) => {
    window
      .newConfirm('Are you sure you want to delete this data exporter config ?')
      .then(confirmed => {
        if (confirmed) {
          BackOfficeServices.deleteDataExporterConfig(config)
            .then(() => this.setState({ dataExporters: this.state.dataExporters.filter(e => e.id !== config.id) },
              () => this.table.update()))
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
      title: 'Delete',
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
          rowNavigation={true}
          firstSort={0}
          extractKey={item => item.id}
          injectTable={ref => this.table = ref}
          navigateTo={exporter => this.updateExporter(exporter, this.table)}
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
    name: undefined,
    metadata: {},
    enabled: true,
    eventsFilters: [],
    eventsFiltersNot: [],
    config: undefined
  };

  componentDidMount() {
    if (this.props.exporter) {
      this.setState({
        id: this.props.exporter.id,
        type: this.props.exporter.type,
        enabled: this.props.exporter.enabled,
        eventsFilters: this.props.exporter.eventsFilters,
        eventsFiltersNot: this.props.exporter.eventsFiltersNot,
        config: this.props.exporter.type === 'mailer' ? { mailerSettings: this.props.exporter.config } : this.props.exporter.config
      })
    }
  }

  updateType = type => {
    BackOfficeServices.createNewDataExporterConfig(type)
      .then(config => this.setState({ type, ...config }))
  }

  render() {
    const isInvalidForm = !this.state.type || !this.state.eventsFilters.length
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
              help="The type of event exporter"
            />
            <BooleanInput 
              label="Enabled"
              value={this.state.enabled}
              onChange={e => this.setState({ enabled: e })}
              disabled={!!(this.state.env && this.state.env.staticExposedDomain)}
              help="Enable exporter"
            />
            <TextInput
              label="Name"
              placeholder="data exporter config name"
              value={this.state.name}
              help="The data exporter name"
              onChange={e => this.setState({name: e})}
            />
            <TextInput
              label="Description"
              placeholder="data exporter config description"
              value={this.state.name}
              help="The data exporter description"
              onChange={e => this.setState({ desc: e })}
            />
            <ObjectInput
              label="Metadata"
              value={this.state.metadata}
              onChange={v => this.setState({metadata: e})}
            />
            <ArrayInput
              creatable
              label="Events filters"
              placeholder="Choose a event type or type a regex"
              value={this.state.eventsFilters}
              values={["AlertEvent", "AuditEvent", "GatewayEvent", "TcpEvent", "HealthCheckEvent", ...this.state.eventsFilters]}
              help="regex to filter otoroshi events to send to the event exporter"
              onChange={e => this.setState({ eventsFilters: e })}
            />
            <ArrayInput
              creatable
              label="Events filters Not"
              placeholder="Choose a event type or type a regex which you don't want to export"
              value={this.state.eventsFiltersNot}
              values={["AlertEvent", "AuditEvent", "GatewayEvent", "TcpEvent", "HealthCheckEvent", ...this.state.eventsFilters]}
              help="regex to filter otoroshi events to send to the event exporter"
              onChange={e => this.setState({ eventsFiltersNot: e })}
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
            disabled={isInvalidForm ? 'disabled' : null}
            onClick={e => {
              if (!isInvalidForm) {
                this.props.ok(this.state.type === 'mailer' ? { ...this.state, config: { ...this.state.config.mailerSettings } } : this.state)
              }
            }}>
            Create
          </button>
        </div>
      </>
    );
  }
}

const possibleExporterConfigFormValues = {
  elastic: {
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
  },
  pulsar: {
    flow: [
      'uri',
      'tlsTrustCertsFilePath',
      'tenant',
      'namespace',
      'topic'
    ],
    schema: {
      uri: {
        type: 'string',
        props: {
          label: 'Pulsar URI',
          help: 'URI of the pulsar server'
        },
      },
      tlsTrustCertsFilePath: {
        type: 'string',
        props: {
          label: 'Pulsar trusted cert. path',
          help: 'The path to the trusted TLS certificate file'
        }
      },
      tenant: {
        type: 'string',
        props: {
          label: 'Pulsar tenant',
          help: 'Tenant on the pulsar server',
        },
      },
      namespace: {
        type: 'string',
        props: {
          label: 'Pulsar namespace',
          help: 'Namespace on the pulsar server',
        },
      },
      topic: {
        type: 'string',
        props: {
          label: 'Pulsar topic',
          help: 'Topic on the pulsar server',
        },
      }
    }
  },
  kafka: {
    default: {
      servers: [],
      mtlsConfig: {
        mtls: false,
        loose: false,
        trustAll: false,
        certs: [],
        trustedCerts: []
      },
      keystore: undefined,
      truststore: undefined,
      keyPass: undefined,
      topic: undefined,
    },
    flow: [
      'servers',
      'mtlsConfig.mtls',
      'keyPass',
      'keystore',
      'truststore',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
      'topic',
    ],
    schema: {
      'servers': {
        type: 'array',
        props: {
          label: 'Kafka Servers',
          placeholder: '127.0.0.1:9092',
          help: 'The list of servers to contact to connect the Kafka client with the Kafka cluster',
        },
      },
      'keyPass': {
        type: 'string',
        display: v => tryOrTrue(() => !v.mtlsConfig.mtls),
        props: {
          label: 'Kafka keypass',
          placeholder: 'secret',
          type: 'password',
          help: 'The keystore password if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      'keystore': {
        type: 'string',
        display: v => tryOrTrue(() => !v.mtlsConfig.mtls),
        props: {
          label: 'Kafka keystore path',
          placeholder: '/home/bas/client.keystore.jks',
          help:
            'The keystore path on the server if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      'truststore': {
        type: 'string',
        display: v => tryOrTrue(() => !v.mtlsConfig.mtls),
        props: {
          label: 'Kafka truststore path',
          placeholder: '/home/bas/client.truststore.jks',
          help:
            'The truststore path on the server if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      'mtlsConfig.mtls': {
        type: 'bool',
        props: {
          label: 'Use client certs.',
          help: 'Use client certs. from Otoroshi datastore',
        },
      },
      'mtlsConfig.trustAll': {
        type: 'bool',
        display: v => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.certs': {
        type: 'array',
        display: v => tryOrTrue(() => v.mtlsConfig.mtls),
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
        display: v =>
          tryOrTrue(() => v.mtlsConfig.mtls && !v.mtlsConfig.trustAll),
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
      'topic': {
        type: 'string',
        props: {
          label: 'Kafka topic',
          placeholder: 'otoroshi-alerts',
          help: 'The topic on which Otoroshi alerts will be sent',
        },
      },
    }
  },
  mailer: {
    flow: [
      'mailerSettings'
    ],
    schema: {
      mailerSettings: {
        type: Mailer
      }
    }
  }
}

