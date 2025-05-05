import React, { Component, useState } from 'react';
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
import { CheckElasticsearchConnection } from '../components/elasticsearch';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import {
  LabelAndInput,
  NgArrayRenderer,
  NgForm,
  NgObjectRenderer,
  NgSelectRenderer,
  NgStringRenderer,
} from '../components/nginputs';
import { Location } from '../components/Location';

function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}

function tryOrFalse(f) {
  try {
    return f();
  } catch (e) {
    return false;
  }
}

class CustomMetrics extends Component {
  schema = {
    tags: {
      type: 'object',
      label: 'Tags',
    },
    metrics: {
      type: 'array',
      array: true,
      format: 'form',
      label: 'Metrics',
      flow: ['id', 'eventType', 'selector', 'kind', 'labels'],
      schema: {
        id: {
          type: 'string',
          label: 'Metric name',
        },
        eventType: {
          type: 'select',
          label: 'Event',
          props: {
            options: [
              'GatewayEvent',
              'MaxConcurrentRequestReachedAlert',
              'CircuitBreakerOpenedAlert',
              'CircuitBreakerClosedAlert',
              'SessionDiscardedAlert',
              'SessionsDiscardedAlert',
              'PanicModeAlert',
              'OtoroshiExportAlert',
              'U2FAdminDeletedAlert',
              'BlackListedBackOfficeUserAlert',
              'AdminLoggedInAlert',
              'AdminFirstLogin',
              'AdminLoggedOutAlert',
              'GlobalConfigModification',
              'RevokedApiKeyUsageAlert',
              'ServiceGroupCreatedAlert',
              'ServiceGroupUpdatedAlert',
              'ServiceGroupDeletedAlert',
              'ServiceCreatedAlert',
              'ServiceUpdatedAlert',
              'ServiceDeletedAlert',
              'ApiKeyCreatedAlert',
              'ApiKeyUpdatedAlert',
              'ApiKeyDeletedAlert',
              'TrafficCaptureEvent',
              'TcpEvent',
              'HealthCheckEvent',
              'RequestBodyEvent',
              'ResponseBodyEvent',
              'MirroringEvent',
              'BackOfficeEvent',
              'AdminApiEvent',
              'SnowMonkeyOutageRegisteredEvent',
              'CircuitBreakerOpenedEvent',
              'CircuitBreakerClosedEvent',
              'MaxConcurrentRequestReachedEvent',
              'JobRunEvent',
              'JobErrorEvent',
              'JobStoppedEvent',
              'JobStartedEvent',
            ],
          },
        },
        selector: {
          renderer: (props) => {
            return (
              <LabelAndInput label="Increment value">
                <NgSelectRenderer
                  ngOptions={{
                    spread: true,
                  }}
                  name="Selector"
                  creatable={true}
                  value={props?.value}
                  optionsFrom={`/bo/api/proxy/api/events/_template?eventType=${
                    props?.rootValue?.eventType || 'GatewayEvent'
                  }`}
                  optionsTransformer={(arr) => arr.map((item) => ({ value: item, label: item }))}
                  onChange={props.onChange}
                />
              </LabelAndInput>
            );
          },
        },
        kind: {
          type: 'select',
          label: 'Type of metric',
          props: {
            options: ['Counter', 'Timer', 'Histogram'],
          },
        },
        labels: {
          type: 'object',
          label: 'Labels',
        },
      },
    },
  };

  render() {
    const value = this.props.rawValue;

    // console.log("IN SUB FORM", value)

    return (
      <NgForm
        schema={this.schema}
        value={value}
        flow={['metrics', 'tags']}
        onChange={(e) => this.props.onChange(e)}
      />
    );
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
        initTransform: (values) => values.map((value) => value.email),
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
        initTransform: (values) => values.map((value) => value.email),
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
        initTransform: (values) => values.map((value) => value.email),
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
        initTransform: (values) => values.map((value) => value.email),
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
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Type',
      filterId: 'type',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.type,
    },
    {
      title: 'Enabled',
      filterId: 'enabled',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.enabled,
      cell: (v, item, table) => {
        return (
          <SimpleBooleanInput
            value={item.enabled}
            onChange={(value) => {
              BackOfficeServices.findDataExporterConfigById(item.id).then((exporter) => {
                BackOfficeServices.updateDataExporterConfig({
                  ...exporter,
                  enabled: value,
                }).then(() => table.update());
              });
            }}
          />
        );
      },
    },
  ];

  render() {
    return (
      <div>
        {/* <button
          onClick={() => {

          }}
          className="btn btn-primary"
          style={{ _backgroundColor: "var(--color-primary)", _borderColor: "var(--color-primary)", marginLeft: 5 }}>
          <i className="fas fa-hat-wizard" /> Create with wizard
        </button> */}
        <Table
          parentProps={this.props}
          selfUrl="exporters"
          defaultTitle="Data exporters"
          defaultValue={() => BackOfficeServices.createNewDataExporterConfig('file')}
          itemName="Data exporter"
          columns={this.columns}
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllDataExporterConfigs({
              ...paginationState,
              fields: ['id', 'name', 'type', 'enabled'],
            })
          }
          updateItem={BackOfficeServices.updateDataExporterConfig}
          deleteItem={BackOfficeServices.deleteDataExporterConfig}
          createItem={BackOfficeServices.createDataExporterConfig}
          formComponent={NewExporterForm}
          stayAfterSave={true}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={(item) => item?.id}
          injectTable={(ref) => (this.table = ref)}
          export={true}
          kubernetesKind="events.otoroshi.io/DataExporter"
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

const KafkaExporterTryIt = ({ exporter }) => {
  const [status, setStatus] = useState('Not tested');
  const [timeout, setConnectionTimeout] = useState(15);

  return (
    <div>
      <div class="col-sm-12" role="button">
        <Collapse initCollapsed={false} label="Try it">
          <div className="row mb-3">
            <label className="col-xs-12 col-sm-2 col-form-label">Status</label>
            <div
              className="col-sm-10 "
              style={{
                color:
                  status === 'Successful'
                    ? 'var(--color-green)'
                    : status === 'Not tested'
                      ? '#f39c12'
                      : 'var(--color-red)',
                display: 'flex',
                alignItems: 'center',
                width: 'fit-content',
              }}
            >
              {status !== 'Successful' ? (
                <i className="fas fa-times-circle" />
              ) : (
                <i className="fas fa-check" />
              )}
              <span className="ms-1">{status}</span>
            </div>
          </div>
          <NumberInput
            label="Try it connection timeout"
            step="1"
            min={0}
            max={120}
            value={timeout}
            onChange={setConnectionTimeout}
          />
          <div className="row mb-3">
            <label className="col-xs-12 col-sm-2 col-form-label"></label>
            <div className="col-sm-10 d-flex">
              <FeedbackButton
                text="Test connection"
                icon={() => <i className="fas fa-hammer" />}
                onPress={() => {
                  setStatus('Processing ...');
                  return BackOfficeServices.kafkaDataExportertryIt({ ...exporter, timeout }).then(
                    (res) => {
                      if (res.status === 200) setStatus('Successful');
                      else res.json().then((err) => setStatus(err.error));
                    }
                  );
                }}
              />
            </div>
          </div>
        </Collapse>
      </div>
    </div>
  );
};

export class NewExporterForm extends Component {
  updateType = (type) => {
    BackOfficeServices.createNewDataExporterConfig(type).then((config) => {
      this.props.onChange({
        ...this.props.value,
        type,
        ...config,
        id: this.props.value.id || config.id,
      });
    });
  };

  data = () => {
    return this.props.value;
  };

  dataChange = (obj) => {
    if (obj.config && obj.config.clusterUri) {
      delete obj.config.clusterUri;
    }
    const ns = { ...this.props.value, ...obj };
    if (ns.config && ns.config.clusterUri) {
      delete ns.config.clusterUri;
    }
    this.props.onChange(ns);
  };

  testMatchAndProject = () => {
    window.wizard(
      'Test filtering and projection expressions',
      (ok, cancel) => (
        <TestMatchAndProjectModal
          ok={ok}
          cancel={cancel}
          originalFiltering={this.data().filtering}
          originalProjection={this.data().projection}
          updateExpressions={(filtering, projection) =>
            this.props.onChange({ ...this.props.value, filtering, projection })
          }
        />
      ),
      { additionalClass: 'modal-xl', style: { width: '100%' }, noCancel: true, okLabel: 'close' }
    );
  };

  render() {
    return (
      <>
        <Collapse initCollapsed={false} label="Location" lineEnd={true}>
          <Location
            tenant={this.data()._loc.tenant || 'default'}
            onChangeTenant={(v) =>
              this.dataChange({
                _loc: {
                  ...this.data()._loc,
                  tenant: v,
                },
              })
            }
            teams={this.data()._loc.teams || ['default']}
            onChangeTeams={(v) =>
              this.dataChange({
                _loc: {
                  ...this.data()._loc,
                  teams: v,
                },
              })
            }
          />
        </Collapse>
        <form className="form-horizontal">
          <SelectInput
            label="Type"
            placeholder="The type of exporter"
            value={this.data().type}
            onChange={(e) => this.updateType(e)}
            possibleValues={Object.keys(possibleExporterConfigFormValues).map((key) =>
              possibleExporterConfigFormValues[key].label
                ? { label: possibleExporterConfigFormValues[key].label, value: key }
                : { label: key, value: key }
            )}
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
          <ArrayInput
            label="Tags"
            value={this.data().tags}
            onChange={(e) => this.dataChange({ tags: e })}
          />
          <ObjectInput
            label="Metadata"
            value={this.data().metadata}
            onChange={(e) => this.dataChange({ metadata: e })}
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
            <div className="row mb-3">
              <label className="col-sm-2"></label>
              <div className="col-sm-10">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={this.testMatchAndProject}
                >
                  <i className="fas fa-vial" /> Test filtering and projection expressions
                </button>
              </div>
            </div>
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
              label="Exporter config"
            >
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
                  } else if (this.data().type === 'custommetrics') {
                    return this.dataChange({ config: config.custommetrics });
                  } else if (this.data().type === 'otlp-metrics') {
                    return this.dataChange({ config: config.custommetrics });
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
          {this.data().type === 'kafka' && <KafkaExporterTryIt exporter={this.props.value} />}
        </form>
      </>
    );
  }
}

const CustomMtlsChooser = ({ onChange, value, rawValue }) => {
  console.log(value);
  if (!['SASL_SSL', 'SSL'].includes(rawValue.securityProtocol)) return null;

  return (
    <div className="row mb-3">
      <label className="col-xs-12 col-sm-2 col-form-label">Custom TLS Settings</label>
      <div className="col-sm-10" style={{ display: 'flex' }}>
        <div style={{ display: 'flex', gap: '12px', flex: 1 }}>
          <ChooserButton
            isActive={value === true}
            onChange={onChange}
            value={true}
            label="Use Otoroshi certificates"
            style={{ minHeight: 'initial' }}
          />
          <ChooserButton
            isActive={typeof value === 'object' && Object.keys(value).length === 0}
            onChange={onChange}
            value={false}
            label="Use your own keystore/truststore"
            style={{ minHeight: 'initial' }}
          />
        </div>
      </div>
    </div>
  );
};

const SecurityProtocol = ({ onChange, ...props }) => {
  const protocols = [
    {
      value: 'PLAINTEXT',
      label: 'PLAINTEXT',
      description: '(Un-authenticated, non-encrypted channel)',
    },
    {
      value: 'SASL_PLAINTEXT',
      label: 'SASL_PLAINTEXT',
      description: '(SASL authenticated, non-encrypted channel)',
    },
    { value: 'SASL_SSL', label: 'SASL_SSL', description: '(SASL authenticated, SSL channel)' },
    { value: 'SSL', label: 'SSL channel' },
  ];
  return (
    <div className="row mb-3">
      <label className="col-xs-12 col-sm-2 col-form-label">Security Protocol</label>
      <div className="col-sm-10" style={{ display: 'flex' }}>
        <div style={{ display: 'flex', gap: '12px' }}>
          {protocols.map(({ value, label, description }) => {
            return (
              <ChooserButton
                key={value}
                isActive={value === props.value}
                value={value}
                onChange={onChange}
                label={label}
                description={description}
              />
            );
          })}
        </div>
      </div>
    </div>
  );
};

const ChooserButton = ({ isActive, value, onChange, label, description, style = {} }) => {
  return (
    <button
      type="button"
      className={`btn ${isActive ? 'btn-success' : 'btn-dark'}`}
      onClick={() => onChange(value)}
      style={{
        flexDirection: 'column',
        flex: 1,
        maxWidth: '200px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        maxHeight: '80px !important',
        minHeight: '80px',
        ...style,
      }}
    >
      <span>{label}</span>
      {description && <span style={{ fontSize: '.75em' }}>{description}</span>}
    </button>
  );
};

class CustomExporterForm extends Component {
  state = {
    value: this.props.rawValue,
    flow: [],
    schema: {},
  };

  componentDidMount() {
    this.update();
  }

  update = () => {
    fetch(`/bo/api/proxy/api/scripts/_list?type=exporter`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((values) => {
        const exp = values.find((v) => v.id === this.props.rawValue.ref);
        if (exp) {
          this.setState({
            flow: exp.configFlow,
            schema: exp.configSchema,
          });
        } else {
          this.setState({
            flow: [],
            schema: {},
          });
        }
      })
      .catch((e) => {
        console.log('error while fetching exporter metadata', e);
        this.setState({
          flow: [],
          schema: {},
        });
      });
  };

  componentDidUpdate(prevProps, prevState, snapshot) {
    if (prevProps.rawValue.ref !== this.props.rawValue.ref) {
      this.update();
    }
  }

  onChange = (v) => {
    this.setState({ value: v });
    this.props.rawOnChange(v);
  };

  render() {
    const flow = ['ref', ...this.state.flow, 'config'];
    const schema = {
      ...this.state.schema,
      ref: {
        type: 'select',
        props: {
          label: 'Exporter',
          valuesFrom: `/bo/api/proxy/api/scripts/_list?type=exporter`,
          transformer: (item) => ({ label: item.name, value: item.id, exporter: item }),
        },
      },
      config: {
        type: 'jsonobjectcode',
        props: {
          label: 'Raw exporter config.',
        },
      },
    };
    return (
      <Form
        {...this.props}
        schema={schema}
        flow={flow}
        value={this.props.rawValue}
        onChange={this.onChange}
      />
    );
  }
}

const possibleExporterConfigFormValues = {
  elastic: {
    flow: [
      'uris',
      'index',
      'type',
      'user',
      'password',
      'version',
      'maxBulkSize',
      'sendWorkers',
      'applyTemplate',
      'checkConnection',
      '>>>Index settings',
      'indexSettings.clientSide',
      'indexSettings.interval',
      'indexSettings.numberOfShards',
      'indexSettings.numberOfReplicas',
      '>>>TLS settings',
      'mtlsConfig.mtls',
      'mtlsConfig.loose',
      'mtlsConfig.trustAll',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
    ],
    schema: {
      uris: {
        type: 'array',
        props: { label: 'Cluster URIs', placeholder: 'Elastic cluster URI' },
      },
      index: {
        type: 'string',
        props: { label: 'Index', placeholder: 'Elastic index' },
      },
      type: {
        type: 'string',
        props: {
          label: 'Type',
          placeholder: 'Event type (not needed for elasticsearch above 6.x)',
        },
      },
      user: {
        type: 'string',
        props: { label: 'User', placeholder: 'Elastic User (optional)' },
      },
      password: {
        type: 'string',
        props: { label: 'Password', placeholder: 'Elastic password (optional)', type: 'password' },
      },
      applyTemplate: {
        type: 'bool',
        props: { label: 'Apply template', help: 'Automatically apply index template' },
      },
      version: {
        type: 'string',
        props: {
          label: 'Version',
          placeholder:
            'Elastic version (optional, if none provided it will be fetched from cluster)',
        },
      },
      checkConnection: {
        type: CheckElasticsearchConnection,
        props: { label: 'Check Connection' },
      },
      maxBulkSize: {
        type: 'number',
        props: {
          label: 'Max Bulk Size',
        },
      },
      sendWorkers: {
        type: 'number',
        props: {
          label: 'Sending threads',
        },
      },
      'indexSettings.numberOfShards': {
        type: 'number',
        props: {
          label: 'Number of shards',
        },
      },
      'indexSettings.numberOfReplicas': {
        type: 'number',
        props: {
          label: 'Number of replicas',
        },
      },
      'indexSettings.clientSide': {
        type: 'bool',
        props: { label: 'Client side temporal indexes handling' },
      },
      'indexSettings.interval': {
        type: 'select',
        display: (v) => tryOrFalse(() => v.indexSettings.clientSide),
        props: {
          label: 'One index per',
          possibleValues: [
            { label: 'Day', value: 'Day' },
            { label: 'Week', value: 'Week' },
            { label: 'Month', value: 'Month' },
            { label: 'Year', value: 'Year' },
          ],
        },
      },
      'mtlsConfig.mtls': {
        type: 'bool',
        props: { label: 'Custom TLS Settings' },
      },
      'mtlsConfig.loose': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TLS loose' },
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TLS loose' },
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
      'token',
      'username',
      'password',
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
      token: {
        type: 'string',
        props: {
          label: 'Pulsar access token',
          help: 'The token to access pulsar server (optional)',
        },
      },
      username: {
        type: 'string',
        props: {
          label: 'Pulsar access username',
          help: 'The username to access pulsar server (optional). Use it combined to password.',
        },
      },
      password: {
        type: 'string',
        props: {
          label: 'Pulsar access password',
          help: 'The password to access pulsar server (optional). Use it combined to username.',
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
      'topic',
      'securityProtocol',
      'saslConfig.username',
      'saslConfig.password',
      'saslConfig.mechanism',
      'mtlsConfig.mtls',
      'keyPass',
      'keystore',
      'truststore',
      'mtlsConfig.trustAll',
      'mtlsConfig.loose',
      'mtlsConfig.certs',
      'mtlsConfig.trustedCerts',
      'hostValidation',
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
      securityProtocol: <SecurityProtocol />,
      keyPass: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls && v.securityProtocol.includes('SSL')),
        props: {
          label: 'Kafka keypass',
          placeholder: 'secret',
          type: 'password',
          help: 'The keystore password if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      keystore: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls && v.securityProtocol.includes('SSL')),
        props: {
          label: 'Kafka keystore path',
          placeholder: '/home/bas/client.keystore.jks',
          help: 'The keystore path on the server if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      truststore: {
        type: 'string',
        display: (v) => tryOrTrue(() => !v.mtlsConfig.mtls && v.securityProtocol.includes('SSL')),
        props: {
          label: 'Kafka truststore path',
          placeholder: '/home/bas/client.truststore.jks',
          help: 'The truststore path on the server if you use a keystore/truststore to connect to Kafka cluster',
        },
      },
      'saslConfig.username': {
        type: 'string',
        display: (v) => tryOrTrue(() => v.securityProtocol.includes('SASL')),
        props: {
          label: 'SASL username',
          help: 'Kafka client user',
        },
      },
      'saslConfig.password': {
        type: 'string',
        display: (v) => tryOrTrue(() => v.securityProtocol.includes('SASL')),
        props: {
          label: 'SASL password',
          help: 'Kafka client password',
          type: 'password',
        },
      },
      'saslConfig.mechanism': {
        type: 'select',
        display: (v) =>
          tryOrTrue(() => ['SASL_PLAINTEXT', 'SASL_SSL'].includes(v.securityProtocol)),
        props: {
          label: 'SASL Mechanism',
          possibleValues: [
            { value: 'PLAIN', label: 'PLAIN' },
            //{value: 'GSSAPI', label: 'GSSAPI (Kerberos)' },
            { value: 'SCRAM-SHA-256', label: 'SCRAM-SHA-256' },
            { value: 'SCRAM-SHA-512', label: 'SCRAM-SHA-512' },
            // {value: 'OAUTHBEARER', label: 'OAUTHBEARER' },
          ],
        },
      },
      hostValidation: {
        type: 'bool',
        display: (v) =>
          tryOrTrue(() => v.mtlsConfig.mtls || ['SSL', 'SASL_SSL'].includes(v.securityProtocol)),
        props: {
          label: 'Hostname validation',
          help: 'Enabled TLS hostname validation',
        },
      },
      'mtlsConfig.mtls': <CustomMtlsChooser />,
      'mtlsConfig.trustAll': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'mtlsConfig.loose': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'loose' },
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
    flow: ['path', 'maxFileSize', 'maxNumberOfFile'],
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
      maxNumberOfFile: {
        type: 'number',
        props: {
          label: 'Max number of files',
          placeholder: 'Max number of existing files',
          suffix: 'files',
        },
      },
    },
  },
  s3: {
    flow: [
      'writeEvery',
      'maxFileSize',
      'maxNumberOfFile',
      '>>> S3 config.',
      'bucket',
      'endpoint',
      'region',
      'access',
      'secret',
      'key',
      'chunkSize',
      'v4auth',
      'acl',
    ],
    schema: {
      maxFileSize: {
        type: 'number',
        props: {
          label: 'Max file size',
          placeholder: 'Max size in bytes for a file',
          suffix: 'bytes',
        },
      },
      maxNumberOfFile: {
        type: 'number',
        props: {
          label: 'Max number of files',
          placeholder: 'Max number of existing files',
          suffix: 'files',
        },
      },
      bucket: {
        type: 'string',
        props: {
          label: 'S3 bucket name',
        },
      },
      endpoint: {
        type: 'string',
        props: {
          label: 'S3 endpoint',
        },
      },
      region: {
        type: 'string',
        props: {
          label: 'S3 region',
        },
      },
      access: {
        type: 'string',
        props: {
          label: 'S3 access key',
        },
      },
      secret: {
        type: 'string',
        props: {
          label: 'S3 Secret',
        },
      },
      key: {
        type: 'string',
        props: {
          label: 'File key',
        },
      },
      chunkSize: {
        type: 'number',
        props: {
          label: 'Chunk size',
        },
      },
      v4auth: {
        type: 'bool',
        props: {
          label: 'V4 Auth',
        },
      },
      writeEvery: {
        type: 'number',
        props: {
          label: 'Write every',
          suffix: 'millis.',
        },
      },
      acl: {
        type: 'string',
        props: {
          label: 'S3 file ACL',
        },
      },
    },
  },
  goreplays3: {
    flow: [
      's3.bucket',
      's3.endpoint',
      's3.region',
      's3.access',
      's3.secret',
      's3.key',
      's3.chunkSize',
      's3.v4auth',
      's3.writeEvery',
      's3.acl',
      'maxFileSize',
      'captureRequests',
      'captureResponses',
      'preferBackendRequest',
      'preferBackendResponse',
      'methods',
    ],
    schema: {
      maxFileSize: {
        type: 'number',
        props: {
          label: 'Max file size',
          placeholder: 'Max size in bytes for a file',
          suffix: 'bytes',
        },
      },
      's3.bucket': {
        type: 'string',
        props: {
          label: 'S3 bucket name',
        },
      },
      's3.endpoint': {
        type: 'string',
        props: {
          label: 'S3 endpoint',
        },
      },
      's3.region': {
        type: 'string',
        props: {
          label: 'S3 region',
        },
      },
      's3.access': {
        type: 'string',
        props: {
          label: 'S3 access key',
        },
      },
      's3.secret': {
        type: 'string',
        props: {
          label: 'S3 Secret',
        },
      },
      's3.key': {
        type: 'string',
        props: {
          label: 'File key',
        },
      },
      's3.chunkSize': {
        type: 'number',
        props: {
          label: 'Chunk size',
        },
      },
      's3.v4auth': {
        type: 'bool',
        props: {
          label: 'V4 Auth',
        },
      },
      's3.writeEvery': {
        type: 'number',
        props: {
          label: 'Write every',
          suffix: 'millis.',
        },
      },
      's3.acl': {
        type: 'string',
        props: {
          label: 'S3 file ACL',
        },
      },
      captureRequests: {
        type: 'bool',
        props: {
          label: 'Capture Requests',
        },
      },
      captureResponses: {
        type: 'bool',
        props: {
          label: 'Capture Responses',
        },
      },
      methods: {
        type: 'array',
        props: {
          label: 'HTTP methods',
          help: 'filter only the http methods you want to capture. If none specified, all will be captured !',
        },
      },
      preferBackendRequest: {
        type: 'bool',
        props: {
          label: 'Capture backend requests',
          help: 'instead on frontend requests',
        },
      },
      preferBackendResponse: {
        type: 'bool',
        props: {
          label: 'Capture backend responses',
          help: 'instead on frontend responses',
        },
      },
    },
  },
  goreplayfile: {
    flow: [
      'path',
      'maxFileSize',
      'captureRequests',
      'captureResponses',
      'preferBackendRequest',
      'preferBackendResponse',
      'methods',
    ],
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
      captureRequests: {
        type: 'bool',
        props: {
          label: 'Capture Requests',
        },
      },
      captureResponses: {
        type: 'bool',
        props: {
          label: 'Capture Responses',
        },
      },
      preferBackendRequest: {
        type: 'bool',
        props: {
          label: 'Capture backend requests',
          help: 'instead on frontend requests',
        },
      },
      preferBackendResponse: {
        type: 'bool',
        props: {
          label: 'Capture backend responses',
          help: 'instead on frontend responses',
        },
      },
      methods: {
        type: 'array',
        props: {
          label: 'HTTP methods',
          help: 'filter only the http methods you want to capture. If none specified, all will be captured !',
        },
      },
    },
  },
  console: {
    flow: [],
    schema: {},
  },
  custom: {
    flow: ['root'],
    schema: {
      root: {
        type: CustomExporterForm,
      },
    },
  },
  //custom: {
  //  flow: ['ref', 'config'],
  //  schema: {
  //    ref: {
  //      type: 'select',
  //      props: {
  //        label: 'Exporter',
  //        valuesFrom: `/bo/api/proxy/api/scripts/_list?type=exporter`,
  //        transformer: (item) => ({ label: item.name, value: item.id, exporter: item }),
  //      },
  //    },
  //    config: {
  //      type: 'jsonobjectcode',
  //      props: {
  //        label: 'Exporter config.',
  //      },
  //    },
  //  },
  //},
  metrics: {
    label: 'request routing metrics',
    flow: ['labels'],
    schema: {
      labels: {
        type: 'array_select',
        props: {
          creatable: true,
          label: 'Labels',
          placeholderKey: 'Choose a entry metric label',
          placeholderValue: 'Choose your destination label',
          valuesFrom: '/bo/api/proxy/api/events/_template?eventType=GatewayEvent',
          help: 'The selected properties from events and their projection',
          title: 'Properties of an event to retrieve and transform into metric labels',
          transformer: (a) => ({
            value: a,
            label: a,
          }),
        },
      },
    },
  },
  custommetrics: {
    flow: ['custommetrics'],
    schema: {
      custommetrics: {
        type: CustomMetrics,
      },
    },
  },
  wasm: {
    flow: ['wasm_ref', 'params'],
    schema: {
      wasm_ref: {
        type: 'select',
        props: {
          label: 'Wasm plugin',
          valuesFrom: `/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins`,
          transformer: (i) => ({ label: i.name, value: i.id }),
        },
      },
      one_by_one: {
        type: 'bool',
        props: {
          label: 'One by one',
        },
      },
      params: {
        type: 'jsonobjectcode',
        props: {
          label: 'Exporter config.',
        },
      },
    },
  },
  'otlp-logs': {
    flow: [
      'otlp.grpc',
      'otlp.gzip',
      'otlp.endpoint',
      'otlp.timeout',
      'otlp.client_cert',
      'otlp.trusted_cert',
    ],
    schema: {
      'otlp.grpc': {
        type: 'bool',
        props: {
          label: 'Use grpc',
        },
      },
      'otlp.gzip': {
        type: 'bool',
        props: {
          label: 'Use gzip',
        },
      },
      'otlp.endpoint': {
        type: 'string',
        props: {
          label: 'Endpoint',
        },
      },
      'otlp.timeout': {
        type: 'number',
        props: {
          label: 'Timeout',
          suffix: 'ms.',
        },
      },
      'otlp.client_cert': {
        type: 'select',
        props: {
          label: 'Client cert.',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'otlp.trusted_cert': {
        type: 'select',
        props: {
          label: 'Trusted cert.',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
  'otlp-metrics': {
    flow: [
      'custommetrics',
      'otlp.grpc',
      'otlp.gzip',
      'otlp.endpoint',
      'otlp.timeout',
      'otlp.client_cert',
      'otlp.trusted_cert',
    ],
    schema: {
      custommetrics: {
        type: CustomMetrics,
      },
      'otlp.grpc': {
        type: 'bool',
        props: {
          label: 'Use grpc',
        },
      },
      'otlp.gzip': {
        type: 'bool',
        props: {
          label: 'Use gzip',
        },
      },
      'otlp.endpoint': {
        type: 'string',
        props: {
          label: 'Endpoint',
        },
      },
      'otlp.timeout': {
        type: 'number',
        props: {
          label: 'Timeout',
          suffix: 'ms.',
        },
      },
      'otlp.client_cert': {
        type: 'select',
        props: {
          label: 'Client cert.',
          placeholder: 'Choose a client certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'otlp.trusted_cert': {
        type: 'select',
        props: {
          label: 'Trusted cert.',
          placeholder: 'Choose a trusted certificate',
          valuesFrom: '/bo/api/proxy/api/certificates',
          transformer: (a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
  syslog: {
    flow: [
      'tcp',
      'udp',
      'host',
      'port',
      'unix_socket',
      'connect_timeout',
      'tls.enabled',
      'tls.loose',
      'tls.trust_all',
      'tls.certs',
      'tls.trusted_certs',
    ],
    schema: {
      tcp: {
        type: 'bool',
        props: { label: 'tcp' },
      },
      udp: {
        type: 'bool',
        props: { label: 'udp' },
      },
      host: {
        type: 'string',
        props: { label: 'host' },
      },
      port: {
        type: 'number',
        props: { label: 'port' },
      },
      unix_socket: {
        type: 'bool',
        props: { label: 'unix socket' },
      },
      connect_timeout: {
        type: 'number',
        props: { label: 'connect timeout' },
      },
      'tls.enabled': {
        type: 'bool',
        props: { label: 'TLS' },
      },
      'tls.loose': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TLS loose' },
      },
      'tls.trust_all': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'tls.certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'tls.trusted_certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
  tcp: {
    flow: [
      'host',
      'port',
      'unix_socket',
      'connect_timeout',
      'tls.enabled',
      'tls.loose',
      'tls.trust_all',
      'tls.certs',
      'tls.trusted_certs',
    ],
    schema: {
      tcp: {
        type: 'bool',
        props: { label: 'tcp' },
      },
      udp: {
        type: 'bool',
        props: { label: 'udp' },
      },
      host: {
        type: 'string',
        props: { label: 'host' },
      },
      port: {
        type: 'number',
        props: { label: 'port' },
      },
      unix_socket: {
        type: 'bool',
        props: { label: 'unix socket' },
      },
      connect_timeout: {
        type: 'number',
        props: { label: 'connect timeout' },
      },
      'tls.enabled': {
        type: 'bool',
        props: { label: 'TLS' },
      },
      'tls.loose': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TLS loose' },
      },
      'tls.trust_all': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'tls.certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'tls.trusted_certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
  udp: {
    flow: [
      'host',
      'port',
      'unix_socket',
      'connect_timeout'
    ],
    schema: {
      tcp: {
        type: 'bool',
        props: { label: 'tcp' },
      },
      udp: {
        type: 'bool',
        props: { label: 'udp' },
      },
      host: {
        type: 'string',
        props: { label: 'host' },
      },
      port: {
        type: 'number',
        props: { label: 'port' },
      },
      unix_socket: {
        type: 'bool',
        props: { label: 'unix socket' },
      },
      connect_timeout: {
        type: 'number',
        props: { label: 'connect timeout' },
      },
      'tls.enabled': {
        type: 'bool',
        props: { label: 'TLS' },
      },
      'tls.loose': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TLS loose' },
      },
      'tls.trust_all': {
        type: 'bool',
        display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
        props: { label: 'TrustAll' },
      },
      'tls.certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          }),
        },
      },
      'tls.trusted_certs': {
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
                <span className="badge bg-success" style={{ minWidth: 63 }}>
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
};

class TestMatchAndProjectModal extends Component {
  state = {
    input: {},
    filtering: this.props.originalFiltering,
    projection: this.props.originalProjection,
    output: { message: 'no test yet' },
  };

  componentDidMount() {
    fetch('/bo/api/test_match_and_project_input', {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((r) => {
        this.setState({ input: r });
      });
  }

  test = () => {
    this.setState({ output: 'running test ...' }, () => {
      fetch('/bo/api/test_match_and_project', {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          input: this.state.input,
          match: this.state.filtering,
          projection: this.state.projection,
        }),
      })
        .then((r) => r.json())
        .then((r) => {
          if (r.matches) {
            this.setState({ output: r.projection });
          } else {
            this.setState({
              output: { message: 'The input document does not match the filtering expressions' },
            });
          }
        });
    });
  };

  copy = () => {
    this.props.updateExpressions(this.state.filtering, this.state.projection);
    // this.props.cancel();
  };

  render() {
    const height = (window.innerHeight * 0.4).toFixed(0);
    return (
      <>
        {/*<div className="modal-body">*/}
        <div
          style={{
            display: 'flex',
            flexDirection: 'row',
            width: '100%',
            height: '100%',
            marginTop: 30,
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', width: '50%', height: '100%' }}>
            <JsonObjectAsCodeInput
              label="Filtering"
              value={this.state.filtering}
              onChange={(e) => this.setState({ filtering: e })}
              height={`${height}px`}
            />
            <JsonObjectAsCodeInput
              label="Projection"
              value={this.state.projection}
              onChange={(e) => this.setState({ projection: e })}
              height={`${height}px`}
            />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', width: '50%', height: '100%' }}>
            <JsonObjectAsCodeInput
              label="Input"
              value={this.state.input}
              onChange={(e) => this.setState({ input: e })}
              height={`${height}px`}
            />
            <JsonObjectAsCodeInput
              label="Output"
              value={this.state.output}
              onChange={(e) => ''}
              height={`${height}px`}
            />
          </div>
        </div>
        <div
          style={{
            width: '100%',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}
        >
          <div className="btn-group">
            <button type="button" className="btn btn-success" onClick={this.test}>
              <i className="fas fa-play" /> run
            </button>
            <button type="button" className="btn btn-primary" onClick={this.copy}>
              <i className="fas fa-clipboard" /> update exporter expressions
            </button>
          </div>
        </div>
        {/*</div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            close
          </button>
        </div>*/}
      </>
    );
  }
}
