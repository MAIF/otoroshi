import React, { Component } from 'react';
import { ResetDBButton } from '../components/ResetDBButton';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Form, SelectInput } from '../components/inputs';
import moment from 'moment';

function shallowDiffers(a, b) {
  for (let i in a) if (!(i in b)) return true;
  for (let i in b) if (a[i] !== b[i]) return true;
  return false;
}

// TODO : multi webhooks
export class DangerZonePage extends Component {
  state = {
    readyToPush: false,
    value: {},
    originalValue: null,
    changed: false,
    sync: {
      host: '',
      port: '',
      password: '',
    },
    syncing: false,
    syncingError: null,
  };

  alertWebhooksFormSchema = {
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
  };

  analyticsWebhooksFormSchema = {
    url: {
      type: 'string',
      props: { label: 'Analytics webhook URL', placeholder: 'URL of the webhook target' },
    },
    headers: {
      type: 'object',
      props: {
        label: 'Webhook Headers',
        placeholderKey: 'Name of the header',
        placeholderValue: 'Value of the header',
      },
    },
  };

  elasticConfigFormFlow = ['clusterUri', 'index', 'type', 'user', 'password'];

  elasticConfigFormSchema = {
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
      props: { label: 'Password', placeholder: 'Elastic password (optional)' },
    },
  };

  analyticsEventsUrlFromSchema = {
    url: {
      type: 'string',
      props: { label: 'Analytics source URL', placeholder: 'URL of the events source' },
    },
    headers: {
      type: 'object',
      props: {
        label: 'URL Headers',
        placeholderKey: 'Name of the header',
        placeholderValue: 'Value of the header',
      },
    },
  };

  webhooksFormFlow = ['url', 'headers'];

  formSchema = {
    'ipFiltering.whitelist': {
      type: 'array',
      props: {
        label: 'IP Whitelist',
        placeholder: 'IP address that can access Otoroshi',
        help: 'Only IP addresses that will be able to access Otoroshi exposed services',
      },
    },
    'ipFiltering.blacklist': {
      type: 'array',
      props: {
        label: 'IP Blacklist',
        placeholder: 'IP address that cannot access the Otoroshi',
        help: 'IP addresses that will be refused to access Otoroshi exposed services',
      },
    },
    throttlingQuota: {
      type: 'number',
      props: {
        label: 'Global throttling',
        placeholder: `Number of requests per seconds`,
        help: `The max. number of requests allowed per seconds globally on Otoroshi`,
      },
    },
    perIpThrottlingQuota: {
      type: 'number',
      props: {
        label: 'Throttling per IP',
        placeholder: `Number of requests per seconds per IP`,
        help: `The max. number of requests allowed per seconds per IP address globally on Otoroshi`,
      },
    },
    analyticsEventsUrl: {
      type: Form,
      props: { flow: this.webhooksFormFlow, schema: this.analyticsEventsUrlFromSchema },
    },
    analyticsWebhook: {
      type: Form,
      props: { flow: this.webhooksFormFlow, schema: this.analyticsWebhooksFormSchema },
    },
    alertsWebhook: {
      type: Form,
      props: { flow: this.webhooksFormFlow, schema: this.alertWebhooksFormSchema },
    },
    elasticReadsConfig: {
      type: Form,
      props: {
        flow: this.elasticConfigFormFlow,
        schema: this.elasticConfigFormSchema,
      },
    },
    elasticWritesConfig: {
      type: Form,
      props: {
        flow: this.elasticConfigFormFlow,
        schema: this.elasticConfigFormSchema,
      },
    },
    alertsEmails: {
      type: 'array',
      props: {
        label: 'Alert emails',
        placeholder: 'Email address to receive alerts',
        help: 'Every email address will be notified with a summary of Otoroshi alerts',
      },
    },
    lines: {
      type: 'array',
      props: {
        label: 'Lines',
        placeholder: 'Name of the line',
        help: 'All the environments availables',
      },
    },
    endlessIpAddresses: {
      type: 'array',
      props: {
        label: 'Endless HTTP Responses',
        placeholder: 'Target IP address',
        help: 'IP addresses for which each request will return around 128 Gb of 0s',
      },
    },
    u2fLoginOnly: {
      type: 'bool',
      props: {
        label: 'No OAuth login for BackOffice',
        placeholder: '--',
        help: 'Forces admins to login only with user/password or user/password/u2F device',
      },
    },
    apiReadOnly: {
      type: 'bool',
      props: {
        label: 'API Read Only',
        placeholder: '--',
        help:
          'Freeze the Otoroshi datastore in read only mode. Only people with access to the actual underlying datastore will be able to disable this.',
      },
    },
    useCircuitBreakers: {
      type: 'bool',
      props: {
        label: 'Use circuit breakers',
        placeholder: '--',
        help: 'Use circuit breaker on all services',
      },
    },
    streamEntityOnly: {
      type: 'bool',
      props: {
        label: 'Use HTTP streaming',
        placeholder: '--',
        help: 'Only use HTTP streaming for response, no additional chunking',
      },
    },
    autoLinkToDefaultGroup: {
      type: 'bool',
      props: {
        label: 'Auto link default',
        placeholder: '--',
        help: 'When no group is specified on a service, it will be assigned to default one',
      },
    },
    limitConcurrentRequests: {
      type: 'bool',
      props: {
        label: 'Limit conc. req.',
        placeholder: '--',
        help:
          'Limit the number of concurrent request processed by Otoroshi to a certain amount. Highly recommended for resilience.',
      },
    },
    middleFingers: {
      type: 'bool',
      props: {
        label: 'Digitus medius',
        placeholder: '--',
        help: 'Use middle finger emoji (🖕) as a response character for endless HTTP responses.',
      },
    },
    maxConcurrentRequests: {
      type: 'number',
      props: {
        label: 'Max conc. req.',
        placeholder: '--',
        help: 'Maximum number of concurrent request processed by otoroshi.',
        suffix: 'requests',
      },
    },
    maxHttp10ResponseSize: {
      type: 'number',
      props: {
        label: 'Max HTTP/1.0 resp. size',
        placeholder: '--',
        help:
          'Maximum size of an HTTP/1.0 response in bytes. After this limit, response will be cut and sent as is. The best value here should satisfy (maxConcurrentRequests * maxHttp10ResponseSize) < process.memory for worst case scenario.',
        suffix: 'bytes',
      },
    },
    maxLogsSize: {
      type: 'number',
      props: {
        label: 'Max local events',
        placeholder: '--',
        help: 'Maximum number of events stored.',
        suffix: 'events',
      },
    },
    backOfficeAuthRef: {
      type: SelectInput,
      props: {
        label: 'Backoffice auth. config',
        valuesFrom: '/bo/api/proxy/api/auths',
        transformer: a => ({ value: a.id, label: a.name }),
        help: '...',
      },
    },
    'mailGunSettings.apiKey': {
      type: 'string',
      props: {
        label: 'Mailgun Api Key',
        placeholder: 'Mailgun Api Key',
      },
    },
    'mailGunSettings.domain': {
      type: 'string',
      props: {
        label: 'Mailgun domain',
        placeholder: 'Mailgun domain',
      },
    },
    'cleverSettings.consumerKey': {
      type: 'string',
      props: {
        label: 'CleverCloud consumer key',
        placeholder: 'CleverCloud consumer key',
      },
    },
    'cleverSettings.consumerSecret': {
      type: 'string',
      props: {
        label: 'CleverCloud consumer secret',
        placeholder: 'CleverCloud consumer secret',
      },
    },
    'cleverSettings.token': {
      type: 'string',
      props: {
        label: 'OAuth Token',
        placeholder: 'OAuth Token',
      },
    },
    'cleverSettings.secret': {
      type: 'string',
      props: {
        label: 'OAuth Secret',
        placeholder: 'OAuth Secret',
      },
    },
    'cleverSettings.orgaId': {
      type: 'string',
      props: {
        label: 'CleverCloud orga. Id',
        placeholder: 'CleverCloud orga. Id',
      },
    },
    'kafkaConfig.servers': {
      type: 'array',
      props: {
        label: 'Kafka Servers',
        placeholder: '127.0.0.1:9092',
        help: 'The list of servers to contact to connect the Kafka client with the Kafka cluster',
      },
    },
    'kafkaConfig.keyPass': {
      type: 'string',
      props: {
        label: 'Kafka keypass',
        placeholder: 'secret',
        help: 'The keystore password if you use a keystore/truststore to connect to Kafka cluster',
      },
    },
    'kafkaConfig.keystore': {
      type: 'string',
      props: {
        label: 'Kafka keystore path',
        placeholder: '/home/bas/client.keystore.jks',
        help:
          'The keystore path on the server if you use a keystore/truststore to connect to Kafka cluster',
      },
    },
    'kafkaConfig.truststore': {
      type: 'string',
      props: {
        label: 'Kafka truststore path',
        placeholder: '/home/bas/client.truststore.jks',
        help:
          'The truststore path on the server if you use a keystore/truststore to connect to Kafka cluster',
      },
    },
    'kafkaConfig.alertsTopic': {
      type: 'string',
      props: {
        label: 'Kafka alerts topic',
        placeholder: 'otoroshi-alerts',
        help: 'The topic on which Otoroshi alerts will be sent',
      },
    },
    'kafkaConfig.analyticsTopic': {
      type: 'string',
      props: {
        label: 'Kafka analytics topic',
        placeholder: 'otoroshi-analytics',
        help: 'The topic on which Otoroshi analytics will be sent',
      },
    },
    'kafkaConfig.auditTopic': {
      type: 'string',
      props: {
        label: 'Kafka audits topic',
        placeholder: 'otoroshi-audits',
        help: 'The topic on which Otoroshi audits will be sent',
      },
    },
    'statsdConfig.datadog': {
      type: 'bool',
      props: {
        label: 'Datadog agent',
        placeholder: '--',
        help: 'The StatsD agent is a Datadog agent',
      },
    },
    'statsdConfig.host': {
      type: 'string',
      props: {
        label: 'StatsD agent host',
        placeholder: 'localhost',
        help: 'The host on which StatsD agent is listening',
      },
    },
    'statsdConfig.port': {
      type: 'number',
      props: {
        label: 'StatsD agent port',
        placeholder: '8125',
        help: 'The port on which StatsD agent is listening (default is 8125)',
      },
    },
    backOfficeAuthButtons: {
      type: BackOfficeAuthButtons,
      props: {},
    },
  };

  formFlow = [
    '<<<Misc. Settings',
    'u2fLoginOnly',
    'apiReadOnly',
    'streamEntityOnly',
    'autoLinkToDefaultGroup',
    'useCircuitBreakers',
    'middleFingers',
    'limitConcurrentRequests',
    'maxConcurrentRequests',
    'maxHttp10ResponseSize',
    'maxLogsSize',
    'lines',
    '>>>IP address filtering settings',
    'ipFiltering.whitelist',
    'ipFiltering.blacklist',
    'endlessIpAddresses',
    '>>>Quotas settings',
    'throttlingQuota',
    'perIpThrottlingQuota',
    '>>>Analytics: Webhooks',
    'analyticsWebhook',
    '>>>Analytics: Elastic cluster (write)',
    'elasticWritesConfig',
    '>>>Analytics: Elastic dashboard datasource (read)',
    'elasticReadsConfig',
    '>>>Analytics: Kafka',
    'kafkaConfig.servers',
    'kafkaConfig.keyPass',
    'kafkaConfig.keystore',
    'kafkaConfig.truststore',
    'kafkaConfig.alertsTopic',
    'kafkaConfig.analyticsTopic',
    'kafkaConfig.auditTopic',
    '>>>Alerts settings',
    'alertsWebhook',
    'alertsEmails',
    '>>>Statsd settings',
    'statsdConfig.datadog',
    'statsdConfig.host',
    'statsdConfig.port',
    '>>>Backoffice auth. settings',
    'backOfficeAuthRef',
    'backOfficeAuthButtons',
    '>>>Mailgun settings',
    'mailGunSettings.apiKey',
    'mailGunSettings.domain',
    '>>>CleverCloud settings',
    'cleverSettings.consumerKey',
    'cleverSettings.consumerSecret',
    'cleverSettings.token',
    'cleverSettings.secret',
    'cleverSettings.orgaId',
  ];

  syncSchema = {
    host: {
      type: 'string',
      props: { label: 'Master host', placeholder: 'xxxxx-redis.services.clever-cloud.com' },
    },
    port: { type: 'number', props: { label: 'Master port', placeholder: '4242' } },
    password: { type: 'string', props: { label: 'Master password', type: 'password' } },
  };

  syncFlow = ['host', 'port', 'password'];

  componentDidMount() {
    this.props.setTitle(`Danger Zone`);
    BackOfficeServices.getGlobalConfig().then(value =>
      this.setState({ value, originalValue: value })
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
    BackOfficeServices.updateGlobalConfig(this.state.value).then(() => {
      this.setState({ originalValue: this.state.value, changed: false });
    });
  };

  updateState = raw => {
    const value = {
      ...raw,
      analyticsWebhooks: [raw.analyticsWebhook],
      elasticWritesConfigs: [raw.elasticWritesConfig],
      alertsWebhooks: [raw.alertsWebhook],
    };
    delete value.analyticsWebhook;
    delete value.elasticWritesConfig;
    delete value.alertsWebhook;
    this.setState({ value, changed: shallowDiffers(this.state.originalValue, value) });
  };

  getValue = () => {
    const value = { ...this.state.value };
    value.analyticsWebhook = (value.analyticsWebhooks || [])[0];
    value.elasticWritesConfig = (value.elasticWritesConfigs || [])[0];
    value.alertsWebhook = (value.alertsWebhooks || [])[0];
    delete value.alertsWebhooks;
    delete value.elasticWritesConfigs;
    delete value.analyticsWebhooks;
    return value;
  };

  sync = () => {
    this.setState({ syncing: true });
    BackOfficeServices.syncWithMaster(this.state.sync).then(
      d => this.setState({ syncing: false }),
      e => this.setState({ syncingError: e, syncing: false })
    );
  };

  panicMode = e => {
    if (e && e.preventDefault) e.preventDefault();
    confirm('Are you sure you want to enable panic mode ?');
    confirm('Are you really sure ?');
    BackOfficeServices.panicMode().then(() => {
      window.location.href = '/';
    });
  };

  fullExport = e => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.fetchOtoroshi().then(otoroshi => {
      const json = JSON.stringify(otoroshi, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.download = `${window.__isDev ? 'dev-' : ''}otoroshi-${moment().format(
        'YYYY-MM-DD-HH-mm-ss'
      )}.json`;
      a.href = url;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => document.body.removeChild(a), 300);
    });
  };

  importData = e => {
    if (e && e.preventDefault()) e.preventDefault();
    if (
      confirm(
        'Importing will erase all existing data in the datastore.\n You will be logged out at the end of the import.\n You may want to export your data before doing that.\n Are you sure you want to do that ?'
      ) &&
      confirm('Really sure ?')
    ) {
      const input = document.querySelector('input[type="file"]');
      const data = new FormData();
      data.append('file', input.files[0]);
      return fetch('/bo/api/proxy/api/import', {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
        body: input.files[0],
      }).then(r => window.location.reload(), e => console.log(e));
    }
  };

  readyToPush = e => {
    this.setState({ readyToPush: e.target.files[0].name });
  };

  render() {
    if (window.__apiReadOnly) return null;
    if (this.state.value === null) return null;
    const propsDisabled = { disabled: true };
    if (this.state.changed) {
      delete propsDisabled.disabled;
    }
    return (
      <div>
        <div className="row">
          <div className="form-group btnsService">
            <div className="col-md-10">
              <div className="btn-group pull-right">
                <button
                  title="Add item"
                  className="btn btn-success"
                  type="button"
                  onClick={this.saveGlobalConfig}
                  {...propsDisabled}>
                  <i className="glyphicon glyphicon-hdd" />
                </button>
              </div>
            </div>
          </div>
        </div>
        <Form
          value={this.getValue()}
          onChange={this.updateState}
          flow={this.formFlow}
          schema={this.formSchema}
          style={{ marginTop: 50 }}
        />
        <hr />
        <form className="form-horizontal">
          <input
            type="file"
            name="export"
            id="export"
            className="inputfile"
            ref={ref => (this.fileUpload = ref)}
            style={{ display: 'none' }}
            onChange={this.readyToPush}
          />
          {!this.state.readyToPush && (
            <div className="form-group">
              <label className="col-sm-2 control-label" />
              <div className="col-sm-10">
                <label
                  htmlFor="export"
                  className="fake-inputfile"
                  style={{
                    borderRadius: 5,
                    border: '1px solid #3e8f3e',
                    paddingLeft: 12,
                    paddingRight: 12,
                    paddingTop: 6,
                    paddingBottom: 6,
                    cursor: 'pointer',
                  }}>
                  <i className="glyphicon glyphicon-file" /> Recover from a full export file
                </label>
              </div>
            </div>
          )}
          {this.state.readyToPush && (
            <div className="form-group">
              <label className="col-sm-2 control-label" />
              <div className="col-sm-10">
                <button type="button" className="btn btn-danger" onClick={this.importData}>
                  <i className="glyphicon glyphicon-import" /> Flush DataStore & Import file '{
                    this.state.readyToPush
                  }'
                </button>
              </div>
            </div>
          )}
        </form>
        <hr />
        <form className="form-horizontal">
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <button type="button" className="btn btn-success" onClick={this.fullExport}>
                <i className="glyphicon glyphicon-export" /> Full export
              </button>
              <button
                type="button"
                className="btn btn-danger"
                style={{ marginLeft: 5, marginRight: 5 }}
                onClick={this.panicMode}>
                <i className="glyphicon glyphicon-fire" /> Enable Panic Mode
              </button>
            </div>
          </div>
        </form>
        <hr />
      </div>
    );
  }
}

class BackOfficeAuthButtons extends Component {
  render() {
    return (
      <div className="form-group">
        <label className="col-xs-12 col-sm-2 control-label" />
        <div className="col-sm-10">
          {!this.props.rawValue.backOfficeAuthRef && (
            <a href={`/bo/dashboard/auth-configs/add`} className="btn btn-sm btn-primary">
              <i className="glyphicon glyphicon-plus" /> Create a new auth. config.
            </a>
          )}
          {this.props.rawValue.backOfficeAuthRef && (
            <a
              href={`/bo/dashboard/auth-configs/edit/${this.props.rawValue.backOfficeAuthRef}`}
              className="btn btn-sm btn-success">
              <i className="glyphicon glyphicon-edit" /> Edit the auth. config.
            </a>
          )}
          <a href={`/bo/dashboard/auth-configs`} className="btn btn-sm btn-primary">
            <i className="glyphicon glyphicon-link" /> all auth. config.
          </a>
        </div>
      </div>
    );
  }
}
