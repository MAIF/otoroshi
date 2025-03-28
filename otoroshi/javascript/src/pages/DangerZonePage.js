import React, { Component, Suspense, useState } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Form, SelectInput, BooleanInput } from '../components/inputs';
import { Proxy } from '../components/Proxy';
import { Scripts } from '../components/Scripts';
import moment from 'moment';

import deepSet from 'set-value';
import cloneDeep from 'lodash/cloneDeep';
import merge from 'lodash/merge';

import { CheckElasticsearchConnection } from '../components/elasticsearch';
import { Link } from 'react-router-dom';
import { NgForm, NgSelectRenderer } from '../components/nginputs';
import { Button } from '../components/Button';
import { Description } from './RouteDesigner/Designer';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import { LEGACY_PLUGINS_WRAPPER } from './RouteDesigner/DesignerConfig';
import { JsonObjectAsCodeInput } from '../components/inputs/CodeInput';

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
          onChange={(e) => {
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

function WasmoTester(props) {
  return (
    <div className="row mb-3">
      <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
        Testing button
      </label>
      <div className="col-sm-10">
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          <Button
            style={{ width: 'fit-content' }}
            type="success"
            onClick={() => {
              fetch('/bo/api/plugins/wasm', {
                method: 'POST',
                credentials: 'include',
                headers: {
                  Accept: 'application/json',
                  'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                  ...props.rawValue.wasmoSettings,
                }),
              })
                .catch((_) => {})
                .then((r) => {
                  console.log(r.status);
                  if (r.status !== 200) {
                    return Promise.reject([]);
                  } else return r.json();
                })
                .then((value) => {
                  window.newAlert(
                    <div>
                      <JsonObjectAsCodeInput
                        hideLabel
                        height={window.innerHeight - 320}
                        label=""
                        help="..."
                        onChange={() => {}}
                        value={value}
                      />
                      <p className="text-center" style={{ fontWeight: 'bold' }}>
                        Success! You are now ready to start using Wasmo with Otoroshi!{' '}
                      </p>
                    </div>,
                    'Wasmo connection',
                    null,
                    null,
                    {
                      width: '80vw',
                      marginLeft: '-20vw',
                    }
                  );
                });
            }}
          >
            Save and check the connection
          </Button>
        </div>
      </div>
    </div>
  );
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
    //'>>>Index settings',
    'indexSettings.clientSide',
    'indexSettings.interval',
    'indexSettings.numberOfShards',
    'indexSettings.numberOfReplicas',
    //'>>>TLS settings',
    'mtlsConfig.mtls',
    'mtlsConfig.loose',
    'mtlsConfig.trustAll',
    'mtlsConfig.certs',
    'mtlsConfig.trustedCerts',
  ];

  elasticConfigFormSchema = {
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
      props: { label: 'Type', placeholder: 'Event type (not needed for elasticsearch above 6.x)' },
    },
    user: {
      type: 'string',
      props: { label: 'User', placeholder: 'Elastic User (optional)' },
    },
    password: {
      type: 'string',
      props: { label: 'Password', placeholder: 'Elastic password (optional)', type: 'password' },
    },
    version: {
      type: 'string',
      props: {
        label: 'Version',
        placeholder: 'Elastic version (optional, if none provided it will be fetched from cluster)',
      },
    },
    applyTemplate: {
      type: 'bool',
      props: { label: 'Apply template', help: 'Automatically apply index template' },
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

  formSchema = () => ({
    'ipFiltering.whitelist': {
      type: 'array',
      props: {
        label: 'IP allowed list',
        placeholder: 'IP address that can access Otoroshi',
        help: 'Only IP addresses that will be able to access Otoroshi exposed services',
      },
    },
    'ipFiltering.blacklist': {
      type: 'array',
      props: {
        label: 'IP blocklist',
        placeholder: 'IP address that cannot access the Otoroshi',
        help: 'IP addresses that will be refused to access Otoroshi exposed services',
      },
    },
    throttlingQuota: {
      type: 'number',
      props: {
        label: 'Global throttling',
        placeholder: `Number of requests per window`,
        help: `The max. number of requests allowed per window globally on Otoroshi`,
      },
    },
    perIpThrottlingQuota: {
      type: 'number',
      props: {
        label: 'Throttling per IP',
        placeholder: `Number of requests per window per IP`,
        help: `The max. number of requests allowed per window per IP address globally on Otoroshi`,
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
    trustXForwarded: {
      type: 'bool',
      props: {
        label: 'Use X-Forwarded-* headers for routing',
        placeholder: '--',
        help: 'When evaluating routing of a request X-Forwarded-* headers will be used if presents',
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
        help: 'Freeze the Otoroshi datastore in read only mode. Only people with access to the actual underlying datastore will be able to disable this.',
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
        help: 'Enable live metrics in the Otoroshi cluster. Performs a lot of writes in the datastore',
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
        help: 'Limit the number of concurrent request processed by Otoroshi to a certain amount. Highly recommended for resilience.',
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
    anonymousReporting: {
      type: 'bool',
      props: {
        label: 'Send anonymous reports',
        placeholder: '-',
        help: 'If enabled, otoroshi will send anonymous usage metrics to the Otoroshi team. Enabling this is the best way to contribute to Otoroshi improvement !',
      },
    },
    initWithNewEngine: {
      type: 'bool',
      props: {
        label: 'Routes only',
        placeholder: '-',
        help: 'If enabled, otoroshi will not display service descriptor related stuff anymore',
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
        help: 'Maximum size of an HTTP/1.0 response in bytes. After this limit, response will be cut and sent as is. The best value here should satisfy (maxConcurrentRequests * maxHttp10ResponseSize) < process.memory for worst case scenario.',
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
        transformer: (a) => ({ value: a.id, label: a.name }),
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
        label: 'Clever Cloud consumer key',
        placeholder: 'Clever Cloud consumer key',
      },
    },
    'cleverSettings.consumerSecret': {
      type: 'string',
      props: {
        label: 'Clever Cloud consumer secret',
        placeholder: 'Clever Cloud consumer secret',
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
        label: 'Clever Cloud orga. Id',
        placeholder: 'Clever Cloud orga. Id',
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
    plugins: {
      type: GlobalPlugins,
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
        placeholder: 'Select a CA certificate',
        help: '...',
        valuesFrom: '/bo/api/proxy/api/certificates?ca=true',
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
    'tlsSettings.includeJdkCaServer': {
      type: 'bool',
      props: {
        label: 'Trust JDK CAs (server)',
        placeholder: '--',
        help: 'Trust JDK CAs. The CAs from the JDK CA bundle will be proposed in the certificate request when performing TLS handshake',
      },
    },
    'tlsSettings.includeJdkCaClient': {
      type: 'bool',
      props: {
        label: 'Trust JDK CAs (client)',
        placeholder: '--',
        help: 'Trust JDK CAs. The CAs from the JDK CA bundle will be used as trusted CAs when calling HTTPS resources',
      },
    },
    'tlsSettings.trustedCAsServer': {
      type: 'array',
      props: {
        label: 'Trusted CAs (server)',
        placeholder: 'Select a CA certificate',
        valuesFrom: '/bo/api/proxy/api/certificates?ca=true',
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
        help: 'Select the trusted CAs you want for TLS terminaison. Those CAs only will be proposed in the certificate request when performing TLS handshake',
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
    'wasmoSettings.settings.url': {
      type: 'string',
      props: {
        label: 'URL',
      },
    },
    'wasmoSettings.settings.clientId': {
      type: 'string',
      props: {
        label: 'Apikey id',
      },
    },
    'wasmoSettings.settings.clientSecret': {
      type: 'string',
      props: {
        label: 'Apikey secret',
      },
    },
    'wasmoSettings.settings.pluginsFilter': {
      type: 'string',
      props: {
        label: 'User(s)',
      },
    },
    'wasmoSettings.settings.legacyAuth': {
      type: 'bool',
      props: {
        label: 'Use legacy auth.',
      },
    },
    'wasmoSettings.tlsConfig.mtls': {
      type: 'bool',
      props: { label: 'Custom TLS Settings' },
    },
    'wasmoSettings.tlsConfig.loose': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.wasmoSettings.tlsConfig.mtls),
      props: { label: 'TLS loose' },
    },
    'wasmoSettings.tlsConfig.trustAll': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.wasmoSettings.tlsConfig.mtls),
      props: { label: 'TrustAll' },
    },
    'wasmoSettings.tlsConfig.certs': {
      type: 'array',
      display: (v) => tryOrTrue(() => v.wasmoSettings.tlsConfig.mtls),
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
    'wasmoSettings.tlsConfig.trustedCerts': {
      type: 'array',
      display: (v) =>
        tryOrTrue(() => v.wasmoSettings.tlsConfig.mtls && !v.wasmoSettings.tlsConfig.trustAll),
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
    testing: {
      type: WasmoTester,
    },
    'quotasSettings.enabled': {
      type: 'bool',
      props: {
        label: 'Enable quotas exceeding alerts',
        placeholder: '--',
        help: 'When apikey quotas is almost exceeded, an alert will be sent',
      },
    },
    'quotasSettings.dailyQuotasThreshold': {
      type: 'number',
      props: {
        label: 'Daily quotas threshold',
        placeholder: '0.8',
        suffix: 'percentage',
        help: 'The percentage of daily calls before sending alerts',
      },
    },
    'quotasSettings.monthlyQuotasThreshold': {
      type: 'number',
      props: {
        label: 'Monthly quotas threshold',
        placeholder: '0.8',
        suffix: 'percentage',
        help: 'The percentage of monthly calls before sending alerts',
      },
    },
    'tlsSettings.bannedAlpnProtocols': {
      type: 'jsonobjectcode',
      props: {
        label: 'Banned ALPN protocols',
        help: 'a key/value object that list alpn banned protocols per domain name, ie: {"mydomain.oto.tools": ["h2","h3"]}',
        mode: 'json',
      },
    },
    templates: {
      type: 'code',
      props: {
        label: '',
        mode: 'json',
      },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    env: {
      type: 'jsonobjectcode',
      props: {
        label: 'Otoroshi environment',
        mode: 'json',
      },
    },
    ...Otoroshi.extensions()
      .flatMap((ext) => ext.dangerZoneParts || [])
      .map((part) => part.schema)
      .reduce((a, b) => ({ ...a, ...b }), {}),
  });

  formFlow = (value) => [
    '<<<Misc. Settings',
    'maintenanceMode',
    'u2fLoginOnly',
    'apiReadOnly',
    // 'streamEntityOnly',
    'autoLinkToDefaultGroup',
    'useCircuitBreakers',
    value.logAnalyticsOnServer ? 'logAnalyticsOnServer' : null,
    'useAkkaHttpClient',
    'enableEmbeddedMetrics',
    'middleFingers',
    'limitConcurrentRequests',
    'trustXForwarded',
    'anonymousReporting',
    'initWithNewEngine',
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
    '>>>Global plugins',
    'plugins',
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
    '>>>Quotas alerting settings',
    'quotasSettings.enabled',
    'quotasSettings.dailyQuotasThreshold',
    'quotasSettings.monthlyQuotasThreshold',
    '>>>User-Agent extraction settings',
    'userAgentSettings.enabled',
    '>>>Geolocation extraction settings',
    'geolocationSettings',
    '>>>Tls Settings',
    'tlsSettings.randomIfNotFound',
    'tlsSettings.defaultDomain',
    'tlsSettings.includeJdkCaServer',
    'tlsSettings.includeJdkCaClient',
    'tlsSettings.trustedCAsServer',
    'tlsSettings.bannedAlpnProtocols',
    '>>>Auto Generate Certificates',
    'autoCert.enabled',
    'autoCert.replyNicely',
    'autoCert.caRef',
    'autoCert.allowed',
    'autoCert.notAllowed',
    '>>>Default templates',
    'templates',
    '>>>Wasmo',
    'wasmoSettings.settings.url',
    'wasmoSettings.settings.clientId',
    'wasmoSettings.settings.clientSecret',
    'wasmoSettings.settings.pluginsFilter',
    'wasmoSettings.settings.legacyAuth',
    'wasmoSettings.tlsConfig.mtls',
    'wasmoSettings.tlsConfig.loose',
    'wasmoSettings.tlsConfig.trustAll',
    'wasmoSettings.tlsConfig.certs',
    'wasmoSettings.tlsConfig.trustedCerts',
    'testing',
    '>>>Global metadata',
    'tags',
    'metadata',
    'env',
    ...Otoroshi.extensions()
      .flatMap((ext) => ext.dangerZoneParts || [])
      .flatMap((part) => {
        return ['>>>' + part.title, ...part.flow];
      }),
  ];

  syncSchema = {
    host: {
      type: 'string',
      props: { label: 'Leader host', placeholder: 'xxxxx-redis.services.clever-cloud.com' },
    },
    port: { type: 'number', props: { label: 'Leader port', placeholder: '4242' } },
    password: { type: 'string', props: { label: 'Leader password', type: 'password' } },
  };

  syncFlow = ['host', 'port', 'password'];

  componentDidMount() {
    this.props.setSidebarContent(null);
    this.props.setTitle(`Danger zone`);
    BackOfficeServices.getGlobalConfig().then((value) =>
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

  saveShortcut = (e) => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.changed) {
        this.saveGlobalConfig();
      }
    }
  };

  saveGlobalConfig = (e) => {
    if (e && e.preventDefault) e.preventDefault();

    return BackOfficeServices.updateGlobalConfig(this.state.value).then(() => {
      this.setState({ originalValue: this.state.value, changed: false });
    });
  };

  updateState = (raw) => {
    const value = { ...raw };

    if (value.elasticReadsConfig) delete value.elasticReadsConfig.clusterUri;
    this.setState({ value, changed: shallowDiffers(this.state.originalValue, value) });
  };

  sync = () => {
    this.setState({ syncing: true });
    BackOfficeServices.syncWithLeader(this.state.sync).then(
      (d) => this.setState({ syncing: false }),
      (e) => this.setState({ syncingError: e, syncing: false })
    );
  };

  panicMode = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure you want to enable panic mode ?').then((ok) => {
      window.newConfirm('Are you really sure ?').then((ok2) => {
        if (ok && ok2) {
          BackOfficeServices.panicMode().then(() => {
            window.location.href = '/';
          });
        }
      });
    });
  };

  fullExport = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.fetchOtoroshi('application/json').then((otoroshi) => {
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

  fullExportNdJson = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.fetchOtoroshi('application/x-ndjson').then((otoroshi) => {
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

  exportJson = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const json = JSON.stringify(
      { ...this.state.value, kind: 'config.otoroshi.io/GlobalConfig' },
      null,
      2
    );
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.id = String(Date.now());
    a.style.display = 'none';
    a.download = `global-config-${Date.now()}.json`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => document.body.removeChild(a), 300);
  };

  exportYaml = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    fetch('/bo/api/json_to_yaml', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        apiVersion: 'proxy.otoroshi.io/v1',
        kind: 'GlobalConfig',
        metadata: {
          name: 'global-config',
        },
        spec: this.state.value,
      }),
    })
      .then((r) => r.text())
      .then((yaml) => {
        const blob = new Blob([yaml], { type: 'application/yaml' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.id = String(Date.now());
        a.style.display = 'none';
        a.download = `global-config-${Date.now()}.yaml`;
        a.href = url;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => document.body.removeChild(a), 300);
      });

    /*
    // const json = YAML.stringify({
      apiVersion: 'proxy.otoroshi.io/v1',
      kind: 'GlobalConfig',
      metadata: {
        name: 'global-config',
      },
      spec: this.state.value,
    }, null, { defaultStringType: 'QUOTE_DOUBLE', doubleQuotedAsJSON: true, defaultType: 'QUOTE_DOUBLE' });
    const blob = new Blob([json], { type: 'application/yaml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.id = String(Date.now());
    a.style.display = 'none';
    a.download = `global-config-${Date.now()}.yaml`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => document.body.removeChild(a), 300);
    */
  };

  importData = (e) => {
    if (e && e.preventDefault()) e.preventDefault();

    window
      .newConfirm(
        'Importing will erase all existing data in the datastore.\n You will be logged out at the end of the import.\n You may want to export your data before doing that.\n Are you sure you want to do that ?'
      )
      .then((ok) => {
        window.newConfirm('Really sure ?').then((ok2) => {
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
              (r) => window.location.reload(),
              (e) => console.log(e)
            );
          }
        });
      });
  };

  readyToPush = (e) => {
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
      <div class="container">
        <div className="displayGroupBtn">
          <button
            title="Save changes"
            className="btn btn-success"
            type="button"
            onClick={this.saveGlobalConfig}
            {...propsDisabled}
          >
            Save
          </button>
        </div>
        <Form
          value={this.state.value}
          onChange={this.updateState}
          flow={this.formFlow(this.state.value)}
          schema={this.formSchema()}
          style={{ marginTop: 50 }}
        />
        <hr />
        <form className="form-horizontal">
          <input
            type="file"
            name="export"
            id="export"
            className="inputfile"
            ref={(ref) => (this.fileUpload = ref)}
            style={{ display: 'none' }}
            onChange={this.readyToPush}
          />
          {!this.state.readyToPush && (
            <div className="row mb-3">
              <label className="col-sm-2 col-form-label" />
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
                  }}
                >
                  <i className="fas fa-file" /> Recover from a full export file
                </label>
              </div>
            </div>
          )}
          {this.state.readyToPush && (
            <div className="row mb-3">
              <label className="col-sm-2 col-form-label" />
              <div className="col-sm-10">
                <button type="button" className="btn btn-danger" onClick={this.importData}>
                  <i className="fas fa-file-import" /> Flush DataStore & Import file '
                  {this.state.readyToPush}'
                </button>
              </div>
            </div>
          )}
        </form>
        <hr />
        <form className="form-horizontal">
          <div className="row mb-3">
            <label className="col-sm-2 col-form-label" />
            <div className="col-sm-10  input-group-btn">
              <button type="button" className="btn btn-success" onClick={this.fullExport}>
                <i className="fas fa-file-export" /> Full export
              </button>
              <button type="button" className="btn btn-success" onClick={this.fullExportNdJson}>
                <i className="fas fa-file-export" /> Full export (ndjson)
              </button>
              <button type="button" className="btn btn-info" onClick={this.exportJson}>
                <i className="fas fa-file-export" /> JSON
              </button>
              <button type="button" className="btn btn-info" onClick={this.exportYaml}>
                <i className="fas fa-file-export" /> YAML
              </button>
              <button
                type="button"
                className="btn btn-danger"
                style={{ marginLeft: 5, marginRight: 5 }}
                onClick={this.panicMode}
              >
                <i className="fas fa-fire" /> Enable Panic Mode
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
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label" />
        <div className="col-sm-10 input-group-btn">
          {!this.props.rawValue.backOfficeAuthRef && (
            <Link to="/auth-configs/add" className="btn btn-sm btn-primary">
              <i className="fas fa-plus" /> Create a new auth. config.
            </Link>
          )}
          {this.props.rawValue.backOfficeAuthRef && (
            <Link
              to={`/auth-configs/edit/${this.props.rawValue.backOfficeAuthRef}`}
              className="btn btn-sm btn-success"
            >
              <i className="fas fa-edit" /> Edit the auth. config.
            </Link>
          )}
          <Link to="/auth-configs" className="btn btn-sm btn-primary">
            <i className="fas fa-link" /> all auth. config.
          </Link>
        </div>
      </div>
    );
  }
}

class GlobalScripts extends Component {
  changeTheValue = (name, value) => {
    const cloned = cloneDeep(this.props.value);
    const newCloned = deepSet(cloned, name, value);
    this.props.onChange(newCloned);
  };

  setTheValue = (value, f) => {
    this.props.rawOnChange(value, f);
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
        <Message
          message={
            <>
              <span>
                Global scripts will be deprecated soon, please use global plugins instead !
              </span>
              <Migration
                style={{ marginLeft: 10 }}
                what="transformer"
                value={this.props.rawValue}
                onChange={this.setTheValue}
                extractHolder={(s) => this.props.rawValue.plugins}
                // reset={() => {
                //   this.props.rawOnChange({ ...this.props.rawValue, scripts: {
                //     "enabled": false,
                //     "transformersRefs": [],
                //     "transformersConfig": {},
                //     "validatorRefs": [],
                //     "validatorConfig": {},
                //     "preRouteRefs": [],
                //     "preRouteConfig": {},
                //     "sinkRefs": [],
                //     "sinkConfig": {},
                //     "jobRefs": [],
                //     "jobConfig": {}
                //   }});
                // }}
                extractLegacy={(s) => {
                  return {
                    enabled: s.scripts.enabled,
                    refs: [
                      ...s.scripts.transformersRefs,
                      ...s.scripts.validatorRefs,
                      ...s.scripts.preRouteRefs,
                      ...s.scripts.sinkRefs,
                      ...s.scripts.jobRefs,
                    ],
                    config: {
                      ...s.scripts.transformersConfig,
                      ...s.scripts.validatorConfig,
                      ...s.scripts.preRouteConfig,
                      ...s.scripts.sinkConfig,
                      ...s.scripts.jobConfig,
                    },
                    excluded: [],
                  };
                }}
                setHolder={(s, h) => {
                  s.plugins = h;
                  s.scripts = {
                    enabled: false,
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
                  return s;
                }}
              />
            </>
          }
        />
        <BooleanInput
          label="Enabled"
          value={config.enabled}
          help="Global scripts enabled"
          onChange={(v) => this.changeTheValue('enabled', v)}
        />
        <Scripts
          label="Jobs"
          refs={config.jobRefs}
          type="job"
          onChange={(e) => this.changeTheValue('jobRefs', e)}
          config={config.jobConfig}
          onChangeConfig={(e) => this.changeTheValue('jobConfig', e)}
        />
        <div className="row mb-3">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Jobs configuration"
              mode="json"
              value={JSON.stringify(config.jobConfig, null, 2)}
              onChange={(e) => this.changeTheValue('jobConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Request sinks"
          refs={config.sinkRefs}
          type="sink"
          onChange={(e) => this.changeTheValue('sinkRefs', e)}
          config={config.sinkConfig}
          onChangeConfig={(e) => this.changeTheValue('sinkConfig', e)}
        />
        <div className="row mb-3">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Request sinks configuration"
              mode="json"
              value={JSON.stringify(config.sinkConfig, null, 2)}
              onChange={(e) => this.changeTheValue('sinkConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Pre-routes"
          refs={config.preRouteRefs}
          type="preroute"
          onChange={(e) => this.changeTheValue('preRouteRefs', e)}
          config={config.preRouteConfig}
          onChangeConfig={(e) => this.changeTheValue('preRouteConfig', e)}
        />
        <div className="row mb-3">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Pre-routes configuration"
              mode="json"
              value={JSON.stringify(config.preRouteConfig, null, 2)}
              onChange={(e) => this.changeTheValue('preRouteConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Access validators"
          refs={config.validatorRefs}
          type="validator"
          onChange={(e) => this.changeTheValue('validatorRefs', e)}
          config={config.validatorConfig}
          onChangeConfig={(e) => this.changeTheValue('validatorConfig', e)}
        />
        <div className="row mb-3">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Access validators configuration"
              mode="json"
              value={JSON.stringify(config.validatorConfig, null, 2)}
              onChange={(e) => this.changeTheValue('validatorConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
        <Scripts
          label="Transformers"
          refs={config.transformersRefs}
          type="transformer"
          onChange={(e) => this.changeTheValue('transformersRefs', e)}
          config={config.transformersConfig}
          onChangeConfig={(e) => this.changeTheValue('transformersConfig', e)}
        />
        <div className="row mb-3">
          <Suspense fallback={<div>loading ...</div>}>
            <CodeInput
              label="Transformers configuration"
              mode="json"
              value={JSON.stringify(config.transformersConfig, null, 2)}
              onChange={(e) => this.changeTheValue('transformersConfig', JSON.parse(e))}
            />
          </Suspense>
        </div>
      </>
    );
  }
}

const GlobalPluginInformation = ({ plugin, open }) => {
  if (!open) return null;

  const legacyPluginDocumentationUrl =
    'https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html';

  const getNgPluginDocumentationUrl = () => {
    return `https://maif.github.io/otoroshi/manual/next/built-in-plugins.html#${
      plugin.id.replace('cp:', '')
      // .replace(/\./g, '-')
      // .toLowerCase()
    }`;
  };

  return (
    <div
      className="mt-3"
      style={{
        background: '#494849',
        padding: '12px',
      }}
    >
      <h3>{plugin.name}</h3>
      <div
        className="d-flex align-items-center justify-content-end mb-3"
        style={{ paddingRight: '12px' }}
      >
        <div>
          <Button
            className="btn-sm"
            onClick={() => {
              window
                .open(
                  plugin.legacy ? legacyPluginDocumentationUrl : getNgPluginDocumentationUrl(),
                  '_blank'
                )
                .focus();
            }}
          >
            <i className="fas fa-share" /> documentation
          </Button>
        </div>
      </div>
      <Description text={plugin.description} steps={[]} legacy={plugin.legacy} />
    </div>
  );
};

class GlobalPlugins extends Component {
  state = {
    legacyPlugins: [],
    ngPlugins: [],
  };

  extractConfigurationFromPlugin = (plugin, isOldEngine) => {
    if (!plugin) return;

    if (isOldEngine) {
      return {
        ...(plugin.default_config || plugin.defaultConfig),
        ...this.props.value.config,
      };
    } else {
      return {
        config: {
          ...(plugin.default_config || plugin.defaultConfig),
          plugin: plugin.legacy ? plugin.id : undefined,
        },
        debug: false,
        enabled: true,
        exclude: [],
        include: [],
        plugin:
          plugin.legacy && plugin.id !== 'cp:otoroshi.next.proxy.ProxyEngine'
            ? LEGACY_PLUGINS_WRAPPER[plugin.pluginType]
            : plugin.id,
      };
    }
  };

  handleNgPluginsChange = (e, nextAvailablePlugins) => {
    const currentConfig = this.props.value.config?.ng || [];

    const ngConfig = (e.plugins || []).reduce((acc, plugin) => {
      const pluginConfig = currentConfig.find(
        (entry) => entry.plugin === plugin || entry.config?.plugin === plugin
      );
      if (!pluginConfig) {
        if (plugin.length === 0) {
          return [
            ...acc,
            this.extractConfigurationFromPlugin(this.state.ngPlugins[nextAvailablePlugins], false),
          ];
        } else {
          return [
            ...acc,
            this.extractConfigurationFromPlugin(
              [...this.state.legacyPlugins, ...this.state.ngPlugins].find((f) => f.id === plugin),
              false
            ),
          ];
        }
      }
      return [...acc, pluginConfig];
    }, []);

    this.props.onChange({
      ...this.props.value,
      config: {
        ...this.props.value.config,
        ng: ngConfig.filter((f) => f && f.plugin), // prevent manual deletion of plugin by deleting the configuration of plugin which doesn't contain id
      },
    });
  };

  schema = {
    refs: {
      type: 'array',
      of: 'string',
      label: 'Plugins',
      itemRenderer: (props) => {
        const [open, setOpen] = useState(false);
        const index = ~~props.path[props.path.length - 1];

        const refs = props.rootValue?.refs || [];
        const value = refs[index];

        const plugin =
          [...this.state.ngPlugins, ...this.state.legacyPlugins].find((f) => f.id === value) || {};

        return (
          <div style={{ flex: 1 }}>
            <div className="d-flex">
              <Button className="me-1 btn-sm" onClick={() => setOpen(!open)}>
                <i className={`fas fa-chevron-${open ? 'down' : 'right'}`} />
              </Button>
              <div style={{ flex: 1 }}>
                <NgSelectRenderer
                  value={value}
                  placeholder="Select a plugin"
                  label={' '}
                  ngOptions={{
                    spread: true,
                  }}
                  onChange={(e) => {
                    const selectedPlugin = this.state.legacyPlugins.find((f) => f.id === e);

                    this.props.onChange({
                      ...this.props.value,
                      refs: refs.map((r, i) => (i === index ? selectedPlugin.id : r)),
                    });
                  }}
                  margin={0}
                  style={{ flex: 1 }}
                  options={this.state.legacyPlugins}
                  optionsTransformer={(arr) =>
                    arr.map((item) => ({ label: item.name, value: item.id }))
                  }
                />
              </div>
              {(plugin.default_config || plugin.defaultConfig) && (
                <FeedbackButton
                  className="btn-sm ms-1"
                  type="info"
                  icon={() => <i className="fas fa-cog me-1" style={{ fontSize: '14px' }} />}
                  onPress={() =>
                    this.changeTheValue('config', this.extractConfigurationFromPlugin(plugin, true))
                  }
                  text="Inject default configuration"
                />
              )}
            </div>
            <GlobalPluginInformation plugin={plugin} open={open} />
          </div>
        );
      },
    },
    config: {
      type: 'json',
      label: 'Configuration of all plugins',
      props: {
        ace_config: {
          fontSize: 14,
        },
      },
    },
  };

  ngPluginsSchema = {
    plugins: {
      type: 'array',
      of: 'string',
      label: 'Plugins',
      itemRenderer: (props) => {
        const [open, setOpen] = useState(false);
        const index = ~~props.path[props.path.length - 1];

        const value = props.rootValue?.plugins[index];

        const plugin =
          [...this.state.ngPlugins, ...this.state.legacyPlugins].find((f) => f.id === value) || {};

        return (
          <div style={{ flex: 1 }}>
            <div className="d-flex">
              <Button className="me-1 btn-sm" onClick={() => setOpen(!open)}>
                <i className={`fas fa-chevron-${open ? 'down' : 'right'}`} />
              </Button>
              <div style={{ flex: 1 }}>
                <NgSelectRenderer
                  value={value}
                  placeholder="Select a plugin"
                  label={' '}
                  ngOptions={{
                    spread: true,
                  }}
                  onChange={props.onChange}
                  margin={0}
                  style={{ flex: 1 }}
                  options={[...this.state.ngPlugins, ...this.state.legacyPlugins]}
                  optionsTransformer={(arr) =>
                    arr.map((item) => ({ label: item.name, value: item.id }))
                  }
                />
              </div>
              {(plugin.default_config || plugin.defaultConfig) && (
                <FeedbackButton
                  className="btn-sm ms-1"
                  type="info"
                  icon={() => <i className="fas fa-cog me-1" style={{ fontSize: '14px' }} />}
                  onPress={() => {
                    const newConfig = this.extractConfigurationFromPlugin(plugin, false);

                    return Promise.resolve(
                      this.props.onChange({
                        ...this.props.value,
                        config: {
                          ...this.props.value.config,
                          ng: (this.props.value.config.ng || []).map((v, i) => {
                            if (i === index) return newConfig;
                            return v;
                          }),
                        },
                      })
                    );
                  }}
                  text="Inject default configuration"
                />
              )}
            </div>
            <GlobalPluginInformation plugin={plugin} open={open} />
          </div>
        );
      },
    },
  };

  newEngineSchema = {
    enabled: {
      type: 'bool',
      label: 'Enabled',
    },
    ng_plugins: {
      component: (props) => {
        const plugins = (props.rootValue?.config?.ng || [])
          .flatMap((r) => [r.plugin, (r.config || {}).plugin])
          .filter((p) => p && !p.includes('.wrappers.'));

        return (
          <NgForm
            value={{ plugins }}
            onChange={(e) => this.handleNgPluginsChange(e, plugins.length)}
            schema={this.ngPluginsSchema}
            flow={['plugins']}
          />
        );
      },
    },
  };

  flow = ['refs', 'config'];

  newEngineFlow = ['enabled', 'ng_plugins'];

  componentDidMount() {
    Promise.all([BackOfficeServices.getOldPlugins(), BackOfficeServices.getPlugins()]).then(
      ([legacyPlugins, ngPlugins]) => {
        this.setState({
          legacyPlugins: legacyPlugins.map((p) => ({ ...p, legacy: true })),
          ngPlugins,
        });
      }
    );
  }

  changeTheValue = (name, value) => {
    return Promise.resolve(
      this.props.onChange({
        ...this.props.value,
        [name]: value,
      })
    );
  };

  render() {
    return (
      <div className="plugins-danger-zone">
        <Message message="This is the new place for global plugins in otoroshi. Please use it instead of global scripts as they will be deprecated soon !" />
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label"></label>
          <div className="col-sm-10">
            <span style={{ color: 'rgb(249, 176, 0)', fontWeight: 'bold', marginTop: '7px' }}>
              Plugins on new Otoroshi engine
            </span>
          </div>
        </div>
        <NgForm
          value={this.props.value}
          onChange={this.props.onChange}
          flow={this.newEngineFlow}
          schema={this.newEngineSchema}
        />
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label"></label>
          <div className="col-sm-10">
            <span style={{ color: 'rgb(249, 176, 0)', fontWeight: 'bold', marginTop: '7px' }}>
              Plugins on old Otoroshi engine and global jobs
            </span>
          </div>
        </div>
        <NgForm
          value={this.props.value}
          onChange={this.props.onChange}
          flow={this.flow}
          schema={this.schema}
        />
      </div>
    );
  }
}

export class Message extends Component {
  render() {
    return (
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label" />
        <div className="col-sm-10">
          <div className="sub-container sub-container__bg-color">
            <p style={{ textAlign: 'justify', marginBottom: 0 }}>{this.props.message}</p>
          </div>
        </div>
      </div>
    );
  }
}

export class Migration extends Component {
  migrate = () => {
    const value = cloneDeep(this.props.value);
    const legacy = this.props.extractLegacy
      ? cloneDeep(this.props.extractLegacy(value))
      : { enabled: false, refs: [], excluded: [], config: {} };
    const holder = this.props.extractHolder
      ? cloneDeep(this.props.extractHolder(value))
      : { enabled: false, refs: [], excluded: [], config: {} };
    const newHolder = cloneDeep(holder);
    newHolder.refs = [...newHolder.refs, ...legacy.refs];
    newHolder.excluded = [...newHolder.excluded, ...legacy.excluded];
    newHolder.config = merge({}, newHolder.config, legacy.config);
    if (!newHolder.enabled && legacy.enabled) {
      newHolder.enabled = true;
    }
    if (this.props.setHolder) {
      const res = this.props.setHolder(value, newHolder);
      this.props.onChange(res, () => {
        if (this.props.reset) this.props.reset();
      });
    }
  };
  render() {
    return (
      <button
        className="btn btn-danger btn-sm btn-sm"
        type="button"
        onClick={this.migrate}
        style={this.props.style}
      >
        <i className="fas fa-fire" /> Migrate all to plugins
      </button>
    );
  }
}
