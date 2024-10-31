import React, { Component, useState } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { nextClient } from '../services/BackOfficeServices';
import { Table, SimpleBooleanInput } from '../components/inputs';
import { ServiceSidebar } from '../components/ServiceSidebar';
import faker from 'faker';
import { Restrictions } from '../components/Restrictions';

import DesignerSidebar from './RouteDesigner/Sidebar';
import Loader from '../components/Loader';
import { firstLetterUppercase } from '../util';

import { Tooltip as ReactTooltip } from 'react-tooltip';

const FIELDS_SELECTOR = 'otoroshi-fields-selector';

const CORE_FIELDS = ['enabled', 'clientId', 'clientName', 'stats', 'credentials'];

class ApikeyBearer extends Component {
  state = { bearer: null, cname: 'fas fa-copy' };

  componentDidMount() {
    this.update();
  }

  componentDidUpdate(prevProps, prevState) {
    const prevClientId = prevProps.rawValue.clientId;
    const curClientId = this.props.rawValue.clientId;
    const prevClientSecret = prevProps.rawValue.clientSecret;
    const curClientSecret = this.props.rawValue.clientSecret;
    if (prevClientId !== curClientId || prevClientSecret !== curClientSecret) {
      this.update();
    }
  }

  copy = () => {
    if (this.state.bearer) {
      console.log('copy');
      try {
        navigator.clipboard.writeText(this.state.bearer);
      } catch (e) {
        console.log(e);
      }
      this.setState({ cname: 'fas fa-check' }, () => {
        console.log('changed');
        setTimeout(() => {
          console.log('back');
          this.setState({ cname: 'fas fa-copy' });
        }, 2000);
      });
    }
  };

  update = () => {
    if (!window.location.pathname.endsWith('/add')) {
      fetch(
        '/bo/api/proxy/api/apikeys/' +
          this.props.rawValue.clientId +
          '/bearer?newSecret=' +
          this.props.rawValue.clientSecret,
        {
          method: 'GET',
          credentials: 'include',
          headers: {
            Accept: 'application/json',
          },
        }
      )
        .then((r) => r.json())
        .then((r) => {
          this.setState({ bearer: r.bearer_new });
        });
    }
  };

  toggle = () => {
    this.setState({ show: !this.state.show });
  };

  render() {
    if (!this.state.bearer) {
      return null;
    }
    let fake = String(this.state.bearer);
    let fourth = parseInt(fake.length / 4, 10);
    fake = fake.substring(0, fourth);
    fake = fake + [...Array(fourth * 3)].map(() => '*').join('');
    return (
      <div className="row mb-3">
        <label className="col-sm-2 col-form-label">
          Apikey Bearer{' '}
          <span>
            <i
              className="far fa-question-circle"
              data-tooltip-content="Your apikey as a bearer to pass in the Authorization header"
              data-tooltip-id="apikey-bearer"
            />
            <ReactTooltip id="apikey-bearer" />
          </span>
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            {!this.state.show && (
              <input type="text" className="form-control" disabled value={fake} />
            )}
            {this.state.show && (
              <input type="text" className="form-control" disabled value={this.state.bearer} />
            )}
            {this.state.show && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="hide bearer"
                onClick={this.toggle}
              >
                <i className="fas fa-eye-slash" />
              </span>
            )}
            {!this.state.show && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="show bearer"
                onClick={this.toggle}
              >
                <i className="fas fa-eye" />
              </span>
            )}
            {navigator.clipboard && window.isSecureContext && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="copy bearer"
                onClick={this.copy}
              >
                <i className={this.state.cname} />
              </span>
            )}
          </div>
        </div>
      </div>
    );
  }
}

class ApikeySecret extends Component {
  state = { show: false, cname: 'fas fa-copy' };

  copy = () => {
    try {
      navigator.clipboard.writeText(this.props.value);
    } catch (e) {
      console.log(e);
    }
    this.setState({ cname: 'fas fa-check' }, () => {
      setTimeout(() => {
        this.setState({ cname: 'fas fa-copy' });
      }, 2000);
    });
  };

  toggle = () => {
    this.setState({ show: !this.state.show });
  };

  render() {
    let fake = String(this.props.value);
    let fourth = parseInt(fake.length / 4, 10);
    fake = fake.substring(0, fourth);
    fake = fake + [...Array(fourth * 3)].map(() => '*').join('');
    return (
      <div className="row mb-3">
        <label className="col-sm-2 col-form-label">
          Apikey Secret{' '}
          <span>
            <i
              className="far fa-question-circle"
              data-tooltip-content="The secret is a random key used to validate the API key"
              data-tooltip-id="apikey-secret"
            />
            <ReactTooltip id="apikey-secret" />
          </span>
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            {!this.state.show && (
              <input type="text" className="form-control" disabled value={fake} />
            )}
            {this.state.show && (
              <input
                type="text"
                className="form-control"
                value={this.props.value}
                onChange={(e) => this.props.onChange(e.target.value)}
              />
            )}
            {this.state.show && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="hide secret"
                onClick={this.toggle}
              >
                <i className="fas fa-eye-slash" />
              </span>
            )}
            {!this.state.show && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="show secret"
                onClick={this.toggle}
              >
                <i className="fas fa-eye" />
              </span>
            )}
            {navigator.clipboard && window.isSecureContext && (
              <span
                className="input-group-text"
                style={{ cursor: 'pointer' }}
                title="copy secret"
                onClick={this.copy}
              >
                <i className={this.state.cname} />
              </span>
            )}
          </div>
        </div>
      </div>
    );
  }
}

const Both = ({ label, rawValue }) => (
  <div className="row mb-3">
    <label className="col-sm-2 col-form-label">{label}</label>
    <div className="col-sm-10">
      <input
        onChange={(e) => ''}
        type="text"
        className="form-control"
        value={`${rawValue.clientId}:${rawValue.clientSecret}`}
      />
    </div>
  </div>
);

const CurlCommand = ({ label, rawValue, env }) => (
  <div className="row mb-3">
    <label className="col-sm-2 col-form-label">{label}</label>
    <div className="col-sm-10">
      {env && (
        <input
          onChange={(e) => ''}
          type="text"
          className="form-control"
          value={`curl -X GET -H '${env.clientIdHeader || 'Opun-Client-Id'}: ${
            rawValue.clientId
          }' -H '${env.clientSecretHeader || 'Opun-Client-Secret'}: ${
            rawValue.clientSecret
          }' http://xxxxxx --include`}
        />
      )}
    </div>
  </div>
);

const BasicAuthToken = ({ label, rawValue }) => (
  <div className="row mb-3">
    <label className="col-sm-2 col-form-label">{label}</label>
    <div className="col-sm-10">
      <input
        onChange={(e) => ''}
        type="text"
        className="form-control"
        value={`Authorization: Basic ${window.btoa(
          rawValue.clientId + ':' + rawValue.clientSecret
        )}`}
      />
    </div>
  </div>
);

const CurlCommandWithBasicAuth = ({ label, rawValue, env }) => (
  <div className="row mb-3">
    <label className="col-sm-2 col-form-label">{label}</label>
    <div className="col-sm-10">
      {env && (
        <input
          onChange={(e) => ''}
          type="text"
          className="form-control"
          value={`curl -X GET -H 'Authorization: Basic ${window.btoa(
            rawValue.clientId + ':' + rawValue.clientSecret
          )}' http://xxxxxx --include`}
        />
      )}
    </div>
  </div>
);

const ResetSecret = ({ changeValue }) => (
  <div className="row mb-3">
    <label className="col-sm-2 col-form-label" />
    <div className="col-sm-10">
      <button
        type="button"
        className="btn btn-danger btn-sm"
        onClick={(e) => changeValue('clientSecret', 'apks_' + faker.random.alphaNumeric(64))}
      >
        <i className="fas fa-sync" /> Reset secret
      </button>
    </div>
  </div>
);

class ResetQuotas extends Component {
  resetQuotas = (e) => {
    e.preventDefault();
    BackOfficeServices.resetRemainingApikeyQuotas(this.props.rawValue.clientId).then(() => {
      window.location.reload();
    });
  };

  render() {
    return (
      <div className="row mb-3">
        <label className="col-sm-2 col-form-label" />
        <div className="col-sm-10">
          <button type="button" className="btn btn-danger btn-sm" onClick={this.resetQuotas}>
            <i className="fas fa-sync" /> Reset quotas consumption
          </button>
        </div>
      </div>
    );
  }
}

class CopyCredentials extends Component {
  state = {
    copyIconName: 'fas fa-copy',
  };

  unsecuredCopyToClipboard = (text) => {
    const textArea = document.createElement('textarea');
    textArea.value = text;
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
      document.execCommand('copy');
    } catch (err) {
      console.error('Unable to copy to clipboard', err);
    }
    document.body.removeChild(textArea);
  };

  copy = (value) => {
    if (window.isSecureContext && navigator.clipboard) {
      navigator.clipboard.writeText(value);
    } else {
      this.unsecuredCopyToClipboard(value);
    }
    this.setState({
      copyIconName: 'fas fa-check',
    });

    setTimeout(() => {
      this.setState({
        copyIconName: 'fas fa-copy',
      });
    }, 2000);
  };
  render() {
    const props = this.props;
    return (
      <div className="row mb-3">
        <label className="col-sm-2 col-form-label" />
        <div className="col-sm-10">
          <input
            ref={(r) => (this.clipboard = r)}
            style={{ position: 'fixed', left: 0, top: -250 }}
            type="text"
            value={props.rawValue.clientId + ':' + props.rawValue.clientSecret}
            alt="copy credentials"
          />
          <button
            type="button"
            className="btn btn-success btn-sm"
            onClick={() => {
              this.copy(props.rawValue.clientId + ':' + props.rawValue.clientSecret);
            }}
          >
            <i className={this.state.copyIconName} /> Copy credentials to clipboard
          </button>
        </div>
      </div>
    );
  }
}

function CopyFromLineItem({ item }) {
  const [copyIconName, setCopyIconName] = useState('fas fa-copy');

  const unsecuredCopyToClipboard = (text) => {
    const textArea = document.createElement('textarea');
    textArea.value = text;
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
      document.execCommand('copy');
    } catch (err) {
      console.error('Unable to copy to clipboard', err);
    }
    document.body.removeChild(textArea);
  };

  const copy = (value) => {
    if (window.isSecureContext && navigator.clipboard) {
      navigator.clipboard.writeText(value);
    } else {
      unsecuredCopyToClipboard(value);
    }

    setCopyIconName('fas fa-check');

    setTimeout(() => {
      setCopyIconName('fas fa-copy');
    }, 2000);
  };

  return (
    <button
      type="button"
      className="btn btn-success btn-sm"
      onClick={() => copy(item.clientId + ':' + item.clientSecret)}
    >
      <i className={copyIconName} />
    </button>
  );
}

class DailyRemainingQuotas extends Component {
  state = {
    quotas: null,
  };

  componentDidMount() {
    BackOfficeServices.fetchRemainingApikeyQuotas(this.props.rawValue.clientId).then((quotas) => {
      console.log(quotas);
      this.setState({ quotas });
    });
  }

  render() {
    const quotas = this.state.quotas || {
      authorizedCallsPerSec: 0,
      currentCallsPerSec: 0,
      remainingCallsPerSec: 0,
      authorizedCallsPerDay: 0,
      currentCallsPerDay: 0,
      remainingCallsPerDay: 0,
      authorizedCallsPerMonth: 0,
      currentCallsPerMonth: 0,
      remainingCallsPerMonth: 0,
    };
    return [
      <div className="row mb-3">
        <label htmlFor="input-Throttling quota" className="col-xs-12 col-sm-2 col-form-label">
          Consumed daily calls
          <i
            className="far fa-question-circle"
            data-toggle="tooltip"
            data-placement="top"
            title=""
            data-original-title="The number of calls consumed today"
          />
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            <input
              type="number"
              className="form-control"
              id="input-Throttling quota"
              value={quotas.currentCallsPerDay}
            />
            <div className="input-group-text">calls consumed today</div>
          </div>
        </div>
      </div>,
      <div className="row mb-3">
        <label htmlFor="input-Throttling quota" className="col-xs-12 col-sm-2 col-form-label">
          Remaining daily calls
          <i
            className="far fa-question-circle"
            data-toggle="tooltip"
            data-placement="top"
            title=""
            data-original-title="The remaining number of calls for today"
          />
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            <input
              type="number"
              className="form-control"
              id="input-Throttling quota"
              value={quotas.remainingCallsPerDay}
            />
            <div className="input-group-text">calls remaining for today</div>
          </div>
        </div>
      </div>,
      <div className="row mb-3">
        <label htmlFor="input-Throttling quota" className="col-xs-12 col-sm-2 col-form-label">
          Consumed monthly calls
          <i
            className="far fa-question-circle"
            data-toggle="tooltip"
            data-placement="top"
            title=""
            data-original-title="The number of calls consumed this month"
          />
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            <input
              type="number"
              className="form-control"
              id="input-Throttling quota"
              value={quotas.currentCallsPerMonth}
            />
            <div className="input-group-text">calls consumed this month</div>
          </div>
        </div>
      </div>,
      <div className="row mb-3">
        <label htmlFor="input-Throttling quota" className="col-xs-12 col-sm-2 col-form-label">
          Remaining monthly calls
          <i
            className="far fa-question-circle"
            data-toggle="tooltip"
            data-placement="top"
            title=""
            data-original-title="The remaining number of calls for this month"
          />
        </label>
        <div className="col-sm-10">
          <div className="input-group">
            <input
              type="number"
              className="form-control"
              id="input-Throttling quota"
              value={quotas.remainingCallsPerMonth}
            />
            <div className="input-group-text">calls remaining for this month</div>
          </div>
        </div>
      </div>,
    ];
  }
}

const ApiKeysConstants = {
  formSchema: (that) => ({
    _loc: {
      type: 'location',
      props: {},
    },
    remainingQuotas: {
      type: DailyRemainingQuotas,
      props: {
        onRoutes: that.props.onRoutes,
        label: '',
      },
    },
    copyCredentials: {
      type: CopyCredentials,
      props: {
        label: '',
      },
    },
    resetSecret: {
      type: ResetSecret,
      props: {
        label: '',
      },
    },
    resetQuotas: {
      type: ResetQuotas,
      props: {
        onRoutes: that.props.onRoutes,
        label: '',
      },
    },
    clientId: {
      type: 'string',
      props: {
        label: 'ApiKey Id',
        placeholder: 'The ApiKey id',
        help: 'The id is a unique random key that will represent this API key',
        disabled: !window.location.pathname?.endsWith('/add'),
      },
    },
    clientSecret: {
      // type: 'string',
      type: ApikeySecret,
      props: {
        label: 'ApiKey Secret',
        placeholder: 'The ApiKey secret',
        help: 'The secret is a random key used to validate the API key',
        suffix: <i className="fas fa-copy" />,
        suffixStyle: { cursor: 'pointer' },
        suffixCb: (s) => navigator.clipboard.writeText(s),
      },
    },
    bearer: {
      type: ApikeyBearer,
      props: { lable: 'Bearer', thatProps: that.props },
    },
    both: { type: Both, props: { label: 'Both' } },
    curlCommand: { type: CurlCommand, props: { label: 'Curl Command', env: that.props.env } },
    basicAuth: { type: BasicAuthToken, props: { label: 'Basic Auth. Header' } },
    curlCommandWithBasicAuth: {
      type: CurlCommandWithBasicAuth,
      props: { label: 'Curl Command with Basic Auth. Header', env: that.props.env },
    },
    clientName: {
      type: 'string',
      props: {
        label: 'ApiKey Name',
        help: 'A name for the API key, used for debug purposes',
        placeholder: `The name of the client (ie. ${faker.name.firstName()} ${faker.name.lastName()}'s ApiKey)`,
      },
    },
    description: {
      type: 'string',
      props: {
        label: 'ApiKey description',
        help: 'A useful description for this apikey',
        placeholder: `A useful description for this apikey`,
      },
    },
    authorizedEntities: {
      type: 'array',
      props: {
        label: 'Authorized on',
        placeholder: 'The groups/services of the api key',
        help: 'The groups/services linked to this api key',
        valuesFrom: '/bo/api/groups-and-services',
        optionRenderer: (p) => {
          const colors = {
            service: 'bg-success',
            route: 'bg-info',
            'route-composition': 'bg-secondary',
            group: 'bg-warning',
          };
          return (
            <div style={{ display: 'flex' }}>
              <div style={{ width: 60 }}>
                <span className={`badge ${colors[p.kind] || 'bd-dark'}`}>{p.kind}</span>
              </div>
              <span>{p.label}</span>
            </div>
          );
        },
      },
    },
    enabled: {
      type: 'bool',
      props: {
        label: 'Enabled',
        placeholder: 'The ApiKey is enabled',
        help: 'If the API key is disabled, then any call using this API key will fail',
      },
    },
    validUntil: {
      type: 'datetime',
      props: {
        label: 'Valid until',
        help: 'Auto disable apikey after this date',
      },
    },
    readOnly: {
      type: 'bool',
      props: {
        label: 'Read only',
        placeholder: 'The ApiKey is read only',
        help: 'If the API key is in read only mode, every request done with this api key will only work for GET, HEAD, OPTIONS verbs',
      },
    },
    allowClientIdOnly: {
      type: 'bool',
      props: {
        label: 'Allow pass by clientid only',
        placeholder: 'Allow pass by clientid only',
        help: 'Here you allow client to only pass client id in a specific header in order to grant access to the underlying api',
      },
    },
    constrainedServicesOnly: {
      type: 'bool',
      props: {
        label: 'Constrained services only',
        help: 'This apikey can only be used on services using apikey routing constraints',
      },
    },
    metadata: {
      type: 'object',
      props: {
        label: 'Metadata',
        placeholderKey: 'Metadata Name',
        placeholderValue: 'Metadata value',
        help: 'Some useful metadata',
      },
    },
    tags: {
      type: 'array',
      props: {
        label: 'Tags',
        placeholder: 'admin',
        help: 'The tags assigned to this apikey',
      },
    },
    throttlingQuota: {
      type: 'number',
      props: {
        label: 'Throttling quota',
        placeholder: 'Authorized calls per second',
        suffix: 'calls per sec.',
        help: 'The authorized number of calls per second',
      },
    },
    dailyQuota: {
      type: 'number',
      props: {
        label: 'Daily quota',
        placeholder: 'Authorized calls per day',
        suffix: 'calls per day',
        help: 'The authorized number of calls per day',
      },
    },
    remainingDailyQuota: { type: 'label', props: { label: 'Daily quota' } },
    monthlyQuota: {
      type: 'number',
      props: {
        label: 'Monthly quota',
        placeholder: 'Authorized calls per month',
        suffix: 'calls per month',
        help: 'The authorized number of calls per month',
      },
    },
    remainingMonthlyQuota: { type: 'label', props: { label: 'Monthly quota' } },
    restrictions: { type: Restrictions, props: { path: 'restrictions' } },

    'rotation.enabled': {
      type: 'bool',
      props: {
        label: 'Enabled',
        help: 'Enabled automatic apikey secret rotation',
      },
    },
    'rotation.rotationEvery': {
      type: 'number',
      props: {
        label: 'Rotation every',
        placeholder: 'rotate secrets every',
        suffix: 'hours',
        help: 'rotate secrets every',
      },
    },
    'rotation.gracePeriod': {
      type: 'number',
      props: {
        label: 'Grace period',
        placeholder: 'period when both secrets can be used',
        suffix: 'hours',
        help: 'period when both secrets can be used',
      },
    },
    'rotation.nextSecret': {
      type: 'string',
      props: {
        disabled: true,
        label: 'Next client secret',
      },
    },
  }),
  columns: (that) => [
    {
      title: 'Name',
      filterId: 'clientName',
      content: (item) => item.clientName,
      wrappedCell: (v, item, table) => {
        if (that.state && that.state.env && that.state.env.adminApikeyId === item.clientId) {
          return (
            <span
              title="This apikey controls the API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. You might not want to delete it"
              className="badge bg-danger"
            >
              {item.clientName}
            </span>
          );
        }
        return item.clientName;
      },
    },
    {
      title: 'ApiKey Id',
      filterId: 'clientId',
      content: (item) => item.clientId,
    },
    {
      title: 'Credentials',
      style: { textAlign: 'center', width: 90 },
      notFilterable: true,
      content: (item) => item.clientName,
      cell: (v, item, table) => <CopyFromLineItem item={item} table={table} />,
    },
    {
      title: 'Enabled',
      filterId: 'enabled',
      style: {
        display: 'flex',
        alignItems: 'center',
        flexDirection: 'column-reverse',
        justifyContent: 'flex-start',
      },
      notFilterable: true,
      content: (item) => item.enabled,
      cell: (v, item, table) => (
        <SimpleBooleanInput
          value={item.enabled}
          onChange={(value) => {
            nextClient
              .forEntityNext(nextClient.ENTITIES.APIKEYS)
              .update(
                {
                  ...item,
                  enabled: value,
                },
                'clientId'
              )
              .then(() => table.update());
          }}
        />
      ),
    },
    {
      title: 'Stats',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      content: (item) => (
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={(e) => {
            if (e && e.preventDefault) {
              e.preventDefault();
              e.stopPropagation();
            }
            if (window.location.pathname.indexOf('/bo/dashboard/routes') === 0) {
              window.location = `/bo/dashboard/lines/prod/services/${that.props.params.routeId}/apikeys/edit/${item.clientId}/stats`;
            } else {
              window.location = `/bo/dashboard/lines/prod/services/${
                that.state.service ? that.state.service.id : '-'
              }/apikeys/edit/${item.clientId}/stats`;
            }
          }}
        >
          <i className="fas fa-chart-bar" />
        </button>
      ),
    },
  ],
  formFlow: [
    //'>>>Location',
    '_loc',
    'clientId',
    'clientSecret',
    'bearer',
    'clientName',
    'description',
    'validUntil',
    'copyCredentials',
    'resetSecret',
    '---',
    'enabled',
    'readOnly',
    'allowClientIdOnly',
    'constrainedServicesOnly',
    //'>>>Authorized on',
    '---',
    'authorizedEntities',
    '>>> Metadata and tags',
    'tags',
    'metadata',
    '>>>Automatic secret rotation',
    'rotation.enabled',
    'rotation.rotationEvery',
    'rotation.gracePeriod',
    'rotation.nextSecret',
    '>>>Restrictions',
    'restrictions',
    '>>>Call examples',
    'curlCommand',
    'basicAuth',
    'curlCommandWithBasicAuth',
    '>>>Quotas',
    'throttlingQuota',
    'dailyQuota',
    'monthlyQuota',
    '>>>Quotas consumption',
    'remainingQuotas',
    'resetQuotas',
  ],
};

export class ServiceApiKeysPage extends Component {
  state = {
    service: null,
    env: this.props.env,
    loading: true,
  };

  onRoutes = window.location.pathname.indexOf('/bo/dashboard/routes') === 0;

  sidebarContent(name) {
    if (this.onRoutes) {
      return (
        <DesignerSidebar
          route={{ id: this.props.params.routeId, name }}
          setSidebarContent={this.props.setSidebarContent}
        />
      );
    }
    return (
      <ServiceSidebar
        env={this.state.service.env}
        serviceId={this.props.params.serviceId}
        name={name}
      />
    );
  }

  componentWillUnmount() {
    if (this.props.setSidebarContent) this.props.setSidebarContent(null);
  }

  componentDidMount() {
    const fu = this.onRoutes
      ? nextClient.forEntityNext(nextClient.ENTITIES.ROUTES).findById(this.props.params.routeId)
      : nextClient
          .forEntityNext(nextClient.ENTITIES.SERVICES)
          .findById(this.props.params.serviceId);
    fu.then((service) => {
      this.onRoutes
        ? this.props.setTitle(this.props.title || `Routes Apikeys`)
        : this.props.setTitle(`Service Apikeys`);
      this.setState({ service, loading: false }, () => {
        this.props.setSidebarContent(this.sidebarContent(service.name));
        if (this.table) {
          this.table.readRoute();
          this.table.update();
        }
      });
    }).catch((_) => {
      this.setState({ loading: false });
    });
  }

  fetchAllApiKeys = () => {
    return BackOfficeServices.fetchApiKeysForPage(
      this.props.params.serviceId || this.props.params.routeId
    );
  };

  createItem = (ak) => {
    delete ak.authorizations;
    delete ak.authorizedGroup;
    return BackOfficeServices.createApiKey(
      this.props.params.serviceId,
      this.props.params.routeId,
      ak
    );
  };

  updateItem = (ak) => {
    // return BackOfficeService;
    delete ak.authorizations;
    delete ak.authorizedGroup;
    return BackOfficeServices.updateApiKey(
      this.props.params.serviceId,
      this.props.params.routeId,
      ak
    );
  };

  deleteItem = (ak) => {
    return BackOfficeServices.deleteApiKey(
      this.props.params.serviceId,
      this.props.params.routeId,
      ak
    );
  };

  render() {
    return (
      <Loader loading={this.state.loading}>
        <Table
          parentProps={this.props}
          selfUrl={
            this.onRoutes
              ? // ? `services/${this.props.params.routeId}/apikeys`
                `routes/${this.props.params.routeId}/apikeys`
              : `lines/${this.props.params.lineId}/services/${this.props.params.serviceId}/apikeys`
          }
          defaultTitle={this.onRoutes ? 'Route Apikeys' : 'Service Apikeys'}
          defaultValue={() =>
            nextClient
              .forEntityNext(nextClient.ENTITIES.APIKEYS)
              .template()
              .then((apk) => ({
                ...apk,
                clientName: `${faker.name.firstName()} ${faker.name.lastName()}'s api-key`,
                authorizedEntities: (this.state.service.groups || []).map((g) => 'group_' + g),
              }))
          }
          _defaultValue={() => ({
            clientId: faker.random.alphaNumeric(16),
            clientSecret: faker.random.alphaNumeric(64),
            clientName: `${faker.name.firstName()} ${faker.name.lastName()}'s api-key`,
            description: '',
            enabled: true,
            throttlingQuota: 100,
            dailyQuota: 1000000,
            monthlyQuota: 1000000000000000000,
            authorizedEntities: this.state.service.groups.map((g) => 'group_' + g),
          })}
          itemName="ApiKey"
          formSchema={ApiKeysConstants.formSchema(this)}
          formFlow={ApiKeysConstants.formFlow}
          columns={ApiKeysConstants.columns(this)}
          fetchItems={this.fetchAllApiKeys}
          updateItem={this.updateItem}
          deleteItem={this.deleteItem}
          createItem={this.createItem}
          stayAfterSave={true}
          injectTable={(table) => (this.table = table)}
          showActions={true}
          displayTrash={(item) => this.state.env && this.state.env.adminApikeyId === item.clientId}
          showLink={false}
          rowNavigation={true}
          export={true}
          kubernetesKind="apim.otoroshi.io/ApiKey"
          navigateTo={(item) => {
            if (this.onRoutes) {
              this.props.history.push(
                `/routes/${this.props.params.routeId}/apikeys/edit/${item.clientId}`
                // `/apikeys/edit/${item.clientId}`
              );
            } else {
              this.props.history.push(
                `/lines/${this.props.params.lineId}/services/${this.props.params.serviceId}/apikeys/edit/${item.clientId}?group=${item.id}`
              );
            }
          }}
          itemUrl={(i) => {
            if (this.onRoutes) {
              return `/bo/dashboard/routes/${this.props.params.routeId}/apikeys/edit/${i.clientId}`;
            } else {
              return `/bo/dashboard/lines/${this.props.params.lineId}/services/${this.props.params.serviceId}/apikeys/edit/${i.clientId}`;
            }
          }}
          extractKey={(item) => item.clientId}
        />
      </Loader>
    );
  }
}

export class ApiKeysPage extends Component {
  state = {
    service: null,
    env: this.props.env,
    loading: true,
    fields: {
      enabled: true,
      clientId: true,
      clientName: true,
      stats: true,
      credentials: true,
    },
  };

  ref = React.createRef();

  componentDidMount() {
    this.props.setTitle(`Apikeys`);

    this.loadFields();
  }

  loadFields = () => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR || '{}'));

      if (values.apikeys)
        this.setState({
          fields: values.apikeys,
        });
    } catch (e) {
      // console.log(e);
    } finally {
      this.setState({ loading: false });
    }
  };

  saveFields = (fields) => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR) || '{}');

      localStorage.setItem(
        FIELDS_SELECTOR,
        JSON.stringify({
          ...values,
          apikeys: fields,
        })
      );
    } catch (e) {
      // console.log(e);
    }
  };

  onFieldsChange = (fields) => {
    this.setState(
      {
        fields,
      },
      () => {
        this.saveFields(fields);
        if (this.ref.current) {
          this.ref.current.update();
        }
      }
    );
  };

  fetchAllApiKeys = (paginationState) => {
    return nextClient.forEntityNext(nextClient.ENTITIES.APIKEYS).findAllWithPagination({
      ...paginationState,
      fields: [
        ...Object.keys(this.state.fields).map((field) =>
          this.state.fields[field] ? field : undefined
        ),
      ].filter((c) => c),
    });
  };

  fetchTemplate = () => nextClient.forEntityNext(nextClient.ENTITIES.APIKEYS).template();

  createItem = (ak) => {
    delete ak.authorizations;
    delete ak.authorizedGroup;
    // return BackOfficeServices.createStandaloneApiKey(ak);
    return nextClient.forEntityNext(nextClient.ENTITIES.APIKEYS).create(ak);
  };

  updateItem = (ak) => {
    delete ak.authorizations;
    delete ak.authorizedGroup;
    // return BackOfficeServices.updateStandaloneApiKey(ak);
    return nextClient.forEntityNext(nextClient.ENTITIES.APIKEYS).update(ak, 'clientId');
  };

  deleteItem = (ak) => {
    // return BackOfficeServices.deleteStandaloneApiKey(ak);
    return nextClient.forEntityNext(nextClient.ENTITIES.APIKEYS).delete(ak, 'clientId');
  };

  isAnObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

  buildColumn = (field) => {
    return {
      title: firstLetterUppercase(field.split('.').slice(-1)[0]),
      filterId: firstLetterUppercase(field),
      content: (item) => {
        const value = field.split('.').reduce((r, k) => (r ? r[k] : {}), item);
        if (Array.isArray(value)) {
          return (value || []).map((r) => JSON.stringify(r, null, 2)).join(',');
        } else if (this.isAnObject(value)) {
          return Object.entries(value || {})
            .map(([key, value]) => `${key}:${JSON.stringify(value, null, 2)}`)
            .join(' - ');
        } else if (typeof value == 'boolean') {
          return value ? 'Active' : 'Disabled';
        } else {
          return '' + value;
        }
      },
      notSortable: true,
      notFilterable: true,
    };
  };

  render() {
    const { fields } = this.state;

    const lowercaseFields = Object.entries(fields).map(([key, value]) => [
      key.toLowerCase(),
      value,
    ]);

    const columns = [
      ...ApiKeysConstants.columns(this),
      ...Object.keys(this.state.fields)
        .filter((f) => !CORE_FIELDS.includes(f))
        .map(this.buildColumn),
    ].filter((c) => {
      if (!c) return false;

      return lowercaseFields.find(([key, value]) => {
        if (key === c.title?.toLowerCase() || key === c.filterId?.toLowerCase()) return value;
        return false;
      });
    });

    console.log(columns, fields);

    return (
      <Loader loading={this.state.loading}>
        <Table
          ref={this.ref}
          parentProps={this.props}
          selfUrl={`apikeys`}
          defaultTitle="All apikeys"
          defaultValue={() =>
            nextClient
              .forEntityNext(nextClient.ENTITIES.APIKEYS)
              .template()
              .then((apk) => ({
                ...apk,
                clientName: `${faker.name.firstName()} ${faker.name.lastName()}'s api-key`,
                authorizedEntities: [],
              }))
          }
          _defaultValue={() => ({
            clientId: faker.random.alphaNumeric(16),
            clientSecret: faker.random.alphaNumeric(64),
            clientName: `${faker.name.firstName()} ${faker.name.lastName()}'s api-key`,
            description: '',
            enabled: true,
            throttlingQuota: 100,
            dailyQuota: 1000000,
            monthlyQuota: 1000000000000000000,
            authorizedEntities: [],
          })}
          itemName="Apikey"
          formSchema={ApiKeysConstants.formSchema(this)}
          formFlow={ApiKeysConstants.formFlow}
          columns={columns}
          fields={fields}
          coreFields={CORE_FIELDS}
          addField={(fieldPath) => {
            const newFields = {
              ...fields,
              [fieldPath]: true,
            };
            this.setState(
              {
                fields: newFields,
              },
              () => {
                this.onFieldsChange(newFields);
              }
            );
          }}
          removeField={(fieldPath) => {
            const { [fieldPath]: _, ...newFields } = fields;

            this.setState(
              {
                fields: newFields,
              },
              () => {
                this.onFieldsChange(newFields);
              }
            );
          }}
          onToggleField={(column, enabled) => {
            const newFields = {
              ...fields,
              [column]: enabled,
            };
            this.setState(
              {
                fields: newFields,
              },
              () => {
                this.onFieldsChange(newFields);
              }
            );
          }}
          fetchTemplate={this.fetchTemplate}
          fetchItems={this.fetchAllApiKeys}
          updateItem={this.updateItem}
          deleteItem={this.deleteItem}
          createItem={this.createItem}
          stayAfterSave={true}
          showActions={true}
          displayTrash={(item) => this.state.env && this.state.env.adminApikeyId === item.clientId}
          showLink={false}
          rowNavigation={true}
          export={true}
          kubernetesKind="apim.otoroshi.io/ApiKey"
          navigateTo={(item) =>
            this.props.history.push({
              pathname: `/apikeys/edit/${item.clientId}`,
              query: { group: item.id, groupName: item.name },
            })
          }
          itemUrl={(i) => `/bo/dashboard/apikeys/edit/${i.clientId}`}
          extractKey={(item) => item.clientId}
        />
      </Loader>
    );
  }
}
