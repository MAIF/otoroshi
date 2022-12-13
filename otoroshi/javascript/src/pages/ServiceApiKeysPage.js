import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, SelectInput, SimpleBooleanInput } from '../components/inputs';
import { ServiceSidebar } from '../components/ServiceSidebar';
import faker from 'faker';
import { Restrictions } from '../components/Restrictions';

import DesignerSidebar from './RouteDesigner/Sidebar';

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
        onClick={(e) => changeValue('clientSecret', 'apks_' + faker.random.alphaNumeric(64))}>
        <i className="fas fa-sync" /> Reset secret
      </button>
    </div>
  </div>
);

class ResetQuotas extends Component {
  resetQuotas = (e) => {
    e.preventDefault();
    BackOfficeServices.resetRemainingQuotas(
      this.props.rawValue.authorizedGroup,
      this.props.rawValue.clientId
    ).then(() => {
      window.location.reload();
    });
  };

  render() {
    console.log(this.props);
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
            onClick={(e) => {
              this.clipboard.select();
              document.execCommand('Copy');
            }}>
            <i className="fas fa-copy" /> Copy credentials to clipboard
          </button>
        </div>
      </div>
    );
  }
}

class CopyFromLineItem extends Component {
  render() {
    const item = this.props.item;
    return (
      <button
        type="button"
        className="btn btn-sm btn-info"
        onClick={(e) => {
          this.clipboard.select();
          document.execCommand('Copy');
        }}>
        <i className="fas fa-copy" />
        <input
          type="text"
          ref={(r) => (this.clipboard = r)}
          style={{ position: 'fixed', left: 0, top: -250 }}
          value={item.clientId + ':' + item.clientSecret}
          alt="copy credentials"
        />
      </button>
    );
  }
}

class DailyRemainingQuotas extends Component {
  state = {
    quotas: null,
  };

  componentDidMount() {
    BackOfficeServices.fetchRemainingQuotas(
      this.props.rawValue.authorizedGroup,
      this.props.rawValue.clientId
    ).then((quotas) => {
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
        label: '',
      },
    },
    clientId: {
      type: 'string',
      props: {
        label: 'ApiKey Id',
        placeholder: 'The ApiKey id',
        help: 'The id is a unique random key that will represent this API key',
      },
    },
    clientSecret: {
      type: 'string',
      props: {
        label: 'ApiKey Secret',
        placeholder: 'The ApiKey secret',
        help: 'The secret is a random key used to validate the API key',
      },
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
        help:
          'If the API key is in read only mode, every request done with this api key will only work for GET, HEAD, OPTIONS verbs',
      },
    },
    allowClientIdOnly: {
      type: 'bool',
      props: {
        label: 'Allow pass by clientid only',
        placeholder: 'Allow pass by clientid only',
        help:
          'Here you allow client to only pass client id in a specific header in order to grant access to the underlying api',
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
              className="badge bg-danger">
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
      title: 'Active',
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
            BackOfficeServices.updateStandaloneApiKey({
              ...item,
              enabled: value,
            }).then(() => table.update());
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
          }}>
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

  componentDidMount() {
    const fu = this.onRoutes
      ? BackOfficeServices.nextClient.fetch('routes', this.props.params.routeId)
      : BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId);
    fu.then((service) => {
      this.onRoutes
        ? this.props.setTitle(`Routes Apikeys`)
        : this.props.setTitle(`Service Apikeys`);
      this.setState({ service }, () => {
        this.props.setSidebarContent(this.sidebarContent(service.name));
        if (this.table) {
          this.table.readRoute();
          this.table.update();
        }
      });
    });
  }

  fetchAllApiKeys = () => {
    return BackOfficeServices.fetchApiKeysForPage(
      this.props.params.serviceId || this.props.params.routeId
    );
  };

  createItem = (ak) => {
    return BackOfficeServices.createApiKey(
      this.props.params.serviceId,
      this.props.params.routeId,
      ak
    );
  };

  updateItem = (ak) => {
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
          BackOfficeServices.createNewApikey().then((apk) => ({
            ...apk,
            clientName: `${faker.name.firstName()} ${faker.name.lastName()}'s api-key`,
            authorizedEntities: this.state.service.groups.map((g) => 'group_' + g),
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
        kubernetesKind="ApiKey"
        navigateTo={(item) => {
          if (this.onRoutes) {
            console.log(item);
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
    );
  }
}

export class ApiKeysPage extends Component {
  state = {
    service: null,
    env: this.props.env,
  };

  componentDidMount() {
    this.props.setTitle(`All apikeys`);
  }

  fetchAllApiKeys = (paginationState) => {
    return BackOfficeServices.fetchAllApikeys({
      ...paginationState,
      fields: ['id', 'enabled', 'clientId', 'clientName', 'clientSecret'],
    });
  };

  createItem = (ak) => {
    return BackOfficeServices.createStandaloneApiKey(ak);
  };

  updateItem = (ak) => {
    return BackOfficeServices.updateStandaloneApiKey(ak);
  };

  deleteItem = (ak) => {
    return BackOfficeServices.deleteStandaloneApiKey(ak);
  };

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl={`apikeys`}
        defaultTitle="All apikeys"
        defaultValue={() =>
          BackOfficeServices.createNewApikey().then((apk) => ({
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
        columns={ApiKeysConstants.columns(this)}
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
        kubernetesKind="ApiKey"
        navigateTo={(item) =>
          this.props.history.push({
            pathname: `/apikeys/edit/${item.clientId}`,
            query: { group: item.id, groupName: item.name },
          })
        }
        itemUrl={(i) => `/bo/dashboard/apikeys/edit/${i.clientId}`}
        extractKey={(item) => item.clientId}
      />
    );
  }
}
