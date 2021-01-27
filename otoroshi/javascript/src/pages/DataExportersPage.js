import React, { Component } from 'react';
import faker from 'faker';

import * as BackOfficeServices from '../services/BackOfficeServices';
import {
  Table,
  SelectInput,
  ArrayInput,
  Form,
  BooleanInput,
  TextInput,
  ObjectInput,
  NumberInput,
  SimpleBooleanInput,
} from '../components/inputs';
import { Collapse } from '../components/inputs/Collapse';
import { JsonObjectAsCodeInput } from '../components/inputs/CodeInput';

function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}
class Mailer extends Component {
  genericFormFlow = ['url', 'headers', 'to'];
  mailgunFormFlow = ['eu', 'apiKey', 'domain', 'to'];
  mailjetFormFlow = ['apiKeyPublic', 'apiKeyPrivate', 'to'];
  sendgridFormFlow = ['apiKey', 'to'];
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
    to: {
      type: 'array',
      props: {
        label: 'Email addresses',
        placeholder: 'Email address to receive events',
        help: 'Every email address will be notified with a summary of Otoroshi events',
      },
    },
  };
  sendgridFormSchema = {
    apiKey: {
      type: 'string',
      props: {
        label: 'Sendgrid api key',
        placeholder: 'Sendgrid api key',
      },
    },
    to: {
      type: 'array',
      props: {
        label: 'Email addresses',
        placeholder: 'Email address to receive events',
        help: 'Every email address will be notified with a summary of Otoroshi events',
      },
    },
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
    to: {
      type: 'array',
      props: {
        label: 'Email addresses',
        placeholder: 'Email address to receive events',
        help: 'Every email address will be notified with a summary of Otoroshi events',
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
    to: {
      type: 'array',
      props: {
        label: 'Email addresses',
        placeholder: 'Email address to receive events',
        help: 'Every email address will be notified with a summary of Otoroshi events',
      },
    },
  };
  render() {
    const settings = this.props.value;
    const type = settings.type;

    console.debug({ settings });
    return (
      <div>
        <SelectInput
          label="Type"
          value={type}
          onChange={(e) => {
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
              case 'sendgrid':
                this.props.onChange({
                  type: 'sendgrid',
                  apiKey: '',
                  to: [],
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
    dataExporters: [],
  };

  componentDidMount() {
    this.props.setTitle(`Data exporters`);
  }

  nothing() {
    return null;
  }

  columns = [
    {
      title: 'Name',
      content: (item) => item.name,
    },
    {
      title: 'Type',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.type,
    },
    {
      title: 'Enabled',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.enabled,
      cell: (v, item, table) => {
        return (
          <SimpleBooleanInput
            value={item.enabled}
            onChange={(value) => {
              BackOfficeServices.updateDataExporterConfig({
                ...item,
                enabled: value,
              }).then(() => table.update());
            }}
          />
        );
      },
    },
  ];

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="exporters"
          defaultTitle="Data exporters"
          defaultValue={() => BackOfficeServices.createNewDataExporterConfig('file')}
          itemName="data exporter"
          columns={this.columns}
          fetchItems={BackOfficeServices.findAllDataExporterConfigs}
          updateItem={BackOfficeServices.updateDataExporterConfig}
          deleteItem={BackOfficeServices.deleteDataExporterConfig}
          createItem={BackOfficeServices.createDataExporterConfig}
          formComponent={NewExporterForm}
          stayAfterSave={true}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={(item) => item.id}
          injectTable={(ref) => (this.table = ref)}
          export={true}
          kubernetesKind="DataExporter"
          navigateTo={(exporter) => {
            this.props.history.push({
              pathname: `/exporters/edit/${exporter.id}`,
            });
          }}
        />
      </div>
    );
  }
}

export class NewExporterForm extends Component {
  updateType = (type) => {
    BackOfficeServices.createNewDataExporterConfig(type).then((config) => {
      this.props.onChange({ ...this.props.value, type, ...config });
    });
  };

  data = () => {
    return this.props.value;
  };

  dataChange = (obj) => {
    this.props.onChange({ ...this.props.value, ...obj });
  };

  render() {
    return (
      <>
        <form className="form-horizontal">
          <SelectInput
            label="Type"
            placeholder="The type of exporter"
            value={this.data().type}
            onChange={(e) => this.updateType(e)}
            possibleValues={Object.keys(possibleExporterConfigFormValues)}
            help="The type of event exporter"
          />
          <BooleanInput
            label="Enabled"
            value={this.data().enabled}
            onChange={(e) => this.dataChange({ enabled: e })}
            help="Enable exporter"
          />
          <TextInput
            label="Name"
            placeholder="data exporter config name"
            value={this.data().name}
            help="The data exporter name"
            onChange={(e) => this.dataChange({ name: e })}
          />
          <TextInput
            label="Description"
            placeholder="data exporter config description"
            value={this.data().desc}
            help="The data exporter description"
            onChange={(e) => this.dataChange({ desc: e })}
          />
          <ObjectInput
            label="Metadata"
            value={this.data().metadata}
            onChange={(v) => this.dataChange({ metadata: e })}
          />
          <Collapse initCollapsed={true} label="Filtering and projection">
            <JsonObjectAsCodeInput
              label="Filtering"
              value={this.data().filtering}
              onChange={(e) => this.dataChange({ filtering: e })}
              height="200px"
            />
            <JsonObjectAsCodeInput
              label="Projection"
              value={this.data().projection}
              onChange={(e) => this.dataChange({ projection: e })}
              height="200px"
            />
          </Collapse>
          <Collapse initCollapsed={true} label="Queue details">
            <NumberInput
              label="Buffer Size"
              value={this.data().bufferSize}
              onChange={(v) => this.dataChange({ bufferSize: v })}
            />
            <NumberInput
              label="JSON conversion workers"
              value={this.data().jsonWorkers}
              onChange={(v) => this.dataChange({ jsonWorkers: v })}
            />
            <NumberInput
              label="Send workers"
              value={this.data().sendWorkers}
              onChange={(v) => this.dataChange({ sendWorkers: v })}
            />
            <NumberInput
              label="Group size"
              value={this.data().groupSize}
              onChange={(v) => this.dataChange({ groupSize: v })}
            />
            <NumberInput
              label="Group duration"
              value={this.data().groupDuration}
              onChange={(v) => this.dataChange({ groupDuration: v })}
            />
          </Collapse>
          {this.data().type && (
            <Collapse
              collapsed={this.data().allCollapsed}
              initCollapsed={false}
              label="Exporter config">
              <Form
                value={() => {
                  if (this.data().type === 'mailer') {
                    return { mailerSettings: this.data().config };
                  } else {
                    return this.data().config;
                  }
                }}
                onChange={(config) => {
                  if (this.data().type === 'mailer') {
                    return this.dataChange({ config: config.mailerSettings });
                  } else {
                    return this.dataChange({ config });
                  }
                }}
                flow={possibleExporterConfigFormValues[this.data().type].flow}
                schema={possibleExporterConfigFormValues[this.data().type].schema}
                style={{ marginTop: 50 }}
              />
            </Collapse>
          )}
        </form>
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
        props: { label: 'Custom TLS Settings' },
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
          transformer: (a) => ({
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
          transformer: (a) => ({
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
    },
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
        props: { label: 'Custom TLS Settings' },
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
          transformer: (a) => ({
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
          transformer: (a) => ({
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
    },
  },
  pulsar: {
    flow: [
      'uri',
      'mtlsConfig.mtls',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
      'tenant',
      'namespace',
      'topic',
    ],
    schema: {
      uri: {
        type: 'string',
        props: {
          label: 'Pulsar URI',
          help: 'URI of the pulsar server',
        },
      },
      tlsTrustCertsFilePath: {
        type: 'string',
        props: {
          label: 'Pulsar trusted cert. path',
          help: 'The path to the trusted TLS certificate file',
        },
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
      },
      'mtlsConfig.mtls': {
        type: 'bool',
        props: {
          label: 'Custom TLS Settings',
          help: 'Custom TLS Settings',
        },
      },
      'mtlsConfig.trustAll': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.certs': {
        type: 'array',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: {
          label: 'Client certificates',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
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
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls && !v.mtlsConfig.trustAll),
        props: {
          label: 'Trusted certificates',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
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
    },
  },
  kafka: {
    flow: [
      'servers',
      'keyPass',
      'keystore',
      'truststore',
      'mtlsConfig.mtls',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
      'topic',
    ],
    schema: {
      servers: {
        type: 'array',
        props: {
          label: 'Kafka Servers',
          placeholder: '127.0.0.1:9092',
          help: 'The list of servers to contact to connect the Kafka client with the Kafka cluster',
        },
      },
      keyPass: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls),
        props: {
          label: 'Kafka keypass',
          placeholder: 'secret',
          type: 'password',
          help:
            'The keystore password if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      keystore: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls),
        props: {
          label: 'Kafka keystore path',
          placeholder: '/home/bas/client.keystore.jks',
          help:
            'The keystore path on the server if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      truststore: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls),
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
          label: 'Custom TLS Settings',
          help: 'Custom TLS Settings',
        },
      },
      'mtlsConfig.trustAll': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.certs': {
        type: 'array',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: {
          label: 'Client certificates',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
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
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls && !v.mtlsConfig.trustAll),
        props: {
          label: 'Trusted certificates',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
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
      topic: {
        type: 'string',
        props: {
          label: 'Kafka topic',
          placeholder: 'otoroshi-alerts',
          help: 'The topic on which Otoroshi alerts will be sent',
        },
      },
    },
  },
  mailer: {
    flow: ['mailerSettings'],
    schema: {
      mailerSettings: {
        type: Mailer,
      },
    },
  },
  file: {
    flow: ['path', 'maxFileSize'],
    schema: {
      path: {
        type: 'string',
        props: { label: 'File path', placeholder: 'path for the file' },
      },
      maxFileSize: {
        type: 'number',
        props: {
          label: 'Max file size',
          placeholder: 'Max size in bytes for a file',
          suffix: 'bytes',
        },
      },
    },
  },
  console: {
    flow: [],
    schema: {},
  },
  custom: {
    flow: ['ref', 'config'],
    schema: {
      ref: {
        type: 'select',
        props: { label: 'Exporter', valuesFrom: `/bo/api/proxy/api/scripts/_list?type=exporter` },
      },
      config: {
        type: 'code',
        props: { label: 'Exporter config.' },
      },
    },
  },
  metrics: {
    flow: [],
    schema: {}
  }
};
