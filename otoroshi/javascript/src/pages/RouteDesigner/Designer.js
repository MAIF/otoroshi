import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import * as BackOfficeServices from '../../services/BackOfficeServices'
import { Form } from '@maif/react-forms'
import deepSet from 'set-value'
import DEFAULT_FLOW from './Graph'

import '../../style/components/_designer.scss'

export default ({ lineId, value }) => {
    const { routeId } = useParams()

    const [nodes, setNodes] = useState([])
    const [selectedNode, setSelectedNode] = useState()

    const [plugins, setPlugins] = useState([])
    const [filteredPlugins, setFilteredPlugins] = useState([])

    const [route, setRoute] = useState(value)

    useEffect(() => {
        BackOfficeServices.fetchRoute(routeId)
            .then(setRoute)

        BackOfficeServices.getPlugins()
            .then(cpts => cpts.filter(c => c.id && c.id.startsWith('cp:otoroshi.next.plugins')))
            .then(plugins => {
                setPlugins(plugins)
                setFilteredPlugins(plugins)
                setNodes(DEFAULT_FLOW)
            })
    }, [])

    const allowDrop = e => e.preventDefault()
    const onDrag = (e, element) => e.dataTransfer.setData("newElement", JSON.stringify(element))
    const onDrop = (ev, onFlow) => {
        ev.preventDefault()

        const node = JSON.parse(ev.dataTransfer.getData("newElement"))

        addNode(onFlow, node)
    }

    const removeNode = id => {
        const node = nodes.find(node => node.id === id)

        setNodes(nodes.filter(node => node.id !== id))

        // TODO - reset route with original value

        setRoute({
            ...route,
            [node.property]: templateService[node.property]
        })

        if (node.parent === node.id)
            setElements([
                ...elements,
                {
                    ...plugins.find(e => e.id === node.parent),
                    parent: node.parent
                }
            ])
        else
            setElements(elements?.map(element => ({
                ...element,
                elements: element.group === node.parent ? [
                    ...element.elements,
                    {
                        ...plugins.find(e => e.group === node.parent).elements?.find(elt => elt.id === node.id),
                        parent: element.group
                    }
                ] : element.elements
            })))
    }

    const addNode = (onFlow, node) => {
        if ((onFlow === "onOutputStream" && node.onOutputStream) ||
            (onFlow === 'onTargetStream' && node.onTargetStream) ||
            onFlow === 'onInputStream' && (!node.onOutputStream && !node.onTargetStream)) {

            setPlugins(plugins.map(p => {
                if (p.id === node.id)
                    p.selected = true
                return p
            }))
            setFilteredPlugins(plugins.filter(p => p.id !== node.id))

            setNodes([...nodes, {
                ...node,
                onOutputStream: onFlow === 'onOutputStream',
                onTargetStream: onFlow === 'onTargetStream',
                onInputStream: onFlow === 'onInputStream'
            }])

            if (node.switch)
                changeValues([
                    { name: node.property, value: true }
                ])

            setSelectedNode(node)
            console.log(node)
        }
        // TODO - disable out anchor when IN element is selected
    }

    const Dot = ({ icon, children, clickable, onClick, highlighted = true, style = {} }) => <div className='dot' style={{
        cursor: clickable ? 'pointer' : 'initial',
        opacity: highlighted ? 1 : .25,
        backgroundColor: highlighted ? '#f9b000': '#494948',
        ...style
    }} onClick={onClick ? e => {
        e.stopPropagation()
        onClick(e)
    } : e => e.stopPropagation()}>
        {icon && <i className={`fas fa-${icon}`}
            style={{ color: "#fff", fontSize: 20 }} />}
        {children && children}
    </div>

    const Anchor = ({ flow = 'onInputStream', text, highlighted = true, mt = 'initial' }) => <div className='anchor'
        onDragOver={allowDrop} onDrop={e => onDrop(e, flow)}
        style={{
            opacity: highlighted ? 1 : .25,
            marginTop: mt
        }}>
        <div>{text}</div>
    </div>

    const Link = ({ highlighted = true, flex }) => <div className="link" style={{
        opacity: highlighted ? 1 : .25,
        flex: flex ? 1 : 'initial'
    }}></div>

    const Tab = ({ text }) => (
        <div className="studio-tab">
            {text}
        </div>
    )

    const NodeElement = ({ element, setSelectedNode, hideLink, selectedNode, isLast }) => {
        const { id, name } = element
        const highlighted = !selectedNode || selectedNode.id === id

        return <>
            <Dot clickable={true}
                onClick={e => {
                    e.stopPropagation()
                    setSelectedNode(element)
                }} highlighted={highlighted}>
                <span style={{
                    padding: '4px 12px',
                    borderRadius: '4px',
                    color: "#fff",
                    whiteSpace: 'nowrap',
                    width: "fit-content"
                }}>
                    {name || id}
                </span>
            </Dot>
            {!hideLink && <Link highlighted={highlighted} flex={isLast} />}
        </>
    }

    const handleSearch = search => {
        setFilteredPlugins(plugins
            .reduce((acc, e) => e.id.toLowerCase().includes(search.toLowerCase()) ? [...acc, e] : acc, []))
    }

    const changeValues = ops => {
        const newRoute = ops.reduce((newRoute, { name, value }) => {
            return deepSet(_.cloneDeep(newRoute), name, value)
        }, route)

        BackOfficeServices.updateRoute(newRoute)
            .then(() => setRoute(newRoute))
    }

    const saveChanges = () => {
        BackOfficeServices.updateRoute(route)
            .then(newRoute => {
                setRoute(newRoute)
            })
    }

    const SaveButton = () => <button
        className="btn btn-save"
        type="button"
        onClick={saveChanges}
        style={{
            position: 'absolute',
            bottom: '12px',
            right: '12px',
            zIndex: 100
        }}>
        <i className="far fa-paper-plane" style={{ paddingRight: '6px' }} />
        <span>Update route</span>
    </button>

    const inputNodes = nodes.filter(f => f.onInputStream)
    const targetNodes = nodes.filter(f => f.onTargetStream)
    const outputNodes = nodes.filter(f => f.onOutputStream && !f.onTargetStream)

    return (
        <div className="h-100" onClick={() => setSelectedNode(undefined)}>
            <SaveButton />
            <div className="col-sm-4" style={{ paddingLeft: 0 }}>
                <Tab text="Components" />
                <div className="elements">
                    <div style={{
                        height: "calc(100% - 12px)",
                        width: "3px",
                        backgroundColor: "#f9b000",
                        position: 'absolute',
                        left: "24px",
                        top: 0,
                        zIndex: -1
                    }}></div>
                    <SearchBar handleSearch={handleSearch} />
                    <PluginsStack elements={filteredPlugins.filter(p => !p.selected)} onDrag={onDrag} addNode={addNode} />
                </div>
            </div>
            <div className="col-sm-8">
                <div className="row h-100">
                    <div className="col-sm-4" style={{ display: 'flex', flexDirection: 'column' }}>
                        <Tab text="Route" />
                        <div className="main-view">
                            <Dot icon="arrow-down" />
                            <Link highlighted={!selectedNode} />
                            {inputNodes.map((value, i) => <NodeElement
                                element={value}
                                key={`inNodes${i}`}
                                selectedNode={selectedNode}
                                setSelectedNode={setSelectedNode}
                                isLast={(inputNodes.length - 1) === i}
                            />)}
                            <Anchor text="Drop in elements here" highlighted={!selectedNode} />
                            <Link highlighted={!selectedNode} flex={true} />
                        </div>
                        <div className="main-view"
                            style={{
                                backgroundColor: "#494948",
                                borderTop: '2px solid #f9b000',
                                padding: '32px 6px 8px 6px',
                                position: 'relative',
                                opacity: !selectedNode ? 1 : !selectedNode.onTargetStream ? .25 : 1
                            }}>
                            <i className="fas fa-globe-americas"
                                style={{
                                    position: 'absolute',
                                    top: '-18px',
                                    right: '-18px',
                                    fontSize: 42,
                                    color: "#fff",
                                    backgroundColor: "#f9b000",
                                    borderRadius: "50%"
                                }}>
                            </i>
                            {targetNodes.map((value, i, arr) => <NodeElement
                                element={value}
                                key={`targetNodes${i}`}
                                selectedNode={selectedNode}
                                setSelectedNode={setSelectedNode}
                                hideLink={arr.length - 1 === i}
                            />)}
                            <Anchor out={true} flat={true}
                                text="Drop targets elements here"
                                stream="onTargetStream"
                                highlighted={!selectedNode}
                                mt='auto' />
                        </div>
                        <div className="main-view">
                            <Link highlighted={!selectedNode} />
                            {outputNodes.map(([_, value], i) => <NodeElement
                                element={value}
                                key={`outNodes${i}`}
                                setSelectedNode={setSelectedNode}
                                selectedNode={selectedNode}
                                isLast={(outputNodes.length - 1) === i}
                            />)}
                            <Anchor
                                out={true}
                                text="Drop out elements here"
                                stream="onOutputStream"
                                highlighted={!selectedNode} />
                            <Link highlighted={!selectedNode} flex={true} />
                            <Dot icon="arrow-down" />
                        </div>
                    </div>
                    <div className="col-sm-8" style={{ paddingRight: 0 }}>
                        <Tab text="Details" />
                        <EditView
                            selectedNode={selectedNode}
                            setSelectedNode={setSelectedNode}
                            changeValues={changeValues}
                            removeNode={removeNode}
                            route={route}
                            plugins={plugins} />
                    </div>
                </div>
            </div>
        </div>
    )
}

const Element = ({ element, onDrag, n, addNode }) => (
    <div className="element" draggable={true} onDragStart={e => onDrag(e, { ...element })} onClick={e => {
        e.stopPropagation()
        addNode(element.onTargetStream ? 'onTargetStream' : element.onOutputStream ? 'onOutputStream' : 'onInputStream', element)
    }}>
        <div className="element-icon group-icon">
            <span>{n}</span>
        </div>
        <div style={{
            margin: "0 12px",
            textOverflow: 'ellipsis',
            overflow: 'hidden',
            whiteSpace: 'nowrap',
            width: '100%',
            display: 'flex',
            justifyContent: 'space-between'
        }}>
            {element.name.charAt(0).toUpperCase() + element.name.slice(1)}
            <i className="fas fa-arrow-right" style={{ color: "#494948" }} />
        </div>
    </div>
)

const Group = ({ group, icon, elements, onDrag, addNode }) => {
    const [open, setOpen] = useState(false)

    return <div className="group">
        <div className="group-header" style={{ cursor: 'pointer' }} onClick={e => {
            e.stopPropagation()
            setOpen(!open)
        }}>
            <i className={`fas fa-${icon} group-icon`} style={{ color: "#fff" }} />
            <span style={{ color: "#fff", paddingLeft: "12px" }}>{group.charAt(0).toUpperCase() + group.slice(1)}</span>
            <div style={{ marginLeft: 'auto', display: 'flex', width: '64px' }}>
                <div className="flex-center"
                    onClick={e => {
                        e.stopPropagation()
                        setOpen(!open)
                    }}
                    style={{
                        marginRight: '12px',
                        backgroundColor: "#fff",
                        padding: "3px 6px",
                        borderRadius: "8px",
                        cursor: 'pointer'
                    }}>
                    <i className="fas fa-plus" />
                </div>
                <span className="group-size">{elements?.length}</span>
            </div>
        </div>
        {open && <>
            <PluginsStack elements={elements} onDrag={onDrag} addNode={addNode} />
        </>}
    </div>
}

const PluginsStack = ({ elements, ...props }) => {
    return <div className="">
        {elements?.map((element, i) => {
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
}

const SearchBar = ({ handleSearch }) => {
    return <div className="group">
        <div className="group-header">
            <i className="fas fa-search group-icon" />
            <div style={{ paddingLeft: '12px', width: '100%' }}>
                <input
                    type="text"
                    style={{
                        border: 0,
                        padding: '6px 0px 6px 12px',
                        width: '100%',
                        outline: 'none',
                        borderRadius: '4px'
                    }}
                    onChange={e => handleSearch(e.target.value)}
                    placeholder="Search elements" />
            </div>
        </div>
    </div>
}

const EditView = ({ selectedNode, setSelectedNode, route, changeValues, removeNode, plugins }) => {
    if (!selectedNode)
        return <div style={{
            backgroundColor: "rgb(73, 73, 73)",
            textAlign: "center",
            fontStyle: 'italic',
            padding: '8px',
            color: "#fff"
        }}>
            Start by selecting a node
        </div>

    const { id, flow, configFlow, configSchema, schema, name } = selectedNode

    const plugin = ['Backend', 'Frontend'].includes(id) ? DEFAULT_FLOW.find(f => f.id === id) : plugins.find(element => element.id === id)

    const close = () => setSelectedNode(undefined)

    const read = (value, path) => {
        const keys = path.split(".")
        if (keys.length === 1)
            return value[path]

        return read(value[keys[0]], keys.slice(1).join("."))
    }

    const RemoveComponent = ({ mt = 0 }) => (
        <button className={`btn btn-danger btn-block mt-${mt}`} onClick={e => {
            e.stopPropagation()
            setSelectedNode(undefined)
            removeNode(id)
        }}>
            Disable this component
        </button>
    )

    return <div onClick={e => {
        e.stopPropagation()
    }}>
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
                <p style={{ color: "#fff" }}>{selectedNode.description}</p>
                <RemoveComponent />
            </div>
            : <div style={{
                backgroundColor: "#494949",
                padding: '12px'
            }}>
                <Form
                    value={route[selectedNode.field]}
                    schema={schema || configSchema}
                    flow={flow || configFlow}
                    onSubmit={item => {
                        try {
                            changeValues((flow || configFlow || Object.keys(schema || configSchema)).map(field => {
                                const fieldName = `${selectedNode.field ? `${selectedNode.field}.` : ''}${field}`
                                return { name: fieldName, value: read(item, field) }
                            }))
                            close()
                        } catch (err) {
                            console.log(err)
                        }
                    }}
                    footer={({ valid }) => <button className="btn btn-success btn-block"
                        style={{ backgroundColor: "#f9b000", borderColor: '#f9b000', marginTop: '12px' }}
                        onClick={valid}>
                        Update configuration
                    </button>}
                />
                {!selectedNode.default && <RemoveComponent mt={2} />}
            </div>}
    </div>
}