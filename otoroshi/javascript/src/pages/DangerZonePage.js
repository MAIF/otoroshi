import React, { Component, Suspense } from 'react';
import { ResetDBButton } from '../components/ResetDBButton';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Form, SelectInput, BooleanInput } from '../components/inputs';
import { Proxy } from '../components/Proxy';
import { Scripts } from '../components/Scripts';
import moment from 'moment';

import deepSet from 'set-value';
import _ from 'lodash';
import Select from 'react-select';
import Creatable from 'react-select/lib/Creatable';

function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}

const CodeInput = React.lazy(() => Promise.resolve(require('../components/inputs/CodeInput')));

function shallowDiffers(a, b) {
  for (let i in a) if (!(i in b)) return true;
  for (let i in b) if (a[i] !== b[i]) return true;
  return false;
}

class Geolocation extends Component {
  ipStackFormFlow = ['enabled', 'apikey', 'timeout'];
  maxmindFormFlow = ['enabled', 'path'];
  ipStackForm = {
    enabled: {
      type: 'bool',
      props: {
        label: 'Enabled',
      },
    },
    apikey: {
      type: 'string',
      props: {
        label: 'IpStack api key',
        placeholder: 'IpStack api key',
      },
    },
    timeout: {
      type: 'number',
      props: {
        label: 'IpStack timeout',
        placeholder: 'IpStack timeout',
      },
    },
  };
  maxmindForm = {
    enabled: {
      type: 'bool',
      props: {
        label: 'Enabled',
      },
    },
    path: {
      type: 'string',
      props: {
        label: 'Maxmind db file path',
        placeholder: 'Maxmind db file path',
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
              case 'none':
                this.props.onChange({
                  type: 'none',
                });
                break;
              case 'ipstack':
                this.props.onChange({
                  type: 'ipstack',
                  enabled: false,
                  apikey: 'xxxxxx',
                });
                break;
              case 'maxmind':
                this.props.onChange({
                  type: 'maxmind',
                  enabled: false,
                  path: 'xxxxxx',
                });
                break;
            }
          }}
          possibleValues={[
            { label: 'None', value: 'none' },
            { label: 'IpStack', value: 'ipstack' },
            { label: 'MaxMind', value: 'maxmind' },
          ]}
          help="..."
        />
        {type === 'none' && null}
        {type === 'ipstack' && (
          <Form
            value={settings}
            onChange={this.props.onChange}
            flow={this.ipStackFormFlow}
            schema={this.ipStackForm}
            style={{ marginTop: 5 }}
          />
        )}
        {type === 'maxmind' && (
          <>
            <Form
              value={settings}
              onChange={this.props.onChange}
              flow={this.maxmindFormFlow}
              schema={this.maxmindForm}
              style={{ marginTop: 5 }}
            />
          </>
        )}
      </div>
    );
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
    console.log(settings);
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
              case 'sendgrid':
                this.props.onChange({
                  type: 'sendgrid',
                  apiKey: '',
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
            { label: 'None', value: 'none' },
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
            schema={this.sendgridSchema}
            style={{ marginTop: 5 }}
          />
        )}
      </div>
    );
  }
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

  elasticConfigFormFlow = [
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
  ];

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
  };

  webhooksFormFlow = [
    'url',
    'headers',
    'mtlsConfig.mtls',
    'mtlsConfig.loose',
    'mtlsConfig.trustAll',
    'mtlsConfig.certs',
    'mtlsConfig.trustedCerts',
  ];

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
    elasticReadsConfig: {
      type: Form,
      props: {
        flow: this.elasticConfigFormFlow,
        schema: this.elasticConfigFormSchema,
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
    maintenanceMode: {
      type: 'bool',
      props: {
        label: 'Maintenance mode',
        placeholder: '--',
        help: 'Pass every otoroshi service in maintenance mode',
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
    logAnalyticsOnServer: {
      type: 'bool',
      props: {
        label: 'Log analytics on servers',
        placeholder: '--',
        help: 'All analytics will be logged on the servers',
      },
    },
    useAkkaHttpClient: {
      type: 'bool',
      props: {
        label: 'Use new http client as the default Http client',
        placeholder: '--',
        help: 'All http calls will use the new http client client by default',
      },
    },
    enableEmbeddedMetrics: {
      type: 'bool',
      props: {
        label: 'Enable live metrics',
        placeholder: '--',
        help:
          'Enable live metrics in the Otoroshi cluster. Performs a lot of writes in the datastore',
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
    'letsEncryptSettings.enabled': {
      type: 'bool',
      props: {
        label: 'Enabled',
      },
    },
    'letsEncryptSettings.server': {
      type: 'string',
      props: {
        label: 'Server URL',
      },
    },
    'letsEncryptSettings.emails': {
      type: 'array',
      props: {
        label: 'Email addresses',
      },
    },
    'letsEncryptSettings.contacts': {
      type: 'array',
      props: {
        label: 'Contact URLs',
      },
    },
    'letsEncryptSettings.publicKey': {
      type: 'text',
      props: {
        label: 'Public Key',
        style: { fontFamily: 'monospace' },
      },
    },
    'letsEncryptSettings.privateKey': {
      type: 'text',
      props: {
        label: 'Private Key',
        style: { fontFamily: 'monospace' },
      },
    },
    'mailGunSettings.eu': {
      type: 'bool',
      props: {
        label: 'EU tenant',
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
    'proxies.alertEmails': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.eventsWebhooks': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.clevercloud': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.services': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.auth': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.authority': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.jwk': { type: Proxy, props: { showNonProxyHosts: true } },
    'proxies.elastic': { type: Proxy, props: { showNonProxyHosts: true } },
    geolocationSettings: { type: Geolocation },
    'userAgentSettings.enabled': {
      type: 'bool',
      props: {
        label: 'User-Agent extraction',
        placeholder: '--',
        help: 'Allow user-agent details extraction. Can have impact on consumed memory.',
      },
    },
    'scripts.enabled': {
      type: 'bool',
      props: {
        label: 'Global plugins enabled',
        help: '...',
      },
    },
    scripts: {
      type: GlobalScripts,
      props: {},
    },
    'autoCert.enabled': {
      type: 'bool',
      props: {
        label: 'Enabled',
        placeholder: '--',
        help: 'Generate certificates on the fly when they not exist',
      },
    },
    'autoCert.replyNicely': {
      type: 'bool',
      props: {
        label: 'Reply Nicely',
        placeholder: '--',
        help: 'When not allowed domain name, accept connection and display a nice error message',
      },
    },
    'autoCert.allowed': {
      type: 'array',
      props: {
        label: 'Allowed domains',
        placeholder: 'domain name',
        help: '...',
      },
    },
    'autoCert.notAllowed': {
      type: 'array',
      props: {
        label: 'Not allowed domains',
        placeholder: 'domain name',
        help: '...',
      },
    },
    'autoCert.caRef': {
      type: 'select',
      props: {
        label: 'CA',
        placeholder: 'Selet a CA certificate',
        help: '...',
        valuesFrom: '/bo/api/proxy/api/certificates?ca=true',
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
    'tlsSettings.randomIfNotFound': {
      type: 'bool',
      props: {
        label: 'Use random cert.',
        placeholder: '--',
        help: 'Use the first available cert none matches the current domain',
      },
    },
    'tlsSettings.defaultDomain': {
      type: 'string',
      props: {
        label: 'Default domain',
        placeholder: '--',
        help: 'When the SNI domain cannot be found, this one will be used to find the matching certificate',
      },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
  };

  formFlow = [
    '<<<Misc. Settings',
    'maintenanceMode',
    'u2fLoginOnly',
    'apiReadOnly',
    // 'streamEntityOnly',
    'autoLinkToDefaultGroup',
    'useCircuitBreakers',
    'logAnalyticsOnServer',
    'useAkkaHttpClient',
    'enableEmbeddedMetrics',
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
    '>>>Analytics: Elastic dashboard datasource (read)',
    'elasticReadsConfig',
    '>>>Statsd settings',
    'statsdConfig.datadog',
    'statsdConfig.host',
    'statsdConfig.port',
    '>>>Backoffice auth. settings',
    'backOfficeAuthRef',
    'backOfficeAuthButtons',
    ">>>Let's encrypt settings",
    'letsEncryptSettings.enabled',
    'letsEncryptSettings.server',
    'letsEncryptSettings.emails',
    'letsEncryptSettings.contacts',
    'letsEncryptSettings.publicKey',
    'letsEncryptSettings.privateKey',
    '>>>CleverCloud settings',
    'cleverSettings.consumerKey',
    'cleverSettings.consumerSecret',
    'cleverSettings.token',
    'cleverSettings.secret',
    'cleverSettings.orgaId',
    '>>>Global scripts',
    'scripts',
    '>>>Proxies',
    '-- Proxy for alert emails (mailgun)',
    'proxies.alertEmails',
    '-- Proxy for alert webhooks',
    'proxies.eventsWebhooks',
    '-- Proxy for Clever-Cloud API access',
    'proxies.clevercloud',
    '-- Proxy for services access',
    'proxies.services',
    '-- Proxy for auth. access (OAuth, OIDC)',
    'proxies.auth',
    '-- Proxy for client validators',
    'proxies.authority',
    '-- Proxy for JWKS access',
    'proxies.jwk',
    '-- Proxy for elastic access',
    'proxies.elastic',
    '>>>User-Agent extraction settings',
    'userAgentSettings.enabled',
    '>>>Geolocation extraction settings',
    'geolocationSettings',
    '>>>Tls Settings',
    'tlsSettings.randomIfNotFound',
    'tlsSettings.defaultDomain',
    '>>>Auto Generate Certificates',
    'autoCert.enabled',
    'autoCert.replyNicely',
    'autoCert.caRef',
    'autoCert.allowed',
    'autoCert.notAllowed',
    '>>>Global metadata',
    'metadata'
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
    const value = {...raw};
    this.setState({ value, changed: shallowDiffers(this.state.originalValue, value) });
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
    window.newConfirm('Are you sure you want to enable panic mode ?').then(ok => {
      window.newConfirm('Are you really sure ?').then(ok2 => {
        if (ok && ok2) {
          BackOfficeServices.panicMode().then(() => {
            window.location.href = '/';
          });
        }
      });
    });
  };

  fullExport = e => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.fetchOtoroshi('application/json').then(otoroshi => {
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

  fullExportNdJson = e => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.fetchOtoroshi('application/x-ndjson').then(otoroshi => {
      const blob = new Blob([otoroshi], { type: 'application/x-ndjson' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.download = `${window.__isDev ? 'dev-' : ''}otoroshi-${moment().format(
        'YYYY-MM-DD-HH-mm-ss'
      )}.ndjson`;
      a.href = url;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => document.body.removeChild(a), 300);
    });
  };

  importData = e => {
    if (e && e.preventDefault()) e.preventDefault();

    window
      .newConfirm(
        'Importing will erase all existing data in the datastore.\n You will be logged out at the end of the import.\n You may want to export your data before doing that.\n Are you sure you want to do that ?'
      )
      .then(ok => {
        window.newConfirm('Really sure ?').then(ok2 => {
          if (ok && ok2) {
            const input = document.querySelector('input[type="file"]');
            const data = new FormData();
            data.append('file', input.files[0]);
            return fetch('/bo/api/proxy/api/import', {
              method: 'POST',
              credentials: 'include',
              headers: {
                Accept: 'application/json',
                'X-Content-Type':
                  input.files[0].name.indexOf('ndjson') > -1
                    ? 'application/x-ndjson'
                    : 'application/json',
              },
              body: input.files[0],
            }).then(
              r => window.location.reload(),
              e => console.log(e)
            );
          }
        });
      });
  };

  readyToPush = e => {
    this.setState({ readyToPush: e.target.files[0].name });
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
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
          value={this.state.value}
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
                  <i className="glyphicon glyphicon-import" /> Flush DataStore & Import file '
                  {this.state.readyToPush}'
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
              <button type="button" className="btn btn-success" onClick={this.fullExportNdJson}>
                <i className="glyphicon glyphicon-export" /> Full export (ndjson)
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

class GlobalScripts extends Component {
  changeTheValue = (name, value) => {
    const cloned = _.cloneDeep(this.props.value);
    const newCloned = deepSet(cloned, name, value);
    this.props.onChange(newCloned);
  };
  render() {
    const config = this.props.value || {
      transformersRefs: [],
      transformersConfig: {},
      validatorRefs: [],
      validatorConfig: {},
      preRouteRefs: [],
      preRouteConfig: {},
      sinkRefs: [],
      sinkConfig: {},
      jobRefs: [],
      jobConfig: {},
    };
    return (
      <>
        <BooleanInput
          label="Enabled"
          value={config.enabled}
          help="Global scripts enabled"
          onChange={v => this.changeTheValue('enabled', v)}
        />
        <Scripts
          label="Jobs"
          refs={config.jobRefs}
          type="job"
          onChange={e => this.changeTheValue('jobRefs', e)}
          config={config.jobConfig}
          onChangeConfig={e => this.changeTheValue('jobConfig', e)}
        />
        <div className="form-group">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Jobs configuration"
              mode="json"
              value={JSON.stringify(config.jobConfig, null, 2)}
              onChange={e => this.changeTheValue('jobConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Request sinks"
          refs={config.sinkRefs}
          type="sink"
          onChange={e => this.changeTheValue('sinkRefs', e)}
          config={config.sinkConfig}
          onChangeConfig={e => this.changeTheValue('sinkConfig', e)}
        />
        <div className="form-group">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Request sinks configuration"
              mode="json"
              value={JSON.stringify(config.sinkConfig, null, 2)}
              onChange={e => this.changeTheValue('sinkConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Pre-routes"
          refs={config.preRouteRefs}
          type="preroute"
          onChange={e => this.changeTheValue('preRouteRefs', e)}
          config={config.preRouteConfig}
          onChangeConfig={e => this.changeTheValue('preRouteConfig', e)}
        />
        <div className="form-group">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Pre-routes configuration"
              mode="json"
              value={JSON.stringify(config.preRouteConfig, null, 2)}
              onChange={e => this.changeTheValue('preRouteConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Access validators"
          refs={config.validatorRefs}
          type="validator"
          onChange={e => this.changeTheValue('validatorRefs', e)}
          config={config.validatorConfig}
          onChangeConfig={e => this.changeTheValue('validatorConfig', e)}
        />
        <div className="form-group">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Access validators configuration"
              mode="json"
              value={JSON.stringify(config.validatorConfig, null, 2)}
              onChange={e => this.changeTheValue('validatorConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Transformers"
          refs={config.transformersRefs}
          type="transformer"
          onChange={e => this.changeTheValue('transformersRefs', e)}
          config={config.transformersConfig}
          onChangeConfig={e => this.changeTheValue('transformersConfig', e)}
        />
        <div className="form-group">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Transformers configuration"
              mode="json"
              value={JSON.stringify(config.transformersConfig, null, 2)}
              onChange={e => this.changeTheValue('transformersConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
      </>
    );
  }
}
