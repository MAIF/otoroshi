import React, { useState } from 'react';
import faker from 'faker';
import { CodeInput } from '@maif/react-forms';
import { FeedbackButton } from './FeedbackButton';
import {
  BooleanInput,
  Help,
  ObjectInput,
  SelectInput,
  SimpleBooleanInput,
  TextInput,
} from '../../components/inputs';

const castValue = (value, type) => {
  if (type === 'String') return value;
  else {
    try {
      if (type === 'Number') return parseInt(value);
      else return JSON.parse(value);
    } catch (err) {
      return value;
    }
  }
};

export default class MocksDesigner extends React.Component {
  state = {
    resources: [],
    endpoints: [],
  };

  static getDerivedStateFromProps(props, state) {
    if (props.route) {
      const plugin = props.route.plugins.find(
        (p) => p.plugin === 'cp:otoroshi.next.plugins.MockResponses'
      )?.config;

      console.log(plugin);
      if (plugin && plugin.form_data) return { ...plugin.form_data };
    }
    return {
      resources: [],
      endpoints: [],
    };
  }

  saveRoute = (res) =>
    Promise.resolve(
      this.props.saveRoute({
        ...this.props.route,
        plugins: this.props.route.plugins.map((p) => {
          const config = {
            ...this.state,
            ...(res || {}),
          };
          if (p.plugin === 'cp:otoroshi.next.plugins.MockResponses')
            return {
              ...p,
              config: {
                ...p.config,
                responses: this.configToResponses(config),
                form_data: config,
              },
            };
          return p;
        }),
      })
    );

  configToResponses = (config) =>
    config.endpoints.map(({ path, method, status, headers, body }) => ({
      path,
      method,
      status,
      headers,
      body: this.stringify(body),
    }));

  stringify = (body) => {
    if (typeof body === 'object' && !Array.isArray(body) && body !== null)
      try {
        return JSON.stringify(body);
      } catch (err) {
        return body;
      }
    return body;
  };

  setAndSave = (res) => this.saveRoute(res);

  showForm = (title, idx, elementName, render) => {
    const resource = this.state[elementName].find((_, i) => i === idx);
    window
      .popup(title, (ok, cancel) => render(ok, cancel, resource), {
        additionalClass: 'designer-modal-dialog',
      })
      .then((data) => {
        if (data) {
          const { value, idx } = data;
          this.setAndSave({
            [elementName]: Number.isFinite(idx)
              ? this.state[elementName].map((r, i) => {
                  if (i === idx) return value;
                  return r;
                })
              : [...this.state[elementName], value],
          });
        }
      });
  };

  showResourceForm = (idx) =>
    this.showForm('Create a new resource', idx, 'resources', (ok, cancel, resource) => (
      <NewResource
        confirm={ok}
        cancel={cancel}
        resource={resource}
        idx={idx}
        resources={this.state.resources}
      />
    ));

  showEndpointForm = (idx) =>
    this.showForm('Create a new endpoint', idx, 'endpoints', (ok, cancel, endpoint) => (
      <NewEndpoint
        confirm={ok}
        cancel={cancel}
        resources={this.state.resources}
        endpoint={endpoint}
        idx={idx}
      />
    ));

  showGenerateEndpointForm = () => {
    window
      .popup(
        'Generate an endpoint',
        (ok, cancel) => (
          <GenerateEndpoint resources={this.state.resources} confirm={ok} cancel={cancel} />
        ),
        { additionalClass: 'designer-modal-dialog' }
      )
      .then((generateEndpoints) => {
        this.setAndSave({
          endpoints: [...this.state.endpoints, ...generateEndpoints],
        });
      });
  };

  removeEndpoint = (idx) => {
    this.setAndSave({
      endpoints: this.state.endpoints.filter((_, j) => j !== idx),
    });
  };

  removeResource = (idx) => {
    this.setAndSave({
      resources: this.state.resources.filter((_, j) => j !== idx),
    });
  };

  calculateResource = (resource) => {
    const newItem = (r) =>
      (r.schema || []).reduce(
        (acc, item) => ({
          ...acc,
          ...this.calculateField(item),
        }),
        JSON.parse(resource.additional_data || '{}')
      );
    return newItem(resource);
  };

  fakeValue = (item) => {
    try {
      return castValue(item.value.split('.').reduce((a, c) => a[c], faker)(), item.field_type);
    } catch (err) {
      return castValue(item.value, item.field_type);
    }
  };

  calculateField = (item) => ({
    [item.field_name]:
      item.field_type === 'Child'
        ? this.calculateResource(this.state.resources.find((f) => f.name === item.value))
        : this.fakeValue(item),
  });

  generateFakerValues = (endpoint) => {
    const { resourceList, body } = endpoint;

    if (endpoint.resource) {
      const resource = this.state.resources.find((f) => f.name === endpoint.resource);
      if (!resource) return {};

      const newItem = (r) =>
        (r.schema || []).reduce(
          (acc, item) => ({
            ...acc,
            ...this.calculateField(item),
          }),
          JSON.parse(resource.additional_data || '{}')
        );
      if (resourceList) return Array.from({ length: 10 }, (_, i) => newItem(resource));
      return newItem(resource);
    } else {
      if (resourceList) return Array.from({ length: 10 }, (_, i) => body);
      return body;
    }
  };

  showData = (idx) => {
    window
      .popup(
        'Edit/replace data for users resource. Data must be an array and a valid JSON.',
        (ok, cancel) => (
          <Data body={this.state.endpoints[idx].body} idx={idx} confirm={ok} cancel={cancel} />
        ),
        { additionalClass: 'designer-modal-dialog' }
      )
      .then((res) => {
        this.setAndSave({
          endpoints: this.state.endpoints.map((endpoint, i) => {
            if (i === res.idx) return { ...endpoint, body: res.body };
            return endpoint;
          }),
        });
      });
  };

  generateData = () =>
    this.setAndSave({
      endpoints: this.state.endpoints.map((endpoint) => ({
        ...endpoint,
        body: endpoint.body || this.generateFakerValues(endpoint),
      })),
    });

  resetData = () => {
    window.newConfirm(`Are you sure you reset all data ?`).then((ok) => {
      if (ok) {
        this.setAndSave({
          endpoints: this.state.endpoints.map((endpoint) => ({
            ...endpoint,
            body: null,
          })),
        }).then(this.generateData);
      }
    });
  };

  render() {
    const { route, hide } = this.props;

    if (!route) return null;

    return (
      <div className="graphql-form p-3 pe-2 flex-column" style={{ overflowY: 'scroll' }}>
        <Header hide={hide} />

        <div className="m-3 ms-0">
          <div className="d-flex-between">
            <div>
              <button className="btn btn-sm btn-info" onClick={() => this.showResourceForm()}>
                <i className="fas fa-plus-circle me-1" />
                New resource
              </button>
              <button className="btn btn-sm btn-info mx-1" onClick={() => this.showEndpointForm()}>
                <i className="fas fa-plus-circle me-1" />
                New endpoint
              </button>
              <button
                className="btn btn-sm btn-info"
                onClick={() => this.showGenerateEndpointForm()}>
                <i className="fas fa-hammer me-1" />
                Generate endpoint
              </button>
            </div>
          </div>
          <div className="row my-3">
            <div className="col-md-4">
              <h3>Resources</h3>
              <div className="mt-3 flex-column">
                {this.state.resources.map((resource, idx) => {
                  return (
                    <div
                      className={`mt-${idx === 0 ? 0 : 1} d-flex-between endpoint`}
                      key={resource.name}
                      onClick={() => this.showResourceForm(idx)}>
                      <label>{resource.name}</label>
                      <button
                        className="btn btn-sm btn-danger"
                        onClick={() => this.removeResource(idx)}>
                        <i className="fas fa-trash" />
                      </button>
                    </div>
                  );
                })}
              </div>
              {this.state.resources.length === 0 && <span>No resources available</span>}
            </div>
            <div className="col-md-8">
              <div className="d-flex-between">
                <h3>Endpoints</h3>
                <div>
                  <FeedbackButton
                    className="btn btn-sm btn-save me-1"
                    onPress={this.generateData}
                    icon={() => <i className="fas fa-hammer me-1" />}
                    text="GENERATE ALL"
                  />
                  <button className="btn btn-sm btn-save" onClick={this.resetData}>
                    <i className="fas fa-times me-1" />
                    RESET ALL
                  </button>
                </div>
              </div>
              <div className="mt-3">
                {this.state.endpoints
                  .sort((a, b) => a.path.localeCompare(b.path))
                  .map((endpoint, idx) => {
                    return (
                      <div
                        className="d-flex-between mt-1 endpoint"
                        key={`${endpoint.path}${idx}`}
                        onClick={() => this.showEndpointForm(idx)}>
                        <div className="d-flex-between">
                          <div style={{ minWidth: '60px' }}>
                            <span
                              className={`badge me-1`}
                              style={{ backgroundColor: HTTP_COLORS[endpoint.method] }}>
                              {endpoint.method}
                            </span>
                          </div>
                          <span>{endpoint.path}</span>
                        </div>
                        <div className="d-flex-between">
                          {!endpoint.body && !endpoint.resource && (
                            <div className="mx-1 d-flex-between endpoint-helper">
                              <Help
                                text="Missing data, body or resource"
                                icon="fas fa-exclamation-triangle"
                                iconColor="#D5443F"
                              />
                            </div>
                          )}
                          <button
                            className="btn btn-sm btn-info me-1"
                            onClick={(e) => {
                              e.stopPropagation();
                              this.showData(idx);
                            }}>
                            <i className="fas fa-eye" />
                          </button>
                          <button
                            className="btn btn-sm btn-danger"
                            onClick={(e) => {
                              e.stopPropagation();
                              window.newConfirm('Delete this endpoint ?').then((ok) => {
                                if (ok) {
                                  e.stopPropagation();
                                  this.removeEndpoint(idx);
                                }
                              });
                            }}>
                            <i className="fas fa-trash" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                {this.state.endpoints.length === 0 && <span>No endpoints available</span>}
              </div>
            </div>
          </div>
        </div>

        <FeedbackButton
          className="ms-auto me-2 mt-auto mb-2"
          onPress={this.saveRoute}
          text="Save plugin"
          icon={() => <i className="fas fa-paper-plane" />}
        />
      </div>
    );
  }
}

const Data = ({ idx, body, confirm, cancel }) => {
  const [res, setRes] = useState(body);

  return (
    <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
      <CodeInput value={res} onChange={setRes} />

      <div className="d-flex mt-3">
        <button className="btn btn-sm btn-danger me-1 ms-auto" onClick={cancel}>
          Cancel
        </button>
        <button
          className="btn btn-sm btn-save"
          onClick={() =>
            confirm({
              body: res,
              idx,
            })
          }>
          Save
        </button>
      </div>
    </div>
  );
};

const GenerateEndpoint = ({ resources, confirm, cancel }) => {
  const [resource, setResource] = useState('');
  const [endpoints, setEndpoints] = useState([]);

  const onResourceChange = (name, resourceName) => {
    setEndpoints([
      {
        method: 'GET',
        path: `/${name}s`,
        enabled: true,
        resource: resourceName,
        resourceList: true,
        status: 200,
      },
      { method: 'GET', path: `/${name}s/:id`, enabled: true, resource: resourceName, status: 200 },
      { method: 'POST', path: `/${name}s`, enabled: true, resource: resourceName, status: 201 },
      { method: 'PUT', path: `/${name}s/:id`, enabled: true, resource: resourceName, status: 204 },
      {
        method: 'DELETE',
        path: `/${name}s/:id`,
        enabled: true,
        resource: resourceName,
        status: 202,
      },
    ]);
  };

  return (
    <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
      <div className="row">
        <label>Select a resource</label>
        <SelectInput
          flex={true}
          value={resource}
          onChange={(e) => {
            setResource(e);
            onResourceChange(e, e);
          }}
          possibleValues={resources.map((r) => r.name)}
        />
      </div>
      <div className="row">
        <label>or enter a singular resource name</label>
        <TextInput
          flex={true}
          value={resource}
          onChange={(e) => {
            setResource(e);
            onResourceChange(e);
          }}
        />
      </div>
      <div className="row">
        <label>Generated endpoints</label>
        {endpoints.map((endpoint, i) => (
          <div className="d-flex-between generated-endpoint">
            <div style={{ minWidth: '60px' }} className="me-1 mb-3">
              <span className="badge" style={{ backgroundColor: HTTP_COLORS[endpoint.method] }}>
                {endpoint.method}
              </span>
            </div>
            <TextInput
              flex={true}
              value={endpoint.path}
              onChange={(path) =>
                setEndpoints(
                  endpoints.map((e, j) => {
                    if (j === i) return { ...e, path };
                    return e;
                  })
                )
              }
            />
            <div className="mb-3 ms-2">
              <SimpleBooleanInput
                value={endpoint.enabled}
                onChange={(enabled) =>
                  setEndpoints(
                    endpoints.map((e, j) => {
                      if (j === i) return { ...e, enabled };
                      return e;
                    })
                  )
                }
              />
            </div>
          </div>
        ))}
      </div>
      <div className="d-flex-between">
        <button className="btn btn-sm btn-danger ms-auto me-1" onClick={cancel}>
          Cancel
        </button>
        <button
          className="btn btn-sm btn-save"
          onClick={() => confirm(endpoints.filter((d) => d.enabled))}>
          Create
        </button>
      </div>
    </div>
  );
};

class NewResource extends React.Component {
  state = {
    name: this.props.resource?.name || '',
    schema: this.props.resource?.schema || [],
    objectTemplate: this.props.resource?.additional_data || {},
  };

  onSchemaFieldChange = (idx, field, value, callback) => {
    this.setState(
      {
        schema: this.state.schema.map((s, j) => {
          if (idx === j) return { ...s, [field]: value };
          return s;
        }),
      },
      callback
    );
  };

  emptyField = (className) => (
    <div className={`row mb-3 flex ${className}`}>
      <div className="col-sm-12 d-flex">
        <label className="flex text-center">-</label>
      </div>
    </div>
  );

  render() {
    const { name, schema, objectTemplate } = this.state;

    return (
      <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
        <TextInput
          label="Name"
          help="Enter meaningful resource name, it will be used to generate API endpoints."
          placeholder="Example: User, Comment, Article..."
          value={name}
          onChange={(v) => this.setState({ name: v })}
        />
        <div className="row mb-3">
          <label htmlFor={`input-schema`} className="col-xs-12 col-sm-2 col-form-label">
            Schema (optional){' '}
            <Help text="Define Resource schema, it will be used to generate mock data." />
          </label>
          <div className="col-sm-10">
            <div className="d-flex-between mb-2">
              <label className="flex text-center">Field name</label>
              <label className="flex text-center">Field type</label>
              <label className="flex text-center">Faker value</label>
              <label className="flex text-center">Value</label>
            </div>
            {schema.map((s, i) => {
              const { field_name, field_type, value } = s;
              const type = field_type;
              return (
                <div className="d-flex-between" key={`schema-${i}`}>
                  <TextInput
                    flex={true}
                    value={field_name}
                    placeholder="Field name"
                    onChange={(v) => this.onSchemaFieldChange(i, 'field_name', v)}
                  />
                  <SelectInput
                    flex={true}
                    className="mx-1"
                    value={type}
                    onChange={(v) =>
                      this.onSchemaFieldChange(i, 'field_type', v, () =>
                        this.onSchemaFieldChange(i, 'value', '')
                      )
                    }
                    possibleValues={[
                      'String',
                      'Number',
                      'Boolean',
                      'Object',
                      'Array',
                      'Date',
                      'Child',
                    ]}
                  />

                  {type && type !== 'Child' ? (
                    <SelectInput
                      flex={true}
                      value={value}
                      className="me-1"
                      onChange={(v) => this.onSchemaFieldChange(i, 'value', v)}
                      possibleValues={FakerOptions}
                    />
                  ) : (
                    this.emptyField('me-1')
                  )}
                  {type === 'Child' && (
                    <SelectInput
                      flex={true}
                      value={value}
                      onChange={(v) => this.onSchemaFieldChange(i, 'value', v)}
                      possibleValues={this.props.resources.map((a) => a.name)}
                    />
                  )}
                  {!type ? (
                    this.emptyField()
                  ) : !['Child', 'Faker.js'].includes(type) ? (
                    <TextInput
                      flex={true}
                      value={value}
                      placeholder="or value"
                      onChange={(v) => this.onSchemaFieldChange(i, 'value', castValue(v, type))}
                    />
                  ) : type !== 'Child' ? (
                    this.emptyField()
                  ) : null}
                </div>
              );
            })}
            <button
              className="btn btn-sm btn-save"
              onClick={() =>
                this.setState({
                  schema: [
                    ...schema,
                    {
                      field_name: '',
                      type: 'Faker.js',
                      value: 'name.firstName',
                    },
                  ],
                })
              }>
              <i className="fas fa-plus" />
            </button>
          </div>
        </div>
        <div className="row mb-3">
          <label htmlFor={`input-object-template`} className="col-xs-12 col-sm-2 col-form-label">
            Additional data (optional){' '}
            <Help text="To define more complex structure for your data use JSON template. You can reference Faker.js methods using `$`." />
          </label>
          <div className="col-sm-10">
            <CodeInput
              value={objectTemplate}
              onChange={(v) => this.setState({ objectTemplate: v })}
            />
          </div>
        </div>

        <div className="d-flex-between">
          <button className="btn btn-sm btn-danger ms-auto me-1" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            className="btn btn-sm btn-save"
            onClick={() =>
              this.props.confirm({
                value: this.state,
                idx: this.props.idx,
              })
            }>
            {this.props.resource ? 'Save' : 'Create'}
          </button>
        </div>
      </div>
    );
  }
}

class NewEndpoint extends React.Component {
  state = this.props.endpoint || {
    method: 'GET',
    path: '/',
    status: 200,
    body: null,
    resource: '',
    resourceList: false,
    headers: {},
  };

  render() {
    const { method, path, status, body, headers, resource, resourceList } = this.state;

    return (
      <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
        <div className="row">
          <div className="d-flex-between mb-1">
            <label className="flex">Method</label>
            <label style={{ flex: 3 }}>Path</label>
          </div>
          <div className="d-flex-between mb-1">
            <SelectInput
              flex={true}
              possibleValues={[
                'GET',
                'HEAD',
                'POST',
                'PUT',
                'DELETE',
                'CONNECT',
                'OPTIONS',
                'TRACE',
                'PATCH',
              ]}
              value={method}
              onChange={(v) => this.setState({ method: v })}
            />
            <div style={{ flex: 3 }} className="ms-1">
              <TextInput
                flex={true}
                value={path}
                onChange={(v) => this.setState({ path: v })}
                placeholder="Request path"
              />
            </div>
          </div>
        </div>
        <div className="row mb-3">
          <label htmlFor={`input-method`} className="col-xs-12 col-sm-2 col-form-label">
            Body
          </label>
          <div className="col-sm-10">
            <CodeInput value={body} onChange={(v) => this.setState({ body: v })} />
          </div>
        </div>
        <div className="row mb-3">
          <label htmlFor={`input-method`} className="col-xs-12 col-sm-2 col-form-label">
            or Resource
          </label>
          <div className="col-sm-10">
            <SelectInput
              flex={true}
              value={resource}
              onChange={(v) => this.setState({ resource: v })}
              possibleValues={this.props.resources.map((r) => r.name)}
            />
          </div>
        </div>
        <BooleanInput
          label="Is a list of resource ?"
          value={resourceList}
          onChange={(v) => this.setState({ resourceList: v })}
        />
        <TextInput
          label="Status"
          value={status}
          onChange={(v) => this.setState({ status: v })}
          placeholder="200, 201, 400, 500..."
        />
        <ObjectInput
          label="Headers"
          placeholderKey="Header name"
          placeholderValue="Header value"
          value={headers}
          onChange={(v) => this.setState({ headers: v })}
        />
        <div className="d-flex-between">
          <button className="btn btn-sm btn-danger ms-auto me-1" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            className="btn btn-sm btn-save"
            onClick={() =>
              this.props.confirm({
                value: this.state,
                idx: this.props.idx,
              })
            }>
            {this.props.endpoint ? 'Save' : 'Create'}
          </button>
        </div>
      </div>
    );
  }
}

const Header = ({ hide }) => (
  <div className="d-flex-between">
    <div className="flex">
      <div className="d-flex-between">
        <h3>Charlatan</h3>
        <button className="btn btn-sm" type="button" style={{ minWidth: '36px' }} onClick={hide}>
          <i className="fas fa-times" style={{ color: '#fff' }} />
        </button>
      </div>
    </div>
  </div>
);

export const HTTP_COLORS = {
  GET: 'rgb(52, 170, 182)',
  POST: 'rgb(117, 189, 93)',
  DELETE: 'rgb(238, 106, 86)',
  PATCH: '#9b59b6',
  HEAD: '#9b59b6',
  PUT: 'rgb(230, 195, 0)',
  OPTIONS: '#9b59b6',
};

const FakerOptions = [
  { value: 'address.zipCode', label: 'Zip code' },
  { value: 'address.zipCodeByState', label: 'Zip code by state' },
  { value: 'address.city', label: 'City' },
  { value: 'address.cityPrefix', label: 'City prefix' },
  { value: 'address.citySuffix', label: 'City suffix' },
  { value: 'address.cityName', label: 'City name' },
  { value: 'address.streetName', label: 'Street name' },
  { value: 'address.streetAddress', label: 'Street address' },
  { value: 'address.streetSuffix', label: 'Street suffix' },
  { value: 'address.streetPrefix', label: 'Street prefix' },
  { value: 'address.secondaryAddress', label: 'Secondary address' },
  { value: 'address.county', label: 'County' },
  { value: 'address.country', label: 'Country' },
  { value: 'address.countryCode', label: 'Country code' },
  { value: 'address.state', label: 'State' },
  { value: 'address.stateAbbr', label: 'State abbreviated' },
  { value: 'address.latitude', label: 'Latitude' },
  { value: 'address.longitude', label: 'Longitude' },
  { value: 'address.direction', label: 'Direction' },
  { value: 'address.cardinalDirection', label: 'Cardinal direction' },
  { value: 'address.ordinalDirection', label: 'Ordinal direction' },
  { value: 'address.nearbyGPSCoordinate', label: 'Nearby GPS coordinate' },
  { value: 'address.timeZone', label: 'Time zone' },
  { value: 'animal.dog', label: 'Dog' },
  { value: 'animal.cat', label: 'Cat' },
  { value: 'animal.snake', label: 'Snake' },
  { value: 'animal.bear', label: 'Bear' },
  { value: 'animal.lion', label: 'Lion' },
  { value: 'animal.cetacean', label: 'Cetacean' },
  { value: 'animal.horse', label: 'Horse' },
  { value: 'animal.bird', label: 'Bird' },
  { value: 'animal.cow', label: 'Cow' },
  { value: 'animal.fish', label: 'Fish' },
  { value: 'animal.crocodilia', label: 'Crocodilia' },
  { value: 'animal.insect', label: 'Insect' },
  { value: 'animal.rabbit', label: 'Rabbit' },
  { value: 'animal.type', label: 'Type' },
  { value: 'commerce.color', label: 'Color' },
  { value: 'commerce.department', label: 'Department' },
  { value: 'commerce.productName', label: 'Product name' },
  { value: 'commerce.price', label: 'Price' },
  { value: 'commerce.productAdjective', label: 'Product adjective' },
  { value: 'commerce.productMaterial', label: 'Product material' },
  { value: 'commerce.product', label: 'Product' },
  { value: 'commerce.productDescription', label: 'Product description' },
  { value: 'company.suffixes', label: 'Suffixes' },
  { value: 'company.companyName', label: 'Company name' },
  { value: 'company.companySuffix', label: 'Company suffix' },
  { value: 'company.catchPhrase', label: 'Catch phrase' },
  { value: 'company.bs', label: 'BS' },
  { value: 'company.catchPhraseAdjective', label: 'Catch phrase adjective' },
  { value: 'company.catchPhraseDescriptor', label: 'Catch phrase descriptor' },
  { value: 'company.catchPhraseNoun', label: 'Catch phrase noun' },
  { value: 'company.bsAdjective', label: 'BS adjective' },
  { value: 'company.bsBuzz', label: 'BS buzz' },
  { value: 'company.bsNoun', label: 'BS noun' },
  { value: 'database.column', label: 'Column' },
  { value: 'database.type', label: 'Type' },
  { value: 'database.collation', label: 'Collation' },
  { value: 'database.engine', label: 'Engine' },
  { value: 'date.past', label: 'Past' },
  { value: 'date.future', label: 'Future' },
  { value: 'date.recent', label: 'Recent' },
  { value: 'date.soon', label: 'Soon' },
  { value: 'date.month', label: 'Month' },
  { value: 'date.weekday', label: 'Weekday' },
  { value: 'finance.account', label: 'Account' },
  { value: 'finance.accountName', label: 'Account name' },
  { value: 'finance.routingNumber', label: 'Routing number' },
  { value: 'finance.mask', label: 'Mask' },
  { value: 'finance.amount', label: 'Amount' },
  { value: 'finance.transactionType', label: 'Transaction type' },
  { value: 'finance.currencyCode', label: 'Currency code' },
  { value: 'finance.currencyName', label: 'Currency name' },
  { value: 'finance.currencySymbol', label: 'Currency symbol' },
  { value: 'finance.bitcoinAddress', label: 'Bitcoin address' },
  { value: 'finance.litecoinAddress', label: 'Litecoin address' },
  { value: 'finance.creditCardNumber', label: 'Credit card number' },
  { value: 'finance.creditCardCVV', label: 'Credit card CVV' },
  { value: 'finance.ethereumAddress', label: 'Ethereum address' },
  { value: 'finance.iban', label: 'IBAN' },
  { value: 'finance.bic', label: 'BIC' },
  { value: 'finance.transactionDescription', label: 'Transaction description' },
  { value: 'git.branch', label: 'Branch' },
  { value: 'git.commitEntry', label: 'Commit entry' },
  { value: 'git.commitMessage', label: 'Commit message' },
  { value: 'git.commitSha', label: 'Commit sha' },
  { value: 'git.shortSha', label: 'Short sha' },
  { value: 'hacker.abbreviation', label: 'Abbreviation' },
  { value: 'hacker.adjective', label: 'Adjective' },
  { value: 'hacker.noun', label: 'Noun' },
  { value: 'hacker.verb', label: 'Verb' },
  { value: 'hacker.ingverb', label: 'Ingverb' },
  { value: 'hacker.phrase', label: 'Phrase' },
  { value: 'image.image', label: 'Image' },
  { value: 'image.avatar', label: 'Avatar' },
  { value: 'image.imageUrl', label: 'Image url' },
  { value: 'image.abstract', label: 'Abstract' },
  { value: 'image.animals', label: 'Animals' },
  { value: 'image.business', label: 'Business' },
  { value: 'image.cats', label: 'Cats' },
  { value: 'image.city', label: 'City' },
  { value: 'image.food', label: 'Food' },
  { value: 'image.nightlife', label: 'Nightlife' },
  { value: 'image.fashion', label: 'Fashion' },
  { value: 'image.people', label: 'People' },
  { value: 'image.nature', label: 'Nature' },
  { value: 'image.sports', label: 'Sports' },
  { value: 'image.technics', label: 'Technics' },
  { value: 'image.transport', label: 'Transport' },
  { value: 'image.dataUri', label: 'Data uri' },
  { value: 'internet.avatar', label: 'Avatar' },
  { value: 'internet.email', label: 'Email' },
  { value: 'internet.exampleEmail', label: 'Example email' },
  { value: 'internet.userName', label: 'User name' },
  { value: 'internet.protocol', label: 'Protocol' },
  { value: 'internet.httpMethod', label: 'HTTP method' },
  { value: 'internet.url', label: 'URL' },
  { value: 'internet.domainName', label: 'Domain name' },
  { value: 'internet.domainSuffix', label: 'Domain suffix' },
  { value: 'internet.domainWord', label: 'Domain word' },
  { value: 'internet.ip', label: 'IP' },
  { value: 'internet.ipv6', label: 'IPV6' },
  { value: 'internet.port', label: 'Port' },
  { value: 'internet.userAgent', label: 'User agent' },
  { value: 'internet.color', label: 'Color' },
  { value: 'internet.mac', label: 'Mac' },
  { value: 'internet.password', label: 'Password' },
  { value: 'lorem.word', label: 'Word' },
  { value: 'lorem.words', label: 'Words' },
  { value: 'lorem.sentence', label: 'Sentence' },
  { value: 'lorem.slug', label: 'Slug' },
  { value: 'lorem.sentences', label: 'Sentences' },
  { value: 'lorem.paragraph', label: 'Paragraph' },
  { value: 'lorem.paragraphs', label: 'Paragraphs' },
  { value: 'lorem.text', label: 'Text' },
  { value: 'lorem.lines', label: 'Lines' },
  { value: 'mersenne.rand', label: 'Rand' },
  { value: 'music.genre', label: 'Genre' },
  { value: 'name.firstName', label: 'First name' },
  { value: 'name.lastName', label: 'Last name' },
  { value: 'name.middleName', label: 'Middle name' },
  { value: 'name.findName', label: 'Find name' },
  { value: 'name.jobTitle', label: 'Job title' },
  { value: 'name.gender', label: 'Gender' },
  { value: 'name.prefix', label: 'Prefix' },
  { value: 'name.suffix', label: 'Suffix' },
  { value: 'name.jobTitle', label: 'Title' },
  { value: 'name.jobDescriptor', label: 'Job descriptor' },
  { value: 'name.jobArea', label: 'Job area' },
  { value: 'name.jobType', label: 'Job type' },
  { value: 'phone.phoneNumber', label: 'Phone number' },
  { value: 'phone.phoneNumberFormat', label: 'Phone number format' },
  { value: 'phone.phoneFormats', label: 'Phone formats' },
  { value: 'system.fileName', label: 'File name' },
  { value: 'system.commonFileName', label: 'Common file name' },
  { value: 'system.mimeType', label: 'Mime type' },
  { value: 'system.commonFileType', label: 'Common file type' },
  { value: 'system.commonFileExt', label: 'Common file ext' },
  { value: 'system.fileType', label: 'File type' },
  { value: 'system.fileExt', label: 'File ext' },
  { value: 'system.directoryPath', label: 'Directory path' },
  { value: 'system.filePath', label: 'File path' },
  { value: 'system.semver', label: 'Semver' },
  { value: 'time.recent', label: 'Recent' },
  { value: 'vehicle.vehicle', label: 'Vehicle' },
  { value: 'vehicle.manufacturer', label: 'Manufacturer' },
  { value: 'vehicle.model', label: 'Model' },
  { value: 'vehicle.type', label: 'Type' },
  { value: 'vehicle.fuel', label: 'Fuel' },
  { value: 'vehicle.vin', label: 'Vin' },
  { value: 'vehicle.color', label: 'Color' },
  { value: 'vehicle.vrm', label: 'Vrm' },
  { value: 'vehicle.bicycle', label: 'Bicycle' },
];
