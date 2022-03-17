import React, { useEffect, useState } from 'react'
import { useParams, useLocation } from 'react-router'
import { nextClient, getCategories, getPlugins } from '../../services/BackOfficeServices'
import { Form, format, type } from '@maif/react-forms'
import { SelectInput } from '@maif/react-forms/lib/inputs'
import deepSet from 'set-value'
import { DEFAULT_FLOW, PLUGIN_INFORMATIONS_SCHEMA } from './Graph'
import { BackendForm } from '../BackendsPage'
import Loader from './Loader'

export default ({ lineId, value }) => {
    const { routeId } = useParams()

    const [backends, setBackends] = useState([])

    const [categories, setCategories] = useState([])
    const [nodes, setNodes] = useState([])
    const [plugins, setPlugins] = useState([])

    const [selectedNode, setSelectedNode] = useState()
    const [route, setRoute] = useState(value)

    const [loading, setLoading] = useState(true)
    const location = useLocation()

    useEffect(() => {
        Promise.all([
            nextClient.find(nextClient.ENTITIES.BACKENDS),
            nextClient.fetch(nextClient.ENTITIES.ROUTES, routeId),
            getCategories(),
            getPlugins(),
            nextClient.form(nextClient.ENTITIES.FRONTENDS),
            nextClient.form(nextClient.ENTITIES.BACKENDS),
        ])
            .then(([backends, route, categories, plugins, frontendForm, backendForm]) => {
                const formatedPlugins = plugins
                    .filter(plugin => !plugin.plugin_steps.includes('Sink') && !plugin.plugin_steps.includes('HandlesTunnel'))
                    .map(plugin => ({
                        ...plugin,
                        config_schema: format(plugin.config_schema || {}),
                        config: plugin.default_config
                    }))

                setBackends(backends)
                setCategories(categories.filter(category => category !== 'Tunnel'))
                setRoute(route)

                setPlugins(formatedPlugins.map(p => ({
                    ...p,
                    selected: route.plugins.find(r => r.plugin === p.id)
                })))

                setNodes([
                    {
                        ...DEFAULT_FLOW.Frontend,
                        ...frontendForm,
                        schema: format(frontendForm.schema)
                    },
                    {
                        ...DEFAULT_FLOW.Backend,
                        ...backendForm,
                        schema: format(backendForm.schema)
                    },
                    ...route.plugins.map(ref => {
                        const plugin = formatedPlugins.find(p => p.id === ref.plugin)
                        const onInputStream = (plugin.plugin_steps || []).some(s => ["PreRoute", "ValidateAccess", "TransformRequest"].includes(s))
                        const onOutputStream = (plugin.plugin_steps || []).some(s => ["TransformResponse"].includes(s))

                        return {
                            ...plugin,
                            onOutputStream,
                            onInputStream
                        }
                    })
                ])

                setLoading(false)
            })
    }, [location.pathname])

    const format = obj => {
        return Object.entries(obj).reduce((acc, [key, value]) => {
            const isLabelField = key === "label"
            const v = isLabelField ? value.replace(/_/g, ' ') : value
            const [prefix, ...sequences] = isLabelField ? v.split(/(?=[A-Z])/) : []

            return {
                ...acc,
                [key]: isLabelField ? prefix.charAt(0).toUpperCase() + prefix.slice(1) + " " + sequences.join(" ").toLowerCase() :
                    ((typeof value === 'object' && value !== null && key !== "transformer" && !Array.isArray(value)) ? format(value) : value)
            }
        }, {})
    }

    const allowDrop = e => e.preventDefault()
    const onDrag = (e, element) => e.dataTransfer.setData("newElement", JSON.stringify(element))
    const onDrop = (ev) => {
        ev.preventDefault()

        const node = JSON.parse(ev.dataTransfer.getData("newElement"))

        addNode(node)
    }

    const removeNode = id => {
        setNodes(nodes.filter(node => node.id !== id))
        setRoute({
            ...route,
            plugins: route.plugins.filter(plugin => !plugin.plugin.endsWith(id))
        })

        setPlugins(plugins.map(plugin => {
            if (plugin.id === id)
                return { ...plugin, selected: undefined }
            return plugin
        }))
    }

    const addNode = node => {
        if ((node.plugin_steps || []).some(s => ["TransformResponse"].includes(s)) ||
            node.onTargetStream ||
            (node.plugin_steps || []).some(s => ["PreRoute", "ValidateAccess", "TransformRequest"].includes(s))) {

            setPlugins(plugins.map(p => {
                if (p.id === node.id)
                    p.selected = !p.plugin_multi_inst
                return p
            }))

            setRoute({
                ...route,
                plugins: [
                    ...route.plugins,
                    {
                        plugin: node.id,
                        config: node.config
                    }
                ]
            })

            setNodes([...nodes, node])

            if (node.switch)
                changeValues([
                    { name: node.property, value: true }
                ])

            setSelectedNode(node)
        }
    }

    const Dot = ({ icon, children, clickable, onClick, highlighted, selectedNode, style = {} }) => <div className='dot' style={{
        cursor: clickable ? 'pointer' : 'initial',
        opacity: (!selectedNode || highlighted) ? 1 : .25,
        backgroundColor: highlighted ? '#f9b000' : '#494948',
        ...style,
        textAlign: 'center'
    }} onClick={onClick ? e => {
        e.stopPropagation()
        onClick(e)
    } : e => e.stopPropagation()}>
        {icon && <i className={`fas fa-${icon}`} style={{ color: "#fff", fontSize: 20 }} />}
        {children && children}
    </div>

    const Anchor = ({ text, highlighted = true, mt = 'initial' }) => <div className='anchor'
        onDragOver={allowDrop} onDrop={onDrop}
        style={{
            opacity: highlighted ? 1 : .25,
            marginTop: mt
        }}>
        <span className='text-center'>{text}</span>
    </div>

    const Link = ({ highlighted = true, flex }) => <div className="link" style={{
        opacity: highlighted ? 1 : .25,
        flex: flex ? 1 : 'initial'
    }}></div>

    const NodeElement = ({ element, setSelectedNode, hideLink, selectedNode, isLast, disableBorder }) => {
        const { id, name } = element
        const highlighted = selectedNode && selectedNode.id === id

        return <>
            <Dot clickable={true}
                selectedNode={selectedNode}
                style={{
                    border: disableBorder ? 0 : 1
                }}
                onClick={e => {
                    e.stopPropagation()
                    setSelectedNode(element)
                }} highlighted={highlighted}>
                <span style={{
                    padding: '4px 12px',
                    borderRadius: '4px',
                    color: "#fff",
                    whiteSpace: 'wrap',
                    width: "fit-content"
                }}>
                    {name || id}
                </span>
            </Dot>
            {!hideLink && <Link highlighted={highlighted} />}
        </>
    }

    const handleSearch = search => {
        setPlugins(plugins
            .map(plugin => ({
                ...plugin,
                filtered: !plugin.id.toLowerCase().includes(search.toLowerCase())
            })))
    }

    const changeValues = ops => {
        const newRoute = ops.reduce((newRoute, { name, value }) => {
            return deepSet(_.cloneDeep(newRoute), name, value)
        }, route)

        nextClient.update(nextClient.ENTITIES.ROUTES, newRoute)
            .then(() => setRoute(newRoute))
    }

    const updatePlugin = (pluginId, item) => {
        nextClient.update(nextClient.ENTITIES.ROUTES, {
            ...route,
            plugins: route.plugins.map(plugin => {
                if (plugin.plugin === pluginId)
                    return {
                        ...plugin,
                        ...item.informations,
                        config: item.plugin
                    }
                return plugin
            })
        })
            .then(r => {
                if (!r.error)
                    setRoute(r)
                else {
                    // TODO - manage error
                }
            })
    }

    const saveChanges = () => {
        nextClient.update(nextClient.ENTITIES.ROUTES, route)
            .then(newRoute => {
                setRoute(newRoute)
            })
    }

    const sortInputStream = arr => Object.values(arr.reduce((acc, node) => {
        if (node.plugin_steps.includes('PreRoute'))
            return {
                ...acc, PreRoute: [
                    ...acc['PreRoute'],
                    node
                ]
            }
        else if (node.plugin_steps.includes('ValidateAccess'))
            return {
                ...acc, ValidateAccess: [
                    ...acc['ValidateAccess'],
                    node
                ]
            }
        return {
            ...acc, TransformRequest: [
                ...acc['TransformRequest'],
                node
            ]
        }
    }, {
        PreRoute: [],
        ValidateAccess: [],
        TransformRequest: []
    }))
        .flat()

    const inputNodes = sortInputStream(nodes
        .filter(node => (node.plugin_steps || []).some(s => ["PreRoute", "ValidateAccess", "TransformRequest"].includes(s))))
    const targetNodes = nodes.filter(node => node.onTargetStream)
    const outputNodes = nodes.filter(node => (node.plugin_steps || []).some(s => ["TransformResponse"].includes(s)))

    return <Loader loading={loading}>
        <div className="h-100 col-sm-12" onClick={() => setSelectedNode(undefined)}>
            <div className="col-sm-3" style={{
                paddingLeft: 0,
                marginRight: 'calc(var(--bs-gutter-x) * 1)'
            }}>
                <div className="elements">
                    <div style={{
                        height: "calc(100% - 12px)",
                        width: "3px",
                        backgroundColor: "#f9b000",
                        position: 'absolute',
                        left: "20px",
                        top: 0,
                        zIndex: -1
                    }}></div>
                    <SearchBar handleSearch={handleSearch} />
                    <PluginsStack elements={plugins
                        .reduce((acc, plugin) => {
                            if (plugin.selected || plugin.filtered)
                                return acc
                            return acc.map(group => {
                                if (plugin.plugin_categories.includes(group.group))
                                    return {
                                        ...group,
                                        elements: [...(group.elements || []), plugin]
                                    }
                                return group
                            })
                        }, categories.map(category => ({
                            group: category,
                            elements: []
                        })))}
                        onDrag={onDrag} addNode={addNode} />
                </div>
            </div>
            <div className="col-sm-9">
                <div className="row h-100 p-2 me-1" style={{
                    background: 'rgb(60, 60, 60)',
                    borderRadius: '4px'
                }}>
                    <div className='col-sm-4 pe-3 d-flex flex-column'>
                        <div className='row' style={{ height: '100%' }}>
                            <div className="col-sm-6" style={{ display: 'flex', flexDirection: 'column' }}>
                                <div className="main-view">
                                    <Dot style={{
                                        width: "72px",
                                        height: "36px",
                                        borderRadius: '6px'
                                    }} icon="chevron-down" selectedNode={selectedNode} />
                                    <Link highlighted={!selectedNode} />
                                    {inputNodes.map((value, i) => <NodeElement
                                        element={value}
                                        key={`inNodes${i}`}
                                        selectedNode={selectedNode}
                                        setSelectedNode={setSelectedNode}
                                        isLast={(inputNodes.length - 1) === i}
                                    />)}
                                    <Anchor text="Drop elements here" highlighted={!selectedNode} />
                                    <Link highlighted={!selectedNode} flex={true} />
                                </div>
                            </div>
                            <div className="col-sm-6 pe-3" style={{ display: 'flex', flexDirection: 'column' }}>
                                <div className="main-view">
                                    <Dot style={{
                                        width: "72px",
                                        height: "36px",
                                        borderRadius: '6px'
                                    }} icon="chevron-up" selectedNode={selectedNode} />
                                    <Link highlighted={!selectedNode} />
                                    {outputNodes.map((value, i) => <NodeElement
                                        element={value}
                                        key={`outNodes${i}`}
                                        setSelectedNode={setSelectedNode}
                                        selectedNode={selectedNode}
                                        isLast={(outputNodes.length - 1) === i}
                                    />)}
                                    <Anchor
                                        out={true}
                                        text="Drop elements here"
                                        stream="onOutputStream"
                                        highlighted={!selectedNode} />
                                    <Link highlighted={!selectedNode} flex={true} />
                                </div>
                            </div>
                        </div>
                        <div className="main-view"
                            style={{
                                flex: 0,
                                backgroundColor: "#f9b000",
                                padding: '1px',
                                position: 'relative',
                                opacity: !selectedNode ? 1 : !selectedNode.onTargetStream ? .25 : 1
                            }}>
                            <i className="fas fa-globe-americas"
                                style={{
                                    position: 'absolute',
                                    top: '-3px',
                                    right: '-18px',
                                    fontSize: '42px',
                                    color: "#fff",
                                    backgroundColor: "#f9b000",
                                    borderRadius: "50%"
                                }}>
                            </i>
                            {targetNodes.map((value, i, arr) => <NodeElement
                                element={value}
                                key={`targetNodes${i}`}
                                selectedNode={(selectedNode && selectedNode.onTargetStream) ? selectedNode : undefined}
                                setSelectedNode={setSelectedNode}
                                hideLink={arr.length - 1 === i}
                                disableBorder={true}
                            />)}
                        </div></div>
                    <div className="col-sm-8" style={{ paddingRight: 0 }}>
                        {selectedNode ? <EditView
                            setRoute={setRoute}
                            selectedNode={selectedNode}
                            setSelectedNode={setSelectedNode}
                            changeValues={changeValues}
                            updatePlugin={updatePlugin}
                            removeNode={removeNode}
                            route={route}
                            plugins={plugins}
                            backends={backends}
                        /> : <UnselectedNode saveChanges={saveChanges} />}
                    </div>
                </div>
            </div>
        </div>
    </Loader>
}

const Element = ({ element, onDrag, n, addNode }) => (
    <div className="element" draggable={true} onDragStart={e => onDrag(e, { ...element })} onClick={e => {
        e.stopPropagation()
        addNode(element)
    }}>
        <div className="d-flex-between" style={{
            padding: "10px",
            textOverflow: 'ellipsis',
            overflow: 'hidden',
            whiteSpace: 'wrap',
            width: '100%',
        }}>
            {element.name.charAt(0).toUpperCase() + element.name.slice(1)}
            <i className="fas fa-arrow-right" style={{ color: "#494948" }} />
        </div>
    </div>
)

const Group = ({ group, elements, onDrag, addNode }) => {
    const [open, setOpen] = useState(false)

    return <div className="group">
        <div className="group-header" style={{ cursor: 'pointer' }} onClick={e => {
            e.stopPropagation()
            setOpen(!open)
        }}>
            <span style={{ color: "#fff", padding: "10px" }}>{group.charAt(0).toUpperCase() + group.slice(1)}</span>
            <div style={{ marginLeft: 'auto', display: 'flex' }}>
                <div className="flex-center"
                    onClick={e => {
                        e.stopPropagation()
                        setOpen(!open)
                    }}
                    style={{
                        width: '22px',
                        height: '22px',
                        borderRadius: "4px",
                        cursor: 'pointer'
                    }}>
                    <i className="fas fa-chevron-down mr-2" size={16} style={{ color: "#fff" }} />
                </div>
            </div>
        </div>
        {open && <>
            <PluginsStack elements={elements} onDrag={onDrag} addNode={addNode} />
        </>}
    </div>
}

const PluginsStack = ({ elements, ...props }) => <div className="plugins-stack">
    {elements.map((element, i) => {
        if (element.group) {
            if (element.elements?.find(e => !e.default))
                return <Group {...element} key={element.group} {...props} />
            return null
        }
        else
            return <Element
                key={`${element.id}${i}`}
                n={i + 1}
                element={element}
                {...props} />
    })}
</div>

const SearchBar = ({ handleSearch }) => <div className='group'>
    <div className="group-header" style={{ alignItems: 'initial' }}>
        <i className="fas fa-search group-icon" />
        <div style={{
            paddingLeft: '6px',
            width: '100%',
            backgroundColor: "#fff",
            display: 'flex',
            alignItems: 'center'
        }}>
            <input
                type="text"
                style={{
                    border: 0,
                    padding: '6px 0px 6px 6px',
                    width: '100%',
                    outline: 'none',
                    borderRadius: '4px'
                }}
                onChange={e => handleSearch(e.target.value)}
                placeholder="Search for a specific plugin" />
        </div>
    </div>
</div>

const convertTransformer = obj => {
    return Object.entries(obj).reduce((acc, [key, value]) => {
        let newValue = value
        if (key === "transformer" && typeof value === 'object')
            newValue = item => ({ label: item[value.label], value: item[value.value] })
        else if (typeof value === 'object' && value !== null && !Array.isArray(value))
            newValue = convertTransformer(value)

        return {
            ...acc,
            [key]: newValue
        }
    }, {})
}

const read = (value, path) => {
    const keys = path.split(".")
    if (keys.length === 1)
        return value[path]

    return read(value[keys[0]], keys.slice(1).join("."))
}

const UnselectedNode = ({ saveChanges }) => <div className="d-flex-between dark-background p-1 ps-2">
    <span style={{
        textAlign: "center",
        fontStyle: 'italic'
    }}>Start by selecting a node</span>
    <button className="btn btn-sm btn-outline-success" type="button" onClick={saveChanges}>
        <i className="far fa-paper-plane" style={{ paddingRight: '6px' }} />
        <span>Update route</span>
    </button>
</div>

const camelToSnakeCase = str => str.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`)

const reservedCamelWords = [
    "isMulti", "optionsFrom", "createOption", "onCreateOption",
    "defaultKeyValue", "defaultValue", "className", "onChange", "itemRender", "conditionalSchema"
]

const camelToSnake = obj => {
    return Object.fromEntries(Object.entries(obj).map(([key, value]) => {
        const isFlowField = key === "flow"
        return [
            reservedCamelWords.includes(key) ? key : camelToSnakeCase(key),
            isFlowField ? value.map(step => camelToSnakeFlow(step)) :
                ((typeof value === 'object' && value !== null && !Array.isArray(value)) ? camelToSnake(value) : value)
        ]
    }))
}

const camelToSnakeFlow = step => {
    return typeof step === 'object' ? {
        ...step,
        flow: step.flow.map(f => camelToSnakeFlow(f))
    } : camelToSnakeCase(step)
}

const EditView = ({
    selectedNode, setSelectedNode, route, changeValues,
    removeNode, plugins, updatePlugin, setRoute, backends }) => {
    const [usingExistingBackend, setUsingExistingBackend] = useState(route.backend_ref)
    const [backendConfigRef, setBackendConfigRef] = useState()

    useEffect(() => {
        if (route.backend_ref)
            nextClient.fetch(nextClient.ENTITIES.BACKENDS, route.backend_ref)
                .then(setBackendConfigRef)
    }, [route.backend_ref])

    const { id, flow, config_flow, config_schema, schema, name } = selectedNode

    let formSchema = schema || config_schema
    let formFlow = flow || config_flow

    if (config_schema) {
        formSchema = {
            informations: {
                type: type.object,
                format: format.form,
                collapsable: true,
                label: 'Informations',
                schema: PLUGIN_INFORMATIONS_SCHEMA
            }
        }
        formFlow = [
            'informations'
        ]
        if (Object.keys(config_schema).length > 0) {
            formSchema = {
                ...formSchema,
                plugin: {
                    type: type.object,
                    format: format.form,
                    label: null,
                    schema: { ...convertTransformer(config_schema) },
                    flow: [...flow || config_flow].map(step => camelToSnakeFlow(step))
                }
            }
            formFlow = [
                ...formFlow,
                {
                    label: 'Plugin',
                    flow: ['plugin'],
                    collapsed: false
                }
            ]
        }
    }

    formSchema = camelToSnake(formSchema)
    formFlow = formFlow.map(step => camelToSnakeFlow(step))

    const plugin = ['Backend', 'Frontend'].includes(id) ? DEFAULT_FLOW[id] : plugins.find(element => element.id === id || element.id.endsWith(id))

    const RemoveComponent = () => <button className='btn btn-sm btn-danger ms-2' onClick={e => {
        e.stopPropagation()
        setSelectedNode(undefined)
        removeNode(id)
    }}>
        <i className='fas fa-trash me-2'></i>
        Remove this component
    </button>

    let value = route[selectedNode.field]

    if (!value) {
        const pluginOnFlow = route.plugins.find(p => p.plugin === id)
        if (pluginOnFlow) {
            const { plugin, config, ...informations } = pluginOnFlow
            value = {
                plugin: config,
                informations
            }
        }
    }

    if (!value) {
        const defaultPlugin = plugins.find(p => p.id === id)
        if (defaultPlugin) {
            const { plugin, config, ...informations } = defaultPlugin
            value = {
                plugin: config,
                informations
            }
        }
    }

    // console.log("FLOW", formFlow)
    console.log("SCHEMA", formSchema.plugin)
    console.log("VALUE", value)

    return <div onClick={e => {
        e.stopPropagation()
    }} className="plugins-stack">
        <div className="group-header" style={{
            borderBottom: '1px solid #f9b000',
            borderRight: 0
        }}>
            <i className={`fas fa-${plugin.icon || 'bars'} group-icon`}
                style={{
                    color: "#fff",
                    borderBottomLeftRadius: 0
                }} />
            <span style={{ color: "#fff", paddingLeft: "12px" }}>{name || id}</span>
        </div>
        {selectedNode.switch ?
            <div style={{
                backgroundColor: "#494949",
                padding: "12px"
            }}>
                <p className='form-description' style={{ margin: 0 }}>{selectedNode.description}</p>
            </div>
            : <div style={{
                backgroundColor: "#494949"
            }}>
                <p className='form-description' style={{
                    marginBottom: selectedNode.description ? 'inherit' : 0,
                    padding: selectedNode.description ? '12px' : 0
                }}>{selectedNode.description}</p>
                {id === "Backend" && <div style={{ padding: "12px", backgroundColor: "#404040" }}>
                    <div className={`d-flex ${usingExistingBackend ? 'mb-3' : ''}`}>
                        <button className='btn btn-sm'
                            onClick={() => {
                                setBackendConfigRef(undefined)
                                setUsingExistingBackend(false)
                                setRoute({
                                    ...route,
                                    backend_ref: undefined
                                })
                            }}
                            style={{
                                padding: "6px 12px",
                                backgroundColor: usingExistingBackend ? "#494849" : "#f9b000",
                                color: "#fff"
                            }}>Create a new backend</button>
                        <button className='btn btn-sm' onClick={() => setUsingExistingBackend(true)} style={{
                            padding: "6px 12px",
                            backgroundColor: usingExistingBackend ? "#f9b000" : "#494849",
                            color: "#fff"
                        }}>Select an existing backend</button>
                    </div>
                    {usingExistingBackend && <SelectInput
                        id="backend_select"
                        value={route.backend_ref}
                        placeholder="Select an existing backend"
                        label=""
                        onChange={backend_ref => setRoute({
                            ...route,
                            backend_ref
                        })}
                        possibleValues={backends}
                        transformer={item => ({ label: item.name, value: item.id })}
                    />}
                </div>}
                {(!usingExistingBackend || id !== "Backend") ? <div style={{ padding: '0 12px 12px' }}>
                    <Form
                        value={value}
                        schema={formSchema}
                        flow={formFlow}
                        onSubmit={item => {
                            try {
                                if (config_schema)
                                    updatePlugin(id, item)
                                else
                                    changeValues((flow || config_flow || Object.keys(schema || config_schema))
                                        .reduce((acc, curr) => {
                                            if (curr.flow)
                                                return [...acc, ...curr.flow]
                                            return [...acc, curr]
                                        }, [])
                                        .map(field => {
                                            const fieldName = `${selectedNode.field ? `${selectedNode.field}.` : ''}${field}`
                                            return { name: fieldName, value: read(item, field) }
                                        }))
                                setSelectedNode(undefined)
                            } catch (err) {
                                console.log(err)
                            }
                        }}
                        footer={({ valid }) => <div className='d-flex mt-4'>
                            <button className="btn btn-sm btn-success"
                                style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }}
                                onClick={valid}>
                                <i className='fas fa-save me-2'></i>
                                Update the plugin configuration
                            </button>
                            {!selectedNode.default && <RemoveComponent />}
                        </div>}
                    />
                </div> : <>
                    {backendConfigRef && <BackendForm isCreation={false} value={backendConfigRef} style={{
                        maxWidth: '100%'
                    }} foldable={true} />}
                    <button className="btn btn-sm btn-success m-3"
                        style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }}
                        onClick={() => {
                            nextClient.update(nextClient.ENTITIES.ROUTES, route)
                                .then(newRoute => {
                                    setRoute(newRoute)
                                })
                        }}>
                        <i className='fas fa-save me-2'></i>
                        Update the plugin configuration
                    </button>
                </>}
            </div>
        }
        {selectedNode.switch && !selectedNode.default && <RemoveComponent />}
    </div>
}