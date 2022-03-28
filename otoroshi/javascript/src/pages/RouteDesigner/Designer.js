import React, { useEffect, useState } from 'react'
import { useParams, useLocation } from 'react-router'
import { nextClient, getCategories, getPlugins, getOldPlugins } from '../../services/BackOfficeServices'
import { Form, format, type, CodeInput, SelectInput } from '@maif/react-forms'
import { DEFAULT_FLOW, EXCLUDED_PLUGINS, LEGACY_PLUGINS_WRAPPER, PLUGIN_INFORMATIONS_SCHEMA } from './Graph'
import { BackendForm } from '../BackendsPage'
import Loader from './Loader'
import { camelToSnake, camelToSnakeFlow, toUpperCaseLabels } from '../../util'
import { isEqual } from 'lodash'

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

const NodeElement = ({ element, setSelectedNode, hideLink, selectedNode, bold, disableBorder }) => {
    const { id, name, index } = element
    const highlighted = selectedNode && selectedNode.id === id && (selectedNode.plugin_multi_inst ? selectedNode.index === index : true)

    return <>
        <Dot clickable={true}
            selectedNode={selectedNode}
            style={{
                border: disableBorder ? 0 : 1,
                fontWeight: bold ? 'bold' : 'normal'
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

const Anchor = ({ text, highlighted = true, mt = 'initial', addNode }) => <div className='anchor'
    onDragOver={e => e.preventDefault()}
    onDrop={(ev) => {
        ev.preventDefault()
        const node = JSON.parse(ev.dataTransfer.getData("newElement"))
        addNode(node)
    }}
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
            getOldPlugins(),
            nextClient.form(nextClient.ENTITIES.FRONTENDS),
            nextClient.form(nextClient.ENTITIES.BACKENDS),
        ])
            .then(([backends, route, categories, plugins, oldPlugins, frontendForm, backendForm]) => {
                const formatedPlugins = [...plugins, ...oldPlugins.map(p => ({
                    ...p,
                    legacy: true
                }))]
                    .filter(filterSpecificPlugin)
                    .map(plugin => ({
                        ...plugin,
                        config_schema: toUpperCaseLabels(plugin.config_schema || plugin.configSchema || {}),
                        config: plugin.default_config || plugin.defaultConfig
                    }))

                setBackends(backends)
                setCategories([
                    ...categories.filter(category => !['Tunnel', 'Job'].includes(category)),
                    'Ancien plugins'
                ])
                setRoute(route)

                setPlugins(formatedPlugins.map(p => ({
                    ...p,
                    selected: route.plugins.find(r => r.plugin === p.id)
                })))

                setNodes([
                    {
                        ...DEFAULT_FLOW.Frontend,
                        ...frontendForm,
                        config_schema: toUpperCaseLabels({
                            ...frontendForm.schema,
                            ...DEFAULT_FLOW.Frontend.config_schema
                        }),
                        config_flow: DEFAULT_FLOW.Frontend.config_flow
                    },
                    {
                        ...DEFAULT_FLOW.Backend,
                        ...backendForm,
                        config_schema: toUpperCaseLabels(DEFAULT_FLOW.Backend.config_schema(backendForm.schema)),
                        config_flow: DEFAULT_FLOW.Backend.config_flow
                    },
                    ...route.plugins.map(ref => {
                        const plugin = formatedPlugins.find(p => p.id === ref.plugin || p.id === ref.config.plugin)
                        const onInputStream = (plugin.plugin_steps || []).some(s => ["PreRoute", "ValidateAccess", "TransformRequest"].includes(s))
                        const onOutputStream = (plugin.plugin_steps || []).some(s => ["TransformResponse"].includes(s))

                        return {
                            ...plugin,
                            onOutputStream,
                            onInputStream
                        }
                    })
                ].map((node, i) => ({ ...node, index: i })))

                setLoading(false)
            })
    }, [location.pathname])

    const filterSpecificPlugin = plugin => !plugin.plugin_steps.includes('Sink') &&
        !plugin.plugin_steps.includes('HandlesTunnel') &&
        !EXCLUDED_PLUGINS.plugin_visibility.includes(plugin.plugin_visibility) &&
        !EXCLUDED_PLUGINS.ids.includes(plugin.id.replace('cp:', ''))

    const onDrag = (e, element) => e.dataTransfer.setData("newElement", JSON.stringify(element))

    const removeNode = (id, index) => {
        setNodes(nodes.filter((node, i) => node.id !== id && i !== index))
        setRoute({
            ...route,
            plugins: route.plugins.filter(plugin => !plugin.plugin.endsWith(id))
        })

        setPlugins(plugins.map((plugin, i) => {
            if (plugin.id === id && i === index)
                return { ...plugin, selected: undefined }
            return plugin
        }))
    }

    const addNode = node => {
        const newNode = {
            ...node,
            index: nodes.length
        }
        if ((newNode.plugin_steps || []).some(s => ["TransformResponse"].includes(s)) ||
            newNode.onTargetStream ||
            (newNode.plugin_steps || []).some(s => ["PreRoute", "ValidateAccess", "TransformRequest"].includes(s))) {

            setPlugins(plugins.map(p => {
                if (p.id === newNode.id)
                    p.selected = !p.plugin_multi_inst
                return p
            }))

            setRoute({
                ...route,
                plugins: [
                    ...route.plugins,
                    {
                        plugin: newNode.legacy ? LEGACY_PLUGINS_WRAPPER[newNode.pluginType] : newNode.id,
                        config: {
                            ...newNode.config,
                            plugin: newNode.legacy ? newNode.id : undefined
                        }
                    }
                ]
            })

            setNodes([...nodes, newNode])

            setSelectedNode(newNode)
        }
    }

    const handleSearch = search => {
        setPlugins(plugins
            .map(plugin => ({
                ...plugin,
                filtered: !plugin.id.toLowerCase().includes(search.toLowerCase())
            })))
    }

    const updatePlugin = (pluginId, item, updatedField) => {
        return nextClient.update(nextClient.ENTITIES.ROUTES, {
            ...route,
            frontend: updatedField === 'Frontend' ? item : route.frontend,
            backend: updatedField === 'Backend' ? item : route.backend,
            plugins: route.plugins.map(plugin => {
                if (plugin.plugin === pluginId || plugin.config.plugin === pluginId)
                    return {
                        ...plugin,
                        ...item.status,
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
                                <div className="main-view"
                                    style={{ position: 'relative' }}>
                                    <div style={{
                                        position: 'absolute',
                                        top: 0,
                                        left: '-16px',
                                        height: '36px',
                                        backgroundColor: selectedNode && selectedNode.id === "Frontend" ? "#f9b000" : "rgb(73, 73, 72)",
                                        opacity: !selectedNode || (selectedNode && selectedNode.id === "Frontend") ? 1 : .25
                                    }}>
                                        <i className="fas fa-user" style={{
                                            fontSize: '30px',
                                            color: "#fff",
                                            margin: '3px'
                                        }} />
                                    </div>
                                    {inputNodes.slice(0, 1)
                                        .map((value, i) => <NodeElement
                                            element={value}
                                            key={`inNodes${i}`}
                                            selectedNode={selectedNode}
                                            setSelectedNode={setSelectedNode}
                                            isLast={(inputNodes.length - 1) === i}
                                            bold={true}
                                        />)}
                                    <Dot style={{
                                        width: "72px",
                                        height: "36px",
                                        borderRadius: '6px'
                                    }} icon="chevron-down" selectedNode={selectedNode} />
                                    <Link highlighted={!selectedNode} />
                                    {inputNodes.slice(1).map((value, i) => <NodeElement
                                        element={value}
                                        key={`inNodes${i}`}
                                        selectedNode={selectedNode}
                                        setSelectedNode={setSelectedNode}
                                        isLast={(inputNodes.length - 1) === i}
                                    />)}
                                    <Anchor text="Drop elements here" highlighted={!selectedNode} addNode={addNode} />
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
                                        addNode={addNode}
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
                            <i className="fas fa-bullseye"
                                style={{
                                    position: 'absolute',
                                    top: '-3px',
                                    right: '-18px',
                                    fontSize: '42px',
                                    color: "#fff",
                                    backgroundColor: "#f9b000",
                                    borderRadius: "50%"
                                }} />
                            {targetNodes.map((value, i, arr) => <NodeElement
                                element={value}
                                key={`targetNodes${i}`}
                                selectedNode={(selectedNode && selectedNode.onTargetStream) ? selectedNode : undefined}
                                setSelectedNode={setSelectedNode}
                                hideLink={arr.length - 1 === i}
                                disableBorder={true}
                                bold={true}
                            />)}
                        </div></div>
                    <div className="col-sm-8" style={{ paddingRight: 0 }}>
                        {selectedNode ? <EditView
                            setRoute={setRoute}
                            selectedNode={selectedNode}
                            setSelectedNode={setSelectedNode}
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
        <div className="search-group-header" style={{ cursor: 'pointer' }} onClick={e => {
            e.stopPropagation()
            setOpen(!open)
        }}>
            <i className={`fas fa-chevron-${open ? 'down' : 'right'} ms-3`} size={16} style={{ color: "#fff" }} onClick={e => {
                e.stopPropagation()
                setOpen(!open)
            }} />
            <span style={{ color: "#fff", padding: "10px" }}>{group.charAt(0).toUpperCase() + group.slice(1)}</span>
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

const EditView = ({
    selectedNode, setSelectedNode, route,
    removeNode, plugins, updatePlugin, setRoute, backends }) => {

    const [usingExistingBackend, setUsingExistingBackend] = useState(route.backend_ref)
    const [asJsonFormat, toggleJsonFormat] = useState(selectedNode.legacy)
    const [form, setForm] = useState({
        schema: {},
        flow: [],
        value: {},
        originalValue: {}
    })

    const [test, setTest] = useState()
    const [saveable, setSaveable] = useState(false)
    const [backendConfigRef, setBackendConfigRef] = useState()

    useEffect(() => {
        if (route.backend_ref)
            nextClient.fetch(nextClient.ENTITIES.BACKENDS, route.backend_ref)
                .then(setBackendConfigRef)
    }, [route.backend_ref])

    const { id, flow, config_flow, config_schema, schema, name, index } = selectedNode

    const plugin = ['Backend', 'Frontend'].includes(id) ? DEFAULT_FLOW[id] : plugins.find(element => element.id === id || element.id.endsWith(id))

    const onRemove = e => {
        e.stopPropagation()
        setSelectedNode(undefined)
        removeNode(id, index)
    }

    useEffect(() => {
        let formSchema = schema || config_schema
        let formFlow = config_flow || flow

        if (config_schema) {
            formSchema = {
                status: {
                    type: type.object,
                    format: format.form,
                    collapsable: true,
                    collapsed: Object.keys(config_schema).length > 0,
                    label: 'Informations',
                    schema: PLUGIN_INFORMATIONS_SCHEMA
                }
            }
            formFlow = [
                'status'
            ]
            if (Object.keys(config_schema).length > 0) {
                formSchema = {
                    ...formSchema,
                    plugin: {
                        type: type.object,
                        format: format.form,
                        label: null,
                        schema: { ...convertTransformer(config_schema) },
                        flow: [...config_flow || flow].map(step => camelToSnakeFlow(step))
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

        let value = route[selectedNode.field]

        if (!value) {
            const pluginOnFlow = route.plugins.find(p => p.plugin === id || p.config.plugin === id)
            if (pluginOnFlow) {
                const { plugin, config, ...status } = pluginOnFlow
                value = {
                    plugin: config,
                    status
                }
            }
        }

        if (!value) {
            const defaultPlugin = plugins.find(p => p.id === id)
            if (defaultPlugin) {
                const { plugin, config, ...status } = defaultPlugin
                value = {
                    plugin: config,
                    status
                }
            }
        }
        setForm({
            schema: formSchema,
            flow: formFlow,
            value,
            originalValue: value,
            unsavedForm: value
        })
        setTest(value)
        setSaveable(false)

        toggleJsonFormat(selectedNode.legacy)
    }, [selectedNode])

    const onValidate = item => {
        updatePlugin(id, unstringify(item), selectedNode.id)
            .then(() => {
                setForm({ ...form, originalValue: item })
                setSaveable(false)
            })
    }

    // console.log("SCHEMA", form.schema.plugin)
    // console.log("VALUE", form.value)

    return <div onClick={e => {
        e.stopPropagation()
    }} className="plugins-stack">
        <div className="group-header d-flex-between" style={{
            borderBottom: '1px solid #f9b000',
            borderRight: 0
        }}>
            <div className='d-flex-between'>
                <i className={`fas fa-${plugin.icon || 'bars'} group-icon`}
                    style={{
                        color: "#fff",
                        borderBottomLeftRadius: 0
                    }} />
                <span style={{ color: "#fff", paddingLeft: "12px" }}>{name || id}</span>
            </div>
            {!selectedNode.legacy && <div className='mr-2'>
                <button className='btn btn-sm'
                    onClick={() => toggleJsonFormat(false)}
                    style={{
                        padding: "6px 12px",
                        backgroundColor: asJsonFormat ? "#373735" : "#f9b000",
                        color: "#fff"
                    }}>FORM</button>
                <button className='btn btn-sm' onClick={() => {
                    if (!isEqual(test, form.value))
                        setForm({ ...form, value: test })
                    toggleJsonFormat(true)
                }} style={{
                    padding: "6px 12px",
                    backgroundColor: asJsonFormat ? "#f9b000" : "#373735",
                    color: "#fff"
                }}>RAW JSON</button>
            </div>}
        </div>
        <div style={{
            backgroundColor: "#494949"
        }}>
            <Description text={selectedNode.description} />
            {id === "Backend" && <BackendSelector
                backends={backends}
                setBackendConfigRef={setBackendConfigRef}
                setUsingExistingBackend={setUsingExistingBackend}
                setRoute={setRoute}
                usingExistingBackend={usingExistingBackend}
                route={route}
            />}
            {(!usingExistingBackend || id !== "Backend") ? <div style={{ padding: '0 12px 12px' }}>
                {asJsonFormat ? <>
                    <CodeInput
                        showGutter={false}
                        mode="json"
                        width="100%"
                        value={stringify(form.value)}
                        onChange={value => {
                            setSaveable(!isEqual(form.originalValue, value))
                            setForm({ ...form, value })
                        }}
                    />
                    <EditViewActions valid={() => onValidate(form.value)} selectedNode={selectedNode} onRemove={onRemove} saveable={saveable} />
                </>
                    :
                    <Form
                        value={unstringify(form.value)}
                        schema={form.schema}
                        options={{
                            watch: unsaved => {
                                if (unsaved && Object.keys(unsaved).length > 0) {
                                    const hasChanged = !isEqual(unsaved, form.originalValue)
                                    setSaveable(hasChanged)
                                    if (!isEqual(unsaved, test))
                                        setTest(unsaved)
                                }
                            }
                        }}
                        flow={form.formFlow}
                        onSubmit={onValidate}
                        footer={({ valid }) => <EditViewActions valid={valid} selectedNode={selectedNode} onRemove={onRemove} saveable={saveable} />}
                    />}
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
        {(usingExistingBackend && id === "Backend") && !selectedNode.default && <RemoveComponent onRemove={onRemove} />}
    </div>
}

const stringify = item => typeof item === 'object' ? JSON.stringify(item, null, 4) : item
const unstringify = item => {
    if (typeof item === 'object')
        return item
    else {
        try {
            return JSON.parse(item)
        } catch (_) {
            return item
        }
    }
}

const Description = ({ text }) => {
    const [showMore, setShowMore] = useState(false)

    const textLength = text ? text.length : 0
    const maxLength = 120
    const overflows = textLength > maxLength

    return <>
        <p className='form-description' style={{
            marginBottom: text ? 'inherit' : 0,
            padding: text ? '12px' : 0,
            paddingBottom: overflows || !text ? 0 : '12px'
        }}>
            {text ? text.slice(0, showMore ? textLength : maxLength) : ''} {overflows && !showMore ? "..." : ''}
        </p>
        {overflows && <button className='btn btn-sm btn-success me-3 mb-3' onClick={() => setShowMore(!showMore)}
            style={{ marginLeft: 'auto', display: 'block' }}>
            {showMore ? 'Show less' : 'Show more description'}
        </button>}
    </>
}

const RemoveComponent = ({ onRemove }) => <button className='btn btn-sm btn-danger ms-2' onClick={onRemove}>
    <i className='fas fa-trash me-2'></i>
    Remove this component
</button>

const EditViewActions = ({ valid, selectedNode, onRemove, saveable }) => <div className='d-flex mt-4'>
    <button className="btn btn-sm btn-success"
        style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }}
        onClick={valid}
        disabled={!saveable}>
        <i className='fas fa-save me-2' />
        Update the plugin configuration
    </button>
    {!selectedNode.default && <RemoveComponent onRemove={onRemove} />}
</div>

const BackendSelector = ({ setBackendConfigRef, setUsingExistingBackend, setRoute, usingExistingBackend, route, backends }) => (
    <div style={{ padding: "12px", backgroundColor: "#404040" }}>
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
    </div>
)