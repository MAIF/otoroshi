import React, { useEffect, useState } from 'react';
import { graphqlSchemaToJson, jsonToGraphqlSchema } from '../../services/BackOfficeServices';
import { NgCodeRenderer, NgForm } from '../../components/nginputs';
import { PillButton } from '../../components/PillButton';

export default class GraphQLForm extends React.Component {
  state = {
    schemaView: false,
    tmpSchema: this.props.route?.plugins.find(
      (p) => p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend'
    )?.config.schema,
    types: [],
    error: undefined,
  };

  componentDidMount() {
    const plugin = this.props.route.plugins.find(
      (p) => p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend'
    )?.config;
    this.schemaToJson(plugin.schema);
  }

  schemaToJson = (schema, successCallback) => {
    return graphqlSchemaToJson(schema).then((res) => {
      if (res.error) {
        this.setState({ error: res.error });
      } else {
        this.setState(
          {
            error: undefined,
            types: res.types.map((type) => ({
              ...type,
              fields: (type.fields || []).map((field) => ({
                ...field,
                directives: (field.directives || []).map((directive) => ({
                  ...directive,
                  arguments: (directive.arguments || []).reduce(
                    (acc, argument) => ({
                      ...acc,
                      [argument.name]: this.transformValue(argument.value),
                    }),
                    {}
                  ),
                })),
              })),
            })),
          },
          successCallback
        );
      }
    });
  };

  transformValue = (v) => {
    try {
      if (Array.isArray(v)) return v;
      return JSON.parse(v);
    } catch (_) {
      return v;
    }
  };

  transformTypes = (types) => {
    return types.map((type) => ({
      ...type,
      fields: (type.fields || []).map((field) => ({
        ...field,
        directives: (field.directives || []).map((directive) => ({
          ...directive,
          arguments: Object.entries(directive.arguments || []).map(([k, v]) => ({
            [k]: v,
          })),
        })),
      })),
    }));
  };

  savePlugin = () => {
    const plugin = this.props.route.plugins.find(
      (p) => p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend'
    )?.config;
    return jsonToGraphqlSchema(plugin.schema, this.transformTypes(this.state.types)).then((res) => {
      if (res.error)
        this.setState({
          error: res.error,
        });
      else {
        this.setState({
          error: undefined,
        });
        this.props.saveRoute({
          ...this.props.route,
          plugins: this.props.route.plugins.map((p) => {
            if (p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend')
              return {
                ...p,
                config: {
                  ...p.config,
                  schema: res.schema,
                },
              };
            return p;
          }),
        });
      }
    });
  };

  render() {
    const { route, hide } = this.props;

    if (!route) return null;

    return (
      <div className="graphql-form p-3 pe-2 flex-column" style={{ overflowY: 'scroll' }}>
        <Header
          hide={(e) => {
            this.savePlugin().then(() => hide(e));
          }}
          schemaView={this.state.schemaView}
          toggleSchema={(s) => {
            if (s) {
              const plugin = route.plugins.find(
                (p) => p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend'
              )?.config;
              this.setState({ schemaView: s, tmpSchema: plugin.schema });
            } else {
              this.schemaToJson(this.state.tmpSchema, () => {
                this.setState({ schemaView: s });
              });
            }
          }}
        />
        {this.state.schemaView ? (
          <>
            {this.state.error && (
              <span className="my-3" style={{ color: '#D5443F', fontWeight: 'bold' }}>
                {this.state.error}
              </span>
            )}
            <NgCodeRenderer
              ngOptions={{
                spread: true,
              }}
              rawSchema={{
                props: {
                  editorOnly: true,
                  height: '100%',
                  mode: 'graphqlschema',
                },
              }}
              value={this.state.tmpSchema}
              onChange={(e) => {
                this.props.saveRoute({
                  ...route,
                  plugins: route.plugins.map((p) => {
                    if (p.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend')
                      return {
                        ...p,
                        config: {
                          ...p.config,
                          schema: e,
                        },
                      };
                    return p;
                  }),
                });
                this.setState({ tmpSchema: e, error: undefined });
              }}
            />
          </>
        ) : (
          <SideView
            route={route}
            types={this.state.types}
            setTypes={(t) => this.setState({ types: t })}
            error={this.state.error}
          />
        )}
      </div>
    );
  }
}

const CreationButton = ({ confirm, text, placeholder, className }) => {
  const [onCreationField, setCreationField] = useState(false);
  const [fieldname, setFieldname] = useState('');

  if (onCreationField)
    return (
      <div className="flex d-flex-between my-2" onClick={(e) => e.stopPropagation()}>
        <input
          type="text"
          onChange={(e) => setFieldname(e.target.value)}
          placeholder={placeholder}
          className="form-control flex"
        />
        <button
          className="btn btn-sm btn-danger mx-1"
          onClick={(e) => {
            e.stopPropagation();
            setCreationField(false);
          }}>
          <i className="fas fa-times" />
        </button>
        <button
          className="btn btn-sm btn-save"
          disabled={fieldname.length <= 0}
          onClick={(e) => {
            e.stopPropagation();
            setCreationField(false);
            confirm(fieldname);
          }}>
          <i className="fas fa-check" />
        </button>
      </div>
    );

  return (
    <button
      className={`btn btn-sm btn-primary my-2 ${className || 'me-auto'}`}
      onClick={(e) => {
        e.stopPropagation();
        setCreationField(true);
      }}>
      <i className="fas fa-plus-circle me-1" />
      {text}
    </button>
  );
};

class SideView extends React.Component {
  state = {
    selectedField: undefined,
  };

  onSelectField = (typeIdx, fieldIdx) => {
    const field = this.props.types[typeIdx].fields[fieldIdx];
    this.setState({ selectedField: undefined }, () =>
      this.setState({
        selectedField: {
          field,
          fieldIdx,
          typeIdx,
        },
      })
    );
  };

  removeField = (e, typeIdx, fieldIdx) => {
    e.stopPropagation();

    window.newConfirm('Delete this field').then((ok) => {
      if (ok) {
        this.props.setTypes(
          this.props.types.map((type, i) => ({
            ...type,
            fields: i === typeIdx ? type.fields.filter((_, j) => j !== fieldIdx) : type.fields,
          }))
        );
      }
    });
  };

  removeType = (e, i) => {
    e.stopPropagation();
    window.newConfirm('Delete this type').then((ok) => {
      if (ok) {
        this.props.setTypes(this.props.types.filter((_, j) => j !== i));
      }
    });
  };

  createField = (fieldname, i) => {
    this.props.setTypes(
      this.props.types.map((type, t) => {
        if (t === i)
          return {
            ...type,
            fields: [
              ...type.fields,
              {
                name: fieldname,
                fieldType: {
                  type: 'String',
                  isList: false,
                },
                arguments: [],
                directives: [],
              },
            ],
          };
        return type;
      })
    );
    this.onSelectField(i, this.props.types[i].fields.length - 1);
  };

  render() {
    const { selectedField } = this.state;
    const { types, error } = this.props;

    return (
      <>
        <div className="row flex">
          <div className="col-md-5 flex-column">
            <div className="d-flex-between">
              <h4 className="mb-0 me-3">Types</h4>
              <CreationButton
                text="New type"
                placeholder="New type name"
                confirm={(newFieldname) => {
                  this.props.setTypes([
                    ...types,
                    {
                      name: newFieldname,
                      directives: [],
                      fields: [
                        {
                          name: 'foo',
                          fieldType: {
                            type: 'String',
                            isList: false,
                          },
                          arguments: [],
                          directives: [],
                        },
                      ],
                    },
                  ]);
                  this.onSelectField(types.length - 1, 0);
                }}
              />
            </div>
            {error && (
              <span
                className="my-3"
                style={{
                  color: '#D5443F',
                  fontWeight: 'bold',
                }}>
                {error}
              </span>
            )}
            {types.map((type, i) => (
              <Type
                {...type}
                key={`type${i}`}
                isSelected={(fieldIdx) =>
                  selectedField
                    ? selectedField.typeIdx === i && selectedField.fieldIdx === fieldIdx
                    : undefined
                }
                isSelectedType={selectedField ? selectedField.typeIdx === i : undefined}
                removeType={(e) => this.removeType(e, i)}
                onSelectField={(fieldIdx) => this.onSelectField(i, fieldIdx)}
                removeField={(e, fieldIdx) => this.removeField(e, i, fieldIdx)}
                createField={(fieldname) => this.createField(fieldname, i)}
              />
            ))}
          </div>
          <div className="col-md-7">
            {selectedField && (
              <FieldForm
                {...selectedField}
                field={types[selectedField.typeIdx].fields[selectedField.fieldIdx]}
                types={types
                  .filter((t) => t.name !== 'Query' && t.name !== types[selectedField.typeIdx].name)
                  .map((t) => t.name)}
                onChange={(newField) =>
                  this.props.setTypes(
                    types.map((type, i) => {
                      if (i === selectedField.typeIdx)
                        return {
                          ...type,
                          fields: type.fields.map((field, j) => {
                            if (j === selectedField.fieldIdx) return newField;
                            return field;
                          }),
                        };
                      return type;
                    })
                  )
                }
              />
            )}
          </div>
        </div>
      </>
    );
  }
}

class FieldForm extends React.Component {
  flow = [
    'name',
    'fieldType',
    {
      type: 'group',
      name: 'Arguments',
      fields: ['arguments'],
    },
    {
      type: 'group',
      name: 'Directives',
      fields: ['directives'],
    },
  ];
  schema = {
    name: {
      type: 'string',
      label: 'Name',
    },
    fieldType: {
      type: 'form',
      label: ' ',
      flow: ['type', 'required', 'isList'],
      schema: {
        type: {
          type: 'select',
          label: 'Type',
          props: {
            createOption: true,
            options: ['Int', 'String', 'Boolean', 'Float', ...this.props.types].map((r) => ({
              label: r,
              value: r,
            })),
          },
        },
        required: {
          type: 'bool',
          label: 'Is required ?',
        },
        isList: {
          type: 'bool',
          label: 'Is a list of ?',
        },
      },
    },
    arguments: {
      format: 'form',
      props: {
        ngOptions: {
          spread: true,
        },
      },
      array: true,
      flow: [
        {
          type: 'group',
          collapsed: true,
          name: (props) => props.value?.name || 'Argument',
          fields: ['name', 'valueType'],
        },
      ],
      schema: {
        name: {
          type: 'string',
          label: 'Argument name',
        },
        valueType: {
          label: 'Argument value',
          type: 'form',
          flow: ['type', 'required', 'isList'],
          schema: {
            type: {
              type: 'select',
              props: {
                label: null,
                options: ['Int', 'String', 'Boolean', 'Float'].map((item) => ({
                  value: item,
                  label: item,
                })),
              },
            },
            required: {
              type: 'bool',
              label: 'Is a required argument ?',
            },
            isList: {
              type: 'bool',
              label: 'Is a list of ?',
            },
          },
        },
      },
    },
    directives: {
      format: 'form',
      props: {
        ngOptions: {
          spread: true,
        },
      },
      array: true,
      flow: [
        {
          type: 'group',
          collapsed: true,
          name: (props) => props.value?.name || 'Directive',
          fields: ['name', 'arguments'],
        },
      ],
      schema: {
        name: {
          type: 'select',
          props: {
            label: 'Type',
            options: [
              'rest',
              'graphql',
              'json',
              'soap',
              'permission',
              'allpermissions',
              'onePermissionsOf',
              'authorize',
              'otoroshi (soon)',
            ].map((item) => ({ label: item, value: item })),
          },
        },
        arguments: {
          format: 'form',
          label: 'Arguments',
          flow: (value, props) => {
            const type = props.rootValue?.name;

            return {
              rest: ['url', 'method', 'headers', 'timeout', 'paginate'],
              graphql: [
                'url',
                'query',
                'headers',
                'timeout',
                'method',
                'response_path_arg',
                'response_filter_arg',
              ],
              json: ['path'],
              permission: ['value', 'unauthorized_value'],
              allpermissions: ['values', 'unauthorized_value'],
              onePermissionsOf: ['values', 'unauthorized_value'],
              authorize: ['path', 'value', 'unauthorized_value'],
              soap: [
                'url',
                'envelope',
                'action',
                'preserve_query',
                'charset',
                'jq_request_filter',
                'jq_response_filter',
              ],
              [undefined]: [],
            }[type];
          },
          schema: {
            url: {
              type: 'string',
              label: 'URL',
            },
            method: {
              type: 'dots',
              label: 'HTTP Method',
              props: {
                options: [
                  { value: 'GET', label: 'GET', color: 'rgb(89, 179, 255)' },
                  { value: 'POST', label: 'POST', color: 'rgb(74, 203, 145)' },
                ],
              },
            },
            headers: {
              type: 'object',
              label: 'Header',
            },
            timeout: {
              type: 'number',
              label: 'Timeout',
              defaultValue: 5000,
            },
            paginate: {
              type: 'bool',
              label: 'Enable pagination',
              help: 'Automatically add limit and offset argument',
              defaultValue: false,
            },
            query: {
              type: 'code',
              props: {
                editorOnly: true,
                label: 'GraphQL Query',
              },
            },
            response_path_arg: {
              type: 'string',
              label: 'JSON Response path',
            },
            response_filter_arg: {
              type: 'string',
              label: 'JSON response filter path',
            },
            path: {
              type: 'string',
              label: 'JSON path',
            },
            value: {
              type: 'string',
              label: 'Value',
            },
            values: {
              type: 'array',
              label: 'Values',
            },
            unauthorized_value: {
              type: 'string',
              label: 'Unauthorized message',
            },
            envelope: {
              type: 'string',
              label: 'Envelope',
            },
            action: {
              type: 'string',
              label: 'Action',
            },
            preserve_query: {
              type: 'bool',
              defaultValue: true,
              label: 'Preserve query',
            },
            charset: {
              type: 'string',
              label: 'Charset',
            },
            jq_request_filter: {
              type: 'string',
              label: 'JQ Request filter',
            },
            jq_response_filter: {
              type: 'string',
              label: 'JQ Response filter',
            },
          },
        },
      },
    },
  };

  render() {
    const { onChange, field } = this.props;

    if (!field) {
      return null;
    }

    return (
      <div className="p-3" style={{ background: '#373735', borderRadius: '4px' }}>
        <h4>Field: {field.name}</h4>
        <NgForm
          useBreadcrumb={true}
          flow={this.flow}
          schema={this.schema}
          value={field}
          onChange={onChange}
        />
      </div>
    );
  }
}

const Type = ({
  name,
  kind,
  fields,
  onSelectField,
  createField,
  isSelected,
  removeField,
  removeType,
  isSelectedType,
}) => {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open && isSelectedType) setOpen(true);
  }, [isSelectedType]);

  const selectField = (e, i) => {
    e.stopPropagation();
    onSelectField(i);
  };

  return (
    <div onClick={() => setOpen(!open)} className="mb-1">
      <div
        className="graphql-form-type d-flex-between p-2"
        style={{
          borderRadius: '4px',
          borderBottomLeftRadius: open ? 0 : '4px',
          borderBottomRightRadius: open ? 0 : '4px',
        }}>
        <div className="d-flex-between" style={{ cursor: 'pointer' }}>
          <i
            className={`fas fa-chevron-${open ? 'down' : 'right'}`}
            style={{ minWidth: '32px' }}></i>
          <span style={{ color: '#fff' }}>{name}</span>
          <span className="badge bg-warning ms-2">{kind}</span>
        </div>
        <button className="btn btn-sm btn-danger ms-1" onClick={removeType}>
          <i className="fas fa-trash" />
        </button>
      </div>
      <div
        style={{
          backgroundColor: 'rgba(55, 55, 53, .5)',
          border: open ? '1px solid rgb(55, 55, 53)' : 'none',
          paddingTop: open ? '4px' : 0,
        }}>
        {open &&
          (fields || []).map((field, i) => (
            <div
              className="d-flex-between graphql-form-element py-1 ps-1 pe-2"
              key={`field${i}`}
              onClick={(e) => selectField(e, i)}>
              <div
                className="d-flex-between mb-1 ms-2"
                style={{
                  flex: 0.75,
                  opacity: isSelected(i) !== false ? 1 : 0.5,
                }}>
                <span className="me-2 flex" style={{ color: '#fff' }}>
                  {field.name}
                </span>
                <span className="badge bg-light ms-2" style={{ color: '#000' }}>
                  {field.fieldType.type}
                </span>
                <span
                  className={`badge ${field.fieldType.isList ? 'bg-dark' : ''} ms-1`}
                  style={{
                    minWidth: '38px',
                  }}>
                  {field.fieldType.isList ? 'LIST' : '\u00a0\u00a0'}
                </span>
              </div>
              <div className="d-flex" style={{ minWidth: '64px', justifyContent: 'flex-end' }}>
                <button className="btn btn-sm btn-danger me-1" onClick={(e) => removeField(e, i)}>
                  <i className="fas fa-trash" />
                </button>
                <button className="btn btn-sm btn-primary" onClick={(e) => selectField(e, i)}>
                  <i className="fas fa-chevron-right" />
                </button>
              </div>
            </div>
          ))}
        {open && (
          <CreationButton
            text="New field"
            className="ms-auto d-flex-between"
            placeholder="New field name"
            confirm={createField}
          />
        )}
      </div>
    </div>
  );
};

const Header = ({ schemaView, toggleSchema, hide }) => (
  <>
    <div className="d-flex-between">
      <h3>GraphQL Schema Editor</h3>
      <button className="btn btn-sm btn-primary" onClick={hide}>
        <i className="fas fa-times" />
      </button>
    </div>
    <PillButton
      className="mb-3"
      rightEnabled={schemaView}
      onChange={toggleSchema}
      leftText="Form"
      rightText="Schema"
    />
  </>
);
