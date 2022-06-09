import React, { useEffect, useState } from 'react'
import { graphqlSchemaToJson, jsonToGraphqlSchema } from '../../services/BackOfficeServices';
import { Form } from '@maif/react-forms'
import { merge } from 'lodash';
import { FeedbackButton } from './FeedbackButton'

export default class GraphQLForm extends React.Component {
  render() {
    const { route, hide, saveRoute } = this.props

    if (!route)
      return null

    return (
      <div className="graphql-form p-3 flex-column">
        <Header hide={hide} />
        <SideView route={route} saveRoute={saveRoute} />
      </div>
    )
  }
}

const CreationButton = ({ confirm, text, placeholder }) => {
  const [onCreationField, setCreationField] = useState(false)
  const [fieldname, setFieldname] = useState("")

  if (onCreationField)
    return <div className='d-flex-between' onClick={e => e.stopPropagation()}>
      <input type="text"
        onChange={e => setFieldname(e.target.value)}
        placeholder={placeholder}
        className="form-control flex" />
      <button className='btn btn-sm btn-danger mx-1' onClick={e => {
        e.stopPropagation()
        setCreationField(false)
      }} >
        <i className='fas fa-times' />
      </button>
      <button className='btn btn-sm btn-save' onClick={e => {
        e.stopPropagation()
        setCreationField(false)
        confirm(fieldname)
      }}>
        <i className='fas fa-check' />
      </button>
    </div>

  return <button className='btn btn-sm btn-primary my-2 ms-auto'
    onClick={e => {
      e.stopPropagation()
      setCreationField(true)
    }}>{text}</button>
}

class SideView extends React.Component {
  state = {
    types: [],
    selectedField: undefined
  }

  componentDidMount() {
    const plugin = this.props.route.plugins.find(p => p.plugin === "cp:otoroshi.next.plugins.GraphQLBackend")?.config
    graphqlSchemaToJson(plugin.schema)
      .then(res => {
        this.setState({
          types: res.types.map(type => ({
            ...type,
            fields: (type.fields || []).map(field => ({
              ...field,
              directives: (field.directives || []).map(directive => ({
                ...directive,
                arguments: (directive.arguments || []).map(argument => ({
                  [argument.name]: this.transformValue(argument.value)
                }))
              }))
            }))
          }))
        });
      })
  }

  transformValue = v => {
    try {
      return JSON.parse(v);
    } catch (_) {
      return v;
    }
  }

  onSelectField = (typeIdx, fieldIdx) => {
    const field = this.state.types[typeIdx].fields[fieldIdx]
    this.setState({
      selectedField: {
        field,
        fieldIdx,
        typeIdx
      }
    })
  }

  savePlugin = () => {
    const plugin = this.props.route.plugins.find(p => p.plugin === "cp:otoroshi.next.plugins.GraphQLBackend")?.config
    return jsonToGraphqlSchema(plugin.schema, this.state.types)
      .then(res => {
        this.props.saveRoute({
          ...this.props.route,
          plugins: this.props.route.plugins.map(p => {
            if (p.plugin === "cp:otoroshi.next.plugins.GraphQLBackend")
              return {
                ...p,
                config: {
                  ...p.config,
                  schema: res.schema
                }
              }
            return p
          })
        })
      })
  }

  removeField = (e, i) => {
    e.stopPropagation();
    this.setState({
      types: this.state.types.map(type => ({
        ...type,
        fields: type.fields.filter((_, j) => j !== i)
      }))
    })
  }

  createField = fieldname => this.setState({
    types: this.state.types.map((type, t) => {
      if (t === i)
        return ({
          ...type,
          fields: [...type.fields, {
            name: fieldname,
            fieldType: {
              type: "String",
              isList: false
            },
            arguments: [],
            directives: []
          }]
        })
      return type
    })
  })

  render() {
    // const { route, saveRoute } = this.props;
    const { types, selectedField } = this.state;

    console.log(types, selectedField)

    return <>
      <div className="row mt-2">
        <div className="col-md-5 flex-column">
          {
            types.map((type, i) => <Type {...type} key={`type${i}`}
              isSelected={fieldIdx => selectedField ? selectedField.typeIdx === i && selectedField.fieldIdx === fieldIdx : undefined}
              onSelectField={fieldIdx => this.onSelectField(i, fieldIdx)}
              removeField={this.removeField}
              createField={this.createField} />)
          }
          <CreationButton
            text="New type"
            placeholder="New type name"
            confirm={newFieldname => this.setState({
              types: [...this.state.types, {
                name: newFieldname,
                directives: [],
                fields: [{
                  name: "foo",
                  fieldType: {
                    type: "String",
                    isList: false
                  },
                  arguments: [],
                  directives: []
                }]
              }]
            })} />
        </div>
        <div className="col-md-7">
          {selectedField && <FieldForm
            {...selectedField}
            types={types.filter(t => t.name !== "Query" && t.name !== types[selectedField.typeIdx].name).map(t => t.name)}
            onChange={newField => this.setState({
              types: types.map((type, i) => {
                if (i === selectedField.typeIdx)
                  return ({
                    ...type,
                    fields: type.fields.map((field, j) => {
                      if (j === selectedField.fieldIdx)
                        return newField
                      return field
                    })
                  })
                return type
              })
            })}
          />}
        </div>
      </div>
      <FeedbackButton
        className="ms-auto me-2 mt-auto mb-2"
        onPress={this.savePlugin}
        text="Save plugin"
        icon={() => <i className="fas fa-paper-plane" />}
      />
    </>
  }
}

const FieldForm = ({ field, onChange, types }) => {
  const schema = {
    name: {
      type: 'string',
      label: 'Name',
    },
    fieldType: {
      type: 'object',
      format: 'form',
      label: 'Type',
      flow: ['type', 'required', 'isList'],
      schema: {
        type: {
          label: null,
          format: 'select',
          type: 'string',
          options: ['Int', 'String', 'Boolean', 'Float', ...types]
        },
        required: {
          type: 'bool',
          label: 'Is required ?'
        },
        isList: {
          type: 'bool',
          label: 'Is a list of ?'
        }
      }
    },
    arguments: {
      type: 'object',
      label: 'Arguments',
      format: 'form',
      array: true,
      flow: ['name', 'valueType'],
      schema: {
        name: {
          type: 'string',
          label: 'Argument name'
        },
        valueType: {
          label: 'Argument value',
          format: 'form',
          type: 'object',
          flow: ['type', 'required', 'isList'],
          schema: {
            type: {
              label: null,
              format: 'select',
              type: 'string',
              options: ['Int', 'String', 'Boolean', 'Float']
            },
            required: {
              type: 'bool',
              label: 'Is a required argument ?'
            },
            isList: {
              type: 'bool',
              label: 'Is a list of ?'
            }
          }
        }
      }
    },
    directives: {
      type: 'object',
      label: 'Directives',
      array: true,
      format: 'form',
      flow: ['name', 'arguments'],
      schema: {
        'name': {
          type: 'string',
          label: 'Source',
          format: 'select',
          options: [
            'rest', 'graphql', 'json', 'otoroshi (soon)'
          ]
        },
        'arguments': {
          type: 'object',
          label: 'Arguments',
          array: true,
          // format: 'form',
          // conditionalSchema: {
          //   ref: 'name',
          //   switch: [
          //     {
          //       default: true,
          //       condition: 'rest',
          //       schema: {
          //         // argument: {
          //         //   type: 'string',
          //         //   format: 'select',
          //         //   options: [
          //         //     'url', 'method', 'headers', 'timeout', 'paginate'
          //         //   ]
          //         // },
          //         url: {
          //           type: 'string',
          //           label: 'URL'
          //         },
          //         method: {
          //           // visible: { ref: 'argument', test: v => v === "method" },
          //           type: 'string',
          //           label: 'HTTP Method',
          //           format: 'select',
          //           defaultValue: 'GET',
          //           options: ['GET', 'POST']
          //         },
          //         headers: {
          //           // visible: { ref: 'argument', test: v => v === "headers" },
          //           type: 'object',
          //           label: 'Header'
          //         },
          //         timeout: {
          //           // visible: { ref: 'argument', test: v => v === "timeout" },
          //           type: 'number',
          //           label: 'Timeout',
          //           defaultValue: 5000
          //         },
          //         paginate: {
          //           // visible: { ref: 'argument', test: v => v === "paginate" },
          //           type: 'bool',
          //           label: 'Enable pagination',
          //           help: 'Automatically add limit and offset argument'
          //         }
          //       },
          //       flow: [/*'argument', */'url', 'method', 'headers', 'timeout', 'paginate']
          //     },
          //     {
          //       condition: 'graphql',
          //       schema: {
          //         url: {
          //           type: 'string',
          //           label: 'URL'
          //         },
          //         query: {
          //           type: 'string',
          //           format: 'code',
          //           label: 'GraphQL Query'
          //         },
          //         headers: {
          //           type: 'object',
          //           label: 'Headers'
          //         },
          //         timeout: {
          //           type: 'number',
          //           defaultValue: 5000,
          //           label: 'Timeout'
          //         },
          //         method: {
          //           type: 'string',
          //           format: 'select',
          //           defaultValue: 'GET',
          //           options: ['GET', 'POST'],
          //           label: 'HTTP Method'
          //         },
          //         responsePathArg: {
          //           type: 'string',
          //           label: 'JSON Response path'
          //         },
          //         responseFilterArg: {
          //           type: 'string',
          //           label: 'JSON response filter path'
          //         }
          //       },
          //       flow: ['url', 'query', 'headers', 'timeout', 'method', 'responsePathArg', 'responseFilterArg']
          //     }
          //   ]
          // }
        }
      }
    }
  }

  const [state, setState] = useState();

  useEffect(() => {
    setState(field)
  }, [field]);

  if (!state)
    return null;

  return (<div className="p-3" style={{ background: "#373735", borderRadius: '4px' }}>
    <Form
      schema={schema}
      value={state}
      onError={(e) => console.log(e)}
      options={{ autosubmit: true }}
      onSubmit={data => onChange({ ...merge(field, data) })}
      footer={() => null}
    />

  </div>)
}

const Type = ({ name, kind, fields, onSelectField, createField, isSelected, removeField }) => {
  const [open, setOpen] = useState(false);

  const selectField = (e, i) => {
    e.stopPropagation()
    onSelectField(i)
  }

  return <div onClick={() => setOpen(!open)} className="mb-1">
    <div className="graphql-form-type d-flex-between p-2">
      <div className="d-flex-between" style={{ cursor: 'pointer' }}>
        <i className={`fas fa-chevron-${open ? 'down' : 'right'}`} style={{ minWidth: '32px' }}></i>
        <span style={{ color: "#fff" }}>{name}</span>
        <span className="badge bg-warning ms-2">{kind}</span>
      </div>
      <span className='badge bg-dark'>{fields ? fields.length : 0} fields</span>
    </div>
    {open && (fields || []).map((field, i) => <div className="d-flex-between element py-1 ps-1 pe-2" key={`field${i}`} onClick={e => selectField(e, i)} >
      <div className="d-flex-between my-1 ms-2" style={{
        flex: .75,
        opacity: isSelected(i) !== false ? 1 : .5
      }}>
        <span className="me-2 flex">{field.name}</span>
        <span className="badge bg-light ms-2" style={{ color: "#000" }}>{field.fieldType.type}</span>
        <span className={`badge ${field.fieldType.isList ? 'bg-dark' : ''} ms-1`} style={{
          minWidth: '38px',
        }}>
          {field.fieldType.isList ? 'LIST' : '\u00a0\u00a0'}
        </span>
      </div>
      <div className='d-flex-between'>
        {isSelected(i) === true && <button className='btn btn-sm btn-danger me-1' onClick={e => removeField(e, i)}>
          <i className='fas fa-trash' />
        </button>}
        <button className='btn btn-sm btn-save' onClick={e => selectField(e, i)}>
          <i className="fas fa-chevron-right" />
        </button>
      </div>
    </div>)}
    {open && <CreationButton
      text="New field"
      placeholder="New field name"
      confirm={createField} />}
  </div>
}

const Header = ({ hide }) => <div className='d-flex-between'>
  <div className='flex'>
    <h3>GraphQL Schema Editor</h3>
  </div>
  <div className="d-flex me-1">
    <button
      className="btn btn-sm"
      type="button"
      style={{ minWidth: '36px' }}
      onClick={hide}>
      <i className="fas fa-times" style={{ color: '#fff' }} />
    </button>
  </div>
</div>
