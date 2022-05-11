import React, { useState, useEffect } from 'react'

import { Form } from '@maif/react-forms';
import { nextClient } from '../../services/BackOfficeServices';
import { useRef } from 'react';
import { DEFAULT_FLOW } from './Graph';
import { toUpperCaseLabels } from '../../util';
import { FeedbackButton } from './FeedbackButton';
import { SelectInput } from '@maif/react-forms';
import { isEqual } from 'lodash';
import { CodeInput } from '@maif/react-forms';

export const HTTP_COLORS = {
    GET: 'rgb(52, 170, 182)',
    POST: 'rgb(117, 189, 93)',
    DELETE: 'rgb(238, 106, 86)',
    PATCH: '#9b59b6',
    HEAD: '#9b59b6',
    PUT: 'rgb(230, 195, 0)',
    OPTIONS: '#9b59b6'
}

const Methods = ({ frontend }) => {
    const hasMethods = frontend.methods && frontend.methods.length > 0
    const methods = hasMethods ?
        frontend.methods.map((m, i) => <span key={`frontendmethod-${i}`} className={`badge me-1`} style={{ backgroundColor: HTTP_COLORS[m] }}>{m}</span>) :
        [<span className="badge bg-dark">ALL</span>];
    return (
        <div className="d-flex-between">
            {methods.map((method, i) => <div key={`method${i}`} style={{ minWidth: 34 }}>{method}</div>)}
        </div>
    );
}

const Uri = ({ frontend, domain }) => {
    const exact = frontend.exact;
    const end = exact ? '' : (domain.indexOf('/') < 0 ? '/*' : '*');
    const start = 'http://'
    return (
        <div className="d-flex-between">
            <span className='flex ms-2' style={{ fontFamily: 'monospace' }}>{start}{domain}{end} {frontend.exact && <span className="badge me-1" style={{ backgroundColor: '#A3A3A3' }}>EXACT</span>}</span>
        </div>
    );
}

const SaveButton = ({ isDirty, saveChanges, disablePadding }) => <div className={`d-flex align-items-center justify-content-end ${disablePadding ? '' : 'pt-3'}`}>
    <FeedbackButton
        text="Update the route"
        disabled={!isDirty.frontend && !isDirty.backend && !isDirty.backendRef}
        icon={() => <i className="fas fa-paper-plane" />}
        onPress={saveChanges}
    />
</div>

const BackendSelector = ({ setExistingBackend, usingExistingBackend }) => <div className="d-flex mt-2">
    <button
        className="btn btn-sm new-backend-button"
        onClick={() => {
            setExistingBackend(false);
        }}
        style={{ backgroundColor: usingExistingBackend ? '#494849' : '#f9b000' }}>
        Create a new backend
    </button>
    <button
        className="btn btn-sm new-backend-button"
        onClick={() => setExistingBackend(true)}
        style={{ backgroundColor: usingExistingBackend ? '#f9b000' : '#494849' }}>
        Select an existing backend
    </button>
</div>

const RouteForms = ({ frontend, backend, backend_ref, updateRoute }) => {
    const frontendRef = useRef()
    const backendRef = useRef()

    const [isDirty, setDirty] = useState({
        frontend: false,
        backend: false,
        backendRef: false
    })
    const [frontendValue, setFrontend] = useState(frontend)
    const [backendValue, setBackend] = useState(backend)
    const [backendRefValue, setBackendRef] = useState(backend_ref)

    const [schemas, setSchemas] = useState()
    const [backends, setBackends] = useState([])
    const [usingExistingBackend, setExistingBackend] = useState(false)
    const [usingJsonView, setJsonView] = useState(false)

    useEffect(() => {
        Promise.all([
            nextClient.form(nextClient.ENTITIES.FRONTENDS),
            nextClient.form(nextClient.ENTITIES.BACKENDS),
            nextClient.find(nextClient.ENTITIES.BACKENDS)
        ]).then(([frontendForm, backendForm, backends]) => {
            setSchemas({
                frontend: {
                    config_flow: DEFAULT_FLOW.Frontend.config_flow,
                    config_schema: toUpperCaseLabels({
                        ...frontendForm.schema,
                        ...DEFAULT_FLOW.Frontend.config_schema,
                    })
                },
                backend: {
                    config_flow: DEFAULT_FLOW.Backend('').config_flow,
                    config_schema: toUpperCaseLabels(
                        DEFAULT_FLOW.Backend('').config_schema(backendForm.schema)
                    )
                }
            })
            setBackends(backends)
        })
    }, [])

    useEffect(() => {
        setFrontend(frontend)
    }, [frontend])

    useEffect(() => {
        setBackend(backend)
    }, [backend])

    useEffect(() => {
        setBackendRef(backend_ref)
    }, [backend_ref])

    const saveChanges = () => {
        return updateRoute({
            frontend: frontendValue,
            backend: backendValue,
            backend_ref: usingExistingBackend ? backendRefValue : null
        })
    }

    if (!schemas)
        return null

    return <div className='p-2'>
        <div className='d-flex justify-content-end'>
            <div className='d-flex p-2' style={{ backgroundColor: "#373735", borderRadius: '4px' }}>
                <button className='btn btn-sm mx-1' style={{ backgroundColor: "#f9b000" }} onClick={() => {
                    setJsonView(!usingJsonView)
                }}>
                    {usingJsonView ? 'Form view' : 'Advanced json'}
                </button>
            </div>
        </div>
        <div className='d-flex mt-3'>
            <div className='flex p-3 route-forms-form'>
                <h5 className='route-forms-title'>Frontend</h5>
                <RouteForm
                    onSubmit={e => setFrontend(e.frontend ? e.frontend : e)}
                    isDirty={() => isDirty}
                    dirtyField="frontend"
                    setDirty={setDirty}
                    customRef={frontendRef}
                    value={frontendValue}
                    schema={schemas.frontend.config_schema}
                    flow={schemas.frontend.config_flow}
                    usingJsonView={usingJsonView}
                />
                <SaveButton isDirty={isDirty} saveChanges={saveChanges} />
            </div>
            <div className='flex ms-1 p-3 route-forms-form'>
                <h5 className='route-forms-title'>Backend</h5>
                <BackendSelector setExistingBackend={setExistingBackend} usingExistingBackend={usingExistingBackend} />
                {usingExistingBackend && <div className='mt-3'>
                    <SelectInput
                        id="backend_select"
                        value={backendRefValue}
                        placeholder="Select an existing backend"
                        label=""
                        onChange={b => {
                            if (b !== backendRefValue) {
                                setDirty({
                                    ...isDirty,
                                    backendRef: true
                                })
                            }
                            setBackendRef(b)
                        }}
                        possibleValues={backends}
                        transformer={(item) => ({ label: item.name, value: item.id })}
                    />
                </div>}

                {!usingExistingBackend && <RouteForm
                    onSubmit={e => setBackend(e.backend ? e.backend : e)}
                    isDirty={() => isDirty}
                    setDirty={setDirty}
                    dirtyField="backend"
                    customRef={backendRef}
                    value={backendValue}
                    schema={schemas.backend.config_schema}
                    flow={schemas.backend.config_flow}
                    usingJsonView={usingJsonView}
                />}
            </div>
        </div>
        <div className='d-flex justify-content-end pt-3'>
            <SaveButton isDirty={isDirty} saveChanges={saveChanges} disablePadding={true} />
        </div>
    </div>
}

const RouteForm = React.memo(({ isDirty, dirtyField, customRef, value, schema, flow, setDirty, usingJsonView, onSubmit }) =>
    <Form
        ref={customRef}
        value={usingJsonView ? {
            [dirtyField]: value
        } : value}
        schema={usingJsonView ? {
            [dirtyField]: {
                type: 'json',
                format: 'code',
                label: null
            }
        } : schema}
        flow={usingJsonView ? [dirtyField] : flow}
        footer={() => null}
        onSubmit={onSubmit}
        options={{
            autosubmit: true,
            watch: () => {
                if (customRef.current) {
                    const formState = customRef.current.isDirty()
                    console.log(formState)
                    setDirty({
                        ...isDirty(),
                        [dirtyField]: formState
                    });
                }
            }
        }}
    />, (prev, next) => prev.value === next.value &&
        prev.usingJsonView === next.usingJsonView &&
        prev.flow === next.flow)

const Route = props => {
    const [open, setOpen] = useState(false)
    const { frontend } = props

    return <div
        className='route-item my-2'
        style={{ minHeight: open ? '200px' : 'initial' }}>
        <div className={`d-flex-between ${open ? 'route-forms-header' : ''}`} style={{
            padding: '6px',
            paddingBottom: open ? '8px' : '6px'
        }}>
            <div className='d-flex-between'>
                <Methods frontend={frontend} />
                <div className='flex-column'>
                    {frontend.domains.map(domain => <Uri frontend={frontend} domain={domain} />)}
                </div>
            </div>
            <div className='d-flex'>
                {<button className='btn btn-sm btn-danger me-2' onClick={props.removeRoute}>
                    <i className="fas fa-trash" />
                </button>}
                <button className='btn btn-sm' style={{ background: '#f9b000', borderColor: '#f9b000' }}
                    onClick={() => setOpen(!open)} >
                    <i className={`fas fa-chevron-${open ? 'up' : 'down'}`} />
                </button>
            </div>
        </div>
        {open && <RouteForms {...props} />}
    </div>

}

export default ({ service }) => {
    const [routes, setRoutes] = useState([])
    const [templates, setTemplates] = useState({})
    const [shouldUpdateRoutes, setUpdatesRoutes] = useState(false)

    useEffect(() => {
        nextClient.template(nextClient.ENTITIES.SERVICES)
            .then(setTemplates);
    }, [])

    useEffect(() => {
        setRoutes(service.routes || [])
    }, [service.id])

    const updateRoute = (index, item) => {
        let r = routes.map((route, i) => {
            if (i === index)
                return item
            return route
        })

        if (routes.length === 0)
            r = [item]
        else if (index >= routes.length)
            r = [...routes, item]

        setUpdatesRoutes(!isEqual(r, service.routes))
        setRoutes(r)
    }

    const saveRoute = () => nextClient.update(nextClient.ENTITIES.SERVICES, {
        ...service,
        routes
    })

    const removeRoute = idx => {
        const newRoutes = routes.filter((_, i) => i !== idx)
        setRoutes(newRoutes)
        setUpdatesRoutes(!isEqual(newRoutes, service.routes))
    }

    const importOpenApi = () => {

        function OpenapiImport(props) {
            const [state, setState] = useState({ openapi: '', domain: '' });
            return (
                <>
                    <div className="modal-body">
                        <form className="form-horizontal">
                            <div>
                                <div className="row mb-3">
                                    <label className="col-xs-12 col-sm-2 col-form-label">
                                        Openapi URL
                                    </label>
                                    <div className="col-sm-10">
                                        <input type="text" className="form-control" value={state.openapi} onChange={e => setState({ ...state, openapi: e.target.value })} />
                                    </div>
                                </div>
                            </div>
                            <div>
                                <div className="row mb-3">
                                    <label className="col-xs-12 col-sm-2 col-form-label">
                                        Exposed domain
                                    </label>
                                    <div className="col-sm-10">
                                        <input type="text" className="form-control" value={state.domain} onChange={e => setState({ ...state, domain: e.target.value })} />
                                    </div>
                                </div>
                            </div>
                        </form>
                    </div>
                    <div className="modal-footer">
                        <button type="button" className="btn btn-danger" onClick={props.cancel}>
                            Close
                        </button>
                        <button
                            type="button"
                            className="btn btn-success"
                            onClick={(e) => props.ok(state)}>
                            Ok
                        </button>
                    </div>
                </>
            );
        }

        window.popup(
            'Import routes from openapi',
            (ok, cancel) => <OpenapiImport ok={ok} cancel={cancel} />,
            { __style: { width: '100%' } }
        ).then(body => {
            if (body) {
                fetch('/bo/api/proxy/api/experimental/services/_openapi', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(body)
                }).then(r => r.json()).then(imported => {
                    const routes = [...routes, ...imported.routes];
                    nextClient.update(nextClient.ENTITIES.SERVICES, {
                        ...service,
                        routes
                    }).then(s => setRoutes(s.routes))
                })
            }
        });
    }

    return (
        <div>
            <div className='d-flex mb-3'>
                <button className='btn btn-sm btn-success' onClick={() => {
                    const newItem = { ...templates?.routes[0] }
                    updateRoute(routes.length, newItem)
                }}>
                    <i className='fas fa-road me-1' />
                    Create a new route
                </button>
                <button className="btn btn-sm btn-success mx-1" style={{ marginLeft: 10 }} onClick={() => {
                    importOpenApi();
                }}>
                    <i className='fas fa-file-code me-1' />
                    Import routes from openapi
                </button>
                <FeedbackButton
                    className="ms-auto"
                    disabled={!shouldUpdateRoutes}
                    text="Save routes"
                    icon={() => <i className='fas fa-paper-plane' />}
                    onPress={saveRoute}
                />
            </div>
            <div>
                {routes.map((route, i) => <Route
                    {...route}
                    key={route.id} i
                    ndex={i}
                    updateRoute={item => updateRoute(i, item)}
                    removeRoute={() => removeRoute(i)} />
                )}
            </div>
        </div>
    )
}