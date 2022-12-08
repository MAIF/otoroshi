import React, { Suspense, useState } from 'react';
import faker from 'faker';
import { FeedbackButton } from './FeedbackButton';
import { createTooltip } from '../../tooltips';

import { NgForm } from '../../components/nginputs/form';
import {
  NgBooleanRenderer,
  NgNumberRenderer,
  NgSelectRenderer,
  NgStringRenderer,
} from '../../components/nginputs/inputs';
import { PillButton } from '../../components/PillButton';

const CodeInput = React.lazy(() => Promise.resolve(require('../../components/inputs/CodeInput')));

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

const stringify = (body) => {
  if (typeof body === 'object' && body !== null)
    try {
      return JSON.stringify(body);
    } catch (err) {
      return body;
    }
  return body;
};

const generateFakerValues = (resources, endpoint) => {
  const { resource_list, body, length } = endpoint;

  function calculateResource(resource) {
    return (resource.schema || []).reduce(
      (acc, item) => ({
        ...acc,
        ...calculateField(item),
      }),
      isAnObject(resource.additional_data)
        ? resource.additional_data
        : JSON.parse(resource.additional_data || '{}')
    );
  }

  function fakeValue(item) {
    try {
      return castValue(item.value.split('.').reduce((a, c) => a[c], faker)(), item.field_type);
    } catch (err) {
      return castValue(item.value, item.field_type);
    }
  }

  function calculateField(item) {
    return {
      [item.field_name]:
        item.field_type === 'Model'
          ? calculateResource(resources.find((f) => f.name === item.value))
          : fakeValue(item),
    };
  }

  function isAnObject(v) {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
  }

  if (endpoint.model) {
    const resource = resources.find((f) => f.name === endpoint.model);
    if (!resource) return {};

    const newItem = calculateResource(resource);

    if (resource_list) {
      return Array.from({ length: length || 10 }, (_, i) => ({ ...newItem }));
    } else {
      return newItem;
    }
  } else {
    if (resource_list) return Array.from({ length: length || 10 }, (_, i) => body);
    return body;
  }
};

function PushView({ endpoints, resources }) {
  const [status, setStatus] = useState(endpoints.map(() => false));
  return (
    <>
      <div className="d-flex">
        <h4 className="mb-0">Endpoints</h4>
      </div>
      <div className="mt-3">
        {endpoints
          .sort((a, b) => a.path.localeCompare(b.path))
          .map((endpoint, idx) => {
            return (
              <div
                className="mb-2 endpoint"
                key={`${endpoint.path}${idx}`}
                onClick={() => setStatus(status.map((s, i) => (i === idx ? !s : s)))}
                style={{
                  border: `1px solid ${HTTP_COLORS[endpoint.method]}`,
                  padding: 0,
                  backgroundColor: `rgba(${HTTP_COLORS[endpoint.method]
                    .replace(')', '')
                    .replace('rgb(', '')}, .25)`,
                }}>
                <div
                  className="d-flex-between p-1"
                  style={{
                    borderBottom: status[idx]
                      ? `1px solid ${HTTP_COLORS[endpoint.method]}`
                      : 'none',
                  }}>
                  <div className="d-flex-between">
                    <div style={{ minWidth: '90px', textAlign: 'center' }} className="d-flex me-3">
                      <span
                        className="flex"
                        style={{
                          backgroundColor: HTTP_COLORS[endpoint.method],
                          padding: '2px',
                          borderRadius: '4px',
                          color: '#fff',
                        }}>
                        {endpoint.method}
                      </span>
                    </div>
                    <span style={{ maxWidth: '50%' }}>{endpoint.path}</span>
                    <span className="ms-3" style={{ maxWidth: '50%' }}>
                      {endpoint.description}
                    </span>

                    {endpoint.model && (
                      <span className="badge bg-dark ms-3 me-auto">{endpoint.model}</span>
                    )}
                  </div>
                  <div className="d-flex-between">
                    {!endpoint.body && !endpoint.model && (
                      <div className="mx-1 d-flex-between endpoint-helper">
                        <span
                          style={{ color: '#D5443F' }}
                          {...createTooltip('Missing raw body or model')}>
                          <i className="fas fa-exclamation-triangle" />
                        </span>
                      </div>
                    )}
                    <button className="btn btn-sm btn-primary me-1" type="button">
                      <i className={`fas fa-chevron-${status[idx] ? 'up' : 'down'}`} />
                    </button>
                  </div>
                </div>
                {status[idx] && <OpenAPIParameters {...endpoints[idx]} resources={resources} />}
                {status[idx] && <OpenAPIResponse {...endpoints[idx]} />}
              </div>
            );
          })}
        {endpoints.length === 0 && <span>No endpoints available</span>}
      </div>
    </>
  );
}

function CharlatanActions({ generateData, resetData, endpoints }) {
  return (
    <div className="d-flex mt-auto ms-auto">
      {endpoints.find((endpoint) => !endpoint.body && !endpoint.model) && (
        <FeedbackButton
          className="btn-sm"
          onPress={generateData}
          icon={() => <i className="fas fa-hammer me-1" />}
          text="Generate missing data"
        />
      )}
      <button className="btn btn-sm btn-success mx-1" onClick={resetData}>
        <i className="fas fa-times me-1" />
        Reset all override data and generate new ones
      </button>
    </div>
  );
}

function CharlatanResourcesList({ showResourceForm, resources, removeResource, endpoints }) {
  return (
    resources.length > 0 && (
      <div className="mt-3">
        <div className="d-flex">
          <h4 className="mb-0">Models</h4>
          <button className="btn btn-sm btn-primary ms-3" onClick={showResourceForm}>
            <i className="fas fa-plus-circle me-1" />
            New model
          </button>
        </div>
        <div className="mt-3 d-flex">
          {resources.map((resource, idx) => {
            return (
              <div
                className="d-flex-between endpoint me-2"
                key={resource.name}
                style={{
                  width: '125px',
                  height: '125px',
                  flexDirection: 'column',
                  gap: '12px',
                  justifyContent: 'space-between',
                }}>
                <label style={{ fontSize: '1.1rem' }}>{resource.name}</label>
                {!endpoints.find((e) => e.model === resource.name) &&
                  !resources.find((e) =>
                    e.schema?.find((f) => f.field_type === 'Model' && f.value === resource.name)
                  ) && (
                    <span style={{ color: '#D5443F' }} {...createTooltip('Model not used')}>
                      <i className="fas fa-exclamation-triangle" />
                    </span>
                  )}
                <div>
                  <button
                    className="btn btn-sm btn-success me-2"
                    onClick={() => {
                      showResourceForm(idx, true);
                    }}>
                    <i className="fas fa-pencil-alt" />
                  </button>
                  <button
                    className="btn btn-sm btn-danger"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeResource(idx);
                    }}>
                    <i className="fas fa-trash" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
        {resources.length === 0 && <span>No entities available</span>}
      </div>
    )
  );
}

function CharlatanEndpointsList({ showEndpointForm, endpoints, removeEndpoint, openResource }) {
  return (
    <>
      <div className="d-flex">
        <h4 className="mb-0">Endpoints</h4>
        <button className="btn btn-sm btn-primary ms-3" onClick={(e) => showEndpointForm(e, false)}>
          <i className="fas fa-plus-circle me-1" />
          New endpoint(s)
        </button>
      </div>
      <div className="mt-3">
        <div
          className="d-flex-between mb-2 endpoint"
          style={{
            border: `1px solid rgba(25, 25, 25, .25)`,
            backgroundColor: `rgba(25, 25, 25, .25)`,
          }}>
          <div className="d-flex-between flex">
            <div style={{ minWidth: '90px', textAlign: 'center' }} className="d-flex me-3">
              <span
                className="flex"
                style={{
                  backgroundColor: 'rgba(25, 25, 25, .25)',
                  padding: '2px',
                  borderRadius: '4px',
                  // color: '#fff',
                }}>
                METHODS
              </span>
            </div>
            <span style={{ maxWidth: '50%', flex: 2 }}>PATH</span>
            <span className="ms-3" style={{ maxWidth: '50%', flex: 3 }}>
              DESCRIPTION
            </span>
            <span className="badge bg-dark ms-3 me-auto">MODEL</span>
          </div>
          <div style={{ minWidth: '40px' }}></div>
        </div>
        {endpoints
          .sort((a, b) => a.path.localeCompare(b.path))
          .map((endpoint, idx) => {
            return (
              <div
                className="d-flex-between mb-2 endpoint"
                key={`${endpoint.path}${idx}`}
                onClick={() => showEndpointForm(idx, true)}
                style={{
                  border: `1px solid ${HTTP_COLORS[endpoint.method]}`,
                  backgroundColor: `rgba(${HTTP_COLORS[endpoint.method]
                    .replace(')', '')
                    .replace('rgb(', '')}, .25)`,
                }}>
                <div className="d-flex-between flex">
                  <div style={{ minWidth: '90px', textAlign: 'center' }} className="d-flex me-3">
                    <span
                      className="flex"
                      style={{
                        backgroundColor: HTTP_COLORS[endpoint.method],
                        padding: '2px',
                        borderRadius: '4px',
                        color: '#fff',
                      }}>
                      {endpoint.method}
                    </span>
                  </div>
                  <span
                    style={{
                      maxWidth: '50%',
                      flex: 2,
                    }}>
                    {endpoint.path}
                  </span>
                  <span
                    className="ms-3"
                    style={{
                      maxWidth: '50%',
                      flex: 3,
                    }}>
                    {endpoint.description}
                  </span>

                  {!endpoint.body && !endpoint.model && (
                    <div className="mx-1 d-flex-between endpoint-helper">
                      <span
                        style={{ color: '#D5443F' }}
                        {...createTooltip('Missing raw body or model')}>
                        <i className="fas fa-exclamation-triangle" />
                      </span>
                    </div>
                  )}

                  {!endpoint.model && <span className="btn btn-sm ms-3 me-3"></span>}
                  {endpoint.model && (
                    <span
                      className="btn btn-sm btn-primary ms-3 me-3"
                      onClick={(e) => {
                        e.stopPropagation();
                        openResource(endpoint.model);
                      }}>
                      {endpoint.model}
                    </span>
                  )}
                </div>
                <div className="d-flex-between" style={{ minWidth: '32px' }}>
                  <button
                    className="btn btn-sm btn-danger"
                    onClick={(e) => {
                      e.stopPropagation();
                      window.newConfirm('Delete this endpoint ?').then((ok) => {
                        if (ok) {
                          e.stopPropagation();
                          removeEndpoint(idx);
                        }
                      });
                    }}>
                    <i className="fas fa-trash" />
                  </button>
                </div>
              </div>
            );
          })}
        {endpoints.length === 0 && <span>No endpoints available</span>}
      </div>
    </>
  );
}

export default class MocksDesigner extends React.Component {
  state = {
    onDesigner: true,
  };

  setAndSave = (res) => {
    return Promise.resolve(
      this.props.saveRoute({
        ...this.props.route,
        plugins: this.props.route.plugins.map((p) => {
          const config = {
            ...this.getState(),
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
  };

  configToResponses = (config) =>
    config.endpoints.map(({ path, method, status, headers, body }) => ({
      path,
      method,
      status,
      headers,
      body: stringify(body),
    }));

  getState() {
    const plugin = this.props.route.plugins.find(
      (p) => p.plugin === 'cp:otoroshi.next.plugins.MockResponses'
    )?.config;

    if (plugin?.form_data) {
      return {
        resources: plugin.form_data.resources,
        endpoints: plugin.form_data.endpoints.map((endpoint) => {
          const { resource, ...props } = endpoint;
          if (resource) {
            return {
              ...props,
              model: resource,
            };
          } else {
            return endpoint;
          }
        }),
      };
    } else {
      return {
        resources: [],
        endpoints: [],
      };
    }
  }

  showForm = (title, idx, elementName, render) => {
    const resource = this.getState()[elementName].find((_, i) => i === idx);
    window
      .popup(title, (ok, cancel) => render(ok, cancel, resource), {
        additionalClass: 'designer-modal-dialog',
      })
      .then((data) => {
        if (data) {
          const { idx } = data;
          let value = data.value;

          if (elementName === 'endpoints') {
            const newEndpoint = {
              ...value,
              enabled_generation: false,
              create_model: false,
              model: value.resource?.name || value.model,
              resource: undefined,
            };

            const additionalEndpoints = [];

            if (value.enabled_generation) {
              [
                { field: 'find_one', path: `${value.path}/:id`, method: 'GET', status: 200 },
                { field: 'put', path: `${value.path}/:id`, method: 'PUT', status: 204 },
                { field: 'post', path: `${value.path}`, method: 'POST', status: 201 },
                { field: 'delete', path: `${value.path}/:id`, method: 'DELETE', status: 202 },
              ].forEach(({ field, path, method, status }) => {
                if (value[field] === undefined || value[field]) {
                  additionalEndpoints.push({
                    ...newEndpoint,
                    path,
                    status,
                    method,
                  });
                }
              });
            }

            this.setAndSave({
              resources: value.resource
                ? [...this.getState().resources, value.resource]
                : this.getState().resources,
              endpoints: Number.isFinite(idx)
                ? this.getState()[elementName].map((r, i) => {
                    if (i === idx) return newEndpoint;
                    return r;
                  })
                : [...this.getState()[elementName], newEndpoint, ...additionalEndpoints],
            });
          } else {
            this.setAndSave({
              resources: Number.isFinite(idx)
                ? this.getState().resources.map((r, i) => {
                    if (i === idx) return value;
                    return r;
                  })
                : [...this.getState().resources, value],
            });
          }
        }
      });
  };

  showResourceForm = (idx, onEdit) =>
    this.showForm(
      `${onEdit ? 'Edit a model' : 'Create a new model'}`,
      idx,
      'resources',
      (ok, cancel, resource) => (
        <NewResource
          confirm={ok}
          cancel={cancel}
          resource={resource}
          idx={idx}
          resources={this.getState().resources}
        />
      )
    );

  showEndpointForm = (idx, onEdit) =>
    this.showForm(
      `${onEdit ? 'Edit an endpoint' : 'Create a new endpoint'}`,
      idx,
      'endpoints',
      (ok, cancel, endpoint) => (
        <NewEndpoint
          confirm={ok}
          cancel={cancel}
          resources={this.getState().resources}
          endpoint={endpoint}
          idx={idx}
          onEdit={onEdit}
        />
      )
    );

  removeEndpoint = (idx) => {
    this.setAndSave({
      endpoints: this.getState().endpoints.filter((_, j) => j !== idx),
    });
  };

  removeResource = (idx) => {
    if (
      this.getState().endpoints.find(
        (endpoint) => endpoint.model === this.getState().resources[idx].name
      )
    ) {
      window.newAlert(`This model can't be deleted because it is used by at least one endpoint`);
    } else {
      this.setAndSave({
        resources: this.getState().resources.filter((_, j) => j !== idx),
      });
    }
  };

  generateData = () => {
    return this.setAndSave({
      endpoints: this.getState().endpoints.map((endpoint) => ({
        ...endpoint,
        body: endpoint.body || generateFakerValues(this.getState().resources, endpoint),
      })),
    }).then(() =>
      this.setState({
        onDesigner: false,
      })
    );
  };

  resetData = () => {
    window.newConfirm(`Are you sure you reset all data ?`).then((ok) => {
      if (ok) {
        this.setAndSave({
          endpoints: this.getState().endpoints.map((endpoint) => ({
            ...endpoint,
            body: null,
          })),
        }).then(this.generateData);
      }
    });
  };

  render() {
    const { route, hide } = this.props;
    const { resources, endpoints } = this.getState();

    if (!route) return null;

    return (
      <div
        className="graphql-form p-3 pe-2 flex-column"
        style={{
          overflowX: 'hidden',
        }}>
        <Header
          hide={hide}
          setDesigner={(value) => this.setState({ onDesigner: value })}
          onDesigner={this.state.onDesigner}
        />

        {this.state.onDesigner && (
          <>
            <div className="row">
              <CharlatanEndpointsList
                showEndpointForm={this.showEndpointForm}
                endpoints={endpoints}
                removeEndpoint={this.removeEndpoint}
                openResource={(model) =>
                  this.showResourceForm(
                    resources.findIndex((f) => f.name === model),
                    true
                  )
                }
              />
              <CharlatanResourcesList
                showResourceForm={this.showResourceForm}
                resources={resources}
                endpoints={endpoints}
                removeResource={this.removeResource}
              />
            </div>
            {resources.length > 0 && (
              <CharlatanActions
                endpoints={endpoints}
                generateData={this.generateData}
                resetData={this.resetData}
              />
            )}
          </>
        )}

        {!this.state.onDesigner && <PushView endpoints={endpoints} resources={resources} />}
      </div>
    );
  }
}

function OpenAPIParameters({ resources, ...props }) {
  const model = resources.find((r) => r.name === props.model);
  return (
    <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
      <h4>Parameters</h4>
      <div className="d-flex" style={{ borderBottom: '1px solid' }}>
        <p style={{ minWidth: '120px' }} className="me-3">
          Name
        </p>
        <p className="flex">Description</p>
      </div>
      {model?.schema.map(({ field_name, field_type, field_description, value }) => {
        return (
          <div className="d-flex pt-2" key={field_name}>
            <p style={{ minWidth: '120px' }} className="me-3">
              {field_name}
            </p>
            <div className="flex">
              <p>
                {field_type === 'Model'
                  ? resources.find((r) => r.name === value)?.name
                  : field_type}
              </p>
              <p>{field_description || 'No description'}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function OpenAPIResponse({ body, status, description, model, resource_list }) {
  return (
    <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
      <h4>Responses</h4>
      <div className="d-flex" style={{ borderBottom: '1px solid' }}>
        <p className="me-3">Code</p>
        <p className="flex">Description</p>
      </div>
      <div className="d-flex pt-2">
        <p className="me-3">{status}</p>
        <div className="flex">
          <p>{description || 'No description'}</p>
          <div className="d-flex" style={{ gap: '4px' }}>
            <p style={{ borderRight: '1px solid', fontWeight: 'bold' }} className="pe-2">
              Example value
            </p>
            <p>{resource_list ? `List of ${model || 'Model'}` : model || 'Model'}</p>
          </div>
          <Suspense fallback={<div>Loading ....</div>}>
            <CodeInput value={body} onChange={() => {}} mode="json" editorOnly={true} />
          </Suspense>
        </div>
      </div>
    </div>
  );
}

class NewResource extends React.Component {
  state = {
    name: this.props.resource?.name || '',
    schema: this.props.resource?.schema || [],
    additional_data: this.props.resource?.additional_data || {},
  };

  schema = {
    ...MODEL_FOPM_SCHEMA(this.props.resources).schema,
    additional_data: {
      type: 'code',
      label: ' Additional data (raw fields)',
      props: {
        mode: 'json',
        editorOnly: true,
      },
    },
  };

  flow = ['name', 'schema', 'additional_data'];

  render() {
    return (
      <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
        <NgForm
          value={this.state}
          schema={this.schema}
          flow={this.flow}
          onChange={(e) => this.setState({ ...e })}
        />

        <div className="d-flex-between">
          <button className="btn btn-sm btn-danger ms-auto me-1" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            className="btn btn-sm btn-save"
            disabled={this.state.name.length <= 0}
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

function EndpointGenerationInput({ label, fieldName, rootOnChange, rootValue, onChange, path }) {
  const additionalEndpoints = rootValue.additionalEndpoints;
  return (
    <div className="d-flex align-items-center mb-3">
      <NgStringRenderer
        label={label}
        onChange={(e) =>
          rootOnChange({
            ...rootValue,
            additionalEndpoints: {
              ...rootValue.additionalEndpoints,
              [fieldName]: {
                path: e,
              },
            },
          })
        }
        value={
          additionalEndpoints && additionalEndpoints[fieldName]
            ? additionalEndpoints[fieldName].path
            : path
        }
        schema={{
          disabled: true,
        }}
        margin="m-0 flex"
      />
      <NgBooleanRenderer
        value={rootValue[fieldName] === undefined ? true : rootValue[fieldName]}
        onChange={onChange}
        schema={{}}
        ngOptions={{
          spread: true,
        }}
      />
    </div>
  );
}

const MODEL_FOPM_SCHEMA = (resources) => ({
  type: 'form',
  label: 'New model',
  schema: {
    name: {
      type: 'string',
      label: 'Model name',
    },
    schema: {
      type: 'form',
      array: true,
      label: 'Fields',
      schema: {
        field_name: {
          type: 'string',
          label: 'Field name',
        },
        field_description: {
          type: 'string',
          label: 'Field description',
        },
        field_type: {
          type: 'select',
          props: {
            options: [
              'String',
              'Number',
              'Boolean',
              // 'Object',
              // 'Array',
              // 'Date',
              'Model',
            ],
          },
          label: 'Field type',
        },
        use_faker_value: {
          visible: (props) => props.field_type === 'String',
          type: 'boolean',
          label: 'Use faker value',
        },
        value: {
          renderer: (props) => {
            const type = props?.rootValue?.field_type;
            const isChild = type === 'Model';
            const isFaker =
              props?.rootValue?.use_faker_value && props?.rootValue?.field_type === 'String';

            let Element = NgStringRenderer;

            if (isChild || isFaker)
              return (
                <NgSelectRenderer
                  label="Content value"
                  value={props?.rootValue?.value}
                  schema={{}}
                  options={isChild ? resources.map((a) => a.name) : FakerOptions}
                  onChange={props.onChange}
                />
              );
            else if (type === 'Number') Element = NgNumberRenderer;
            else if (type === 'Boolean') Element = NgBooleanRenderer;
            return React.createElement(Element, {
              value: props?.rootValue?.value,
              label: 'Content value',
              onChange: props.onChange,
              schema: {},
            });
          },
        },
      },
      flow: [
        {
          type: 'group',
          collapsed: true,
          name: (props) => `Field ${props?.value?.field_name}`,
          fields: ['field_name', 'field_description', 'field_type', 'use_faker_value', 'value'],
        },
      ],
    },
  },
  flow: ['name', 'schema'],
});

class NewEndpoint extends React.Component {
  state = this.props.endpoint || {
    method: 'GET',
    path: '/foo',
    status: 200,
    body: null,
    resource: '',
    resource_list: false,
    headers: {},
    length: 10,
  };

  schema = {
    method: {
      type: 'select',
      label: 'Method',
      props: {
        options: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT', 'OPTIONS', 'TRACE', 'PATCH'],
      },
    },
    path: {
      type: 'string',
      label: 'Request path',
    },
    description: {
      type: 'string',
      label: 'Description',
    },
    status: {
      type: 'number',
      label: 'Status',
    },
    headers: {
      type: 'object',
      label: 'Headers',
      // defaultValue: {
      //   'Content-Type': 'application/json'
      // }
    },
    enabled_generation: {
      type: 'boolean',
      label: 'Enabled generation',
    },
    find_one: {
      visible: (props) => props.enabled_generation,
      renderer: (props) => (
        <EndpointGenerationInput
          label="Find one"
          rootOnChange={props.rootOnChange}
          fieldName="find_one"
          onChange={props.onChange}
          rootValue={props.rootValue}
          path={`${props.rootValue?.path}/:id`}
        />
      ),
    },
    put: {
      visible: (props) => props.enabled_generation,
      renderer: (props) => (
        <EndpointGenerationInput
          label="Update endpoint"
          rootOnChange={props.rootOnChange}
          fieldName="put"
          onChange={props.onChange}
          rootValue={props.rootValue}
          path={`${props.rootValue?.path}/:id`}
        />
      ),
    },
    post: {
      visible: (props) => props.enabled_generation,
      renderer: (props) => (
        <EndpointGenerationInput
          label="Creation endpoint"
          rootOnChange={props.rootOnChange}
          fieldName="post"
          onChange={props.onChange}
          rootValue={props.rootValue}
          path={`${props.rootValue?.path}`}
        />
      ),
    },
    delete: {
      visible: (props) => props.enabled_generation,
      renderer: (props) => (
        <EndpointGenerationInput
          label="Delete endpoint"
          rootOnChange={props.rootOnChange}
          fieldName="delete"
          onChange={props.onChange}
          rootValue={props.rootValue}
          path={`${props.rootValue?.path}/:id`}
        />
      ),
    },
    use_generation: {
      label: 'Use generate data ?',
      type: 'boolean',
    },
    model: {
      type: 'select',
      label: 'Model',
      props: {
        options: this.props.resources.map((r) => r.name),
      },
      visible: (props) => props.use_generation && !props.create_model,
    },
    create_model: {
      visible: (props) => props.use_generation && !props.create_model,
      renderer: (props) => {
        return (
          <div className="d-flex justify-content-end">
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={() => {
                props.onChange(true);
              }}>
              <i className="fas fa-plus-circle me-1" />
              Create new model
            </button>
          </div>
        );
      },
    },
    resource_list: {
      type: 'boolean',
      label: 'Is a list of models ?',
    },
    length: {
      type: 'number',
      label: 'Number of elements to generate',
      visible: (props) => props.resource_list,
    },
    body: {
      type: 'code',
      label: 'JSON Body',
      props: {
        mode: 'json',
        editorOnly: true,
      },
      visible: (props) => !props.use_generation,
    },
    resource: {
      visible: (props) => props.create_model && props.use_generation,
      ...MODEL_FOPM_SCHEMA(this.props.resources),
    },
    additional_data: {
      visible: (props) => props.create_model && props.use_generation,
      type: 'code',
      label: 'Additional data (raw fields)',
      props: {
        editorOnly: true,
      },
    },
    cancel_creation: {
      visible: (props) => props.use_generation && props.create_model,
      renderer: (props) => {
        return (
          <div className="d-flex justify-content-end">
            <button
              className="bnt btn-sm btn-danger"
              type="button"
              onClick={() =>
                props.rootOnChange({
                  ...props.value,
                  create_model: false,
                })
              }>
              <i className="fas fa-times me-1" />
              Discard creation
            </button>
          </div>
        );
      },
    },
  };

  flow = [
    {
      type: 'group',
      name: 'Informations',
      fields: ['method', 'path', 'description', 'status', 'headers'],
    },
    {
      type: 'group',
      visible: () => !this.props.onEdit,
      name: 'Endpoints generation',
      fields: ['enabled_generation', 'find_one', 'put', 'post', 'delete'],
    },
    {
      type: 'group',
      name: 'Response',
      fields: [
        'use_generation',
        'model',
        'create_model',
        'resource',
        'additional_data',
        'cancel_creation',
        {
          type: 'group',
          name: 'Generator informations',
          visible: (props) => props.use_generation,
          fields: ['resource_list', 'length'],
        },
        'body',
      ],
    },
  ];

  render() {
    return (
      <div className="designer p-3" style={{ background: '#373735', borderRadius: '4px' }}>
        <NgForm
          value={this.state}
          flow={this.flow}
          schema={this.schema}
          onChange={(e) => {
            this.setState({ ...e });
          }}
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

function Header({ hide, onDesigner, setDesigner }) {
  return (
    <>
      <div className="d-flex-between">
        <h3>Mock responses</h3>
        <button className="btn btn-sm" type="button" style={{ minWidth: '36px' }} onClick={hide}>
          <i className="fas fa-times" />
        </button>
      </div>
      <PillButton
        rightEnabled={onDesigner}
        onChange={setDesigner}
        leftText="Design"
        rightText="Content"
      />
    </>
  );
}

export const HTTP_COLORS = {
  GET: 'rgb(89, 179, 255)',
  POST: 'rgb(74, 203, 145)',
  DELETE: 'rgb(249, 63, 62)',
  PUT: 'rgb(251, 161, 47)',
  HEAD: 'rgb(155, 89, 182)',
  PATCH: 'rgb(155, 89, 182)',
  OPTIONS: 'rgb(155, 89, 182)',
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
