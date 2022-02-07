import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import * as BackOfficeServices from '../../services/BackOfficeServices'

import { Form } from '@maif/react-forms'
import deepSet from 'set-value'

import { COMPONENTS } from './Schema'

import '../../style/components/_designer.scss'

const Element = ({ element, onDrag, n, addNode, parent }) => (
    <div className="element" draggable={true} onDragStart={e => onDrag(e, {
        ...element,
        parent
    })}>
        <div className="element-icon group-icon">
            <span>{n}</span>
        </div>
        <span style={{
            marginLeft: "12px",
            textOverflow: 'ellipsis',
            overflow: 'hidden',
            whiteSpace: 'nowrap',
            position: 'relative',
            width: '100%'
        }}>
            {element.id.charAt(0).toUpperCase() + element.id.slice(1)}
            <button style={{
                backgroundColor: "#f9b000",
                right: '8px',
                position: 'absolute',
                border: 0,
                background: "none",
            }}
                onClick={e => {
                    e.stopPropagation()
                    addNode(element.onTargetStream ? 'onTargetStream' : element.onOutputStream ? 'onOutputStream' : 'onInFlow', element)
                }}>
                <i className="fas fa-arrow-right" style={{ color: "#494948" }} />
            </button>
        </span>
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
                <span className="group-size">{elements.length}</span>
            </div>
        </div>
        {open && <>
            <ElementsStack elements={elements} onDrag={onDrag} addNode={addNode} />
        </>}
    </div>
}

const ElementsStack = ({ elements, ...props }) => {
    return <div className="">
        {elements.map((element, i) => {
            if (element.group) {
                if (element.elements.find(e => !e.default))
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

const EditView = ({ selectedNode, setSelectedNode, route, changeValues, removeNode, components }) => {
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

    const { props, id, parent } = selectedNode

    const group = components.find(element => element.group === parent || element.id === id)

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
            <i className={`fas fa-${group.icon || 'bars'} group-icon`}
                style={{
                    color: "#fff",
                    borderBottomLeftRadius: 0
                }} />
            <span style={{ color: "#fff", paddingLeft: "12px" }}>{id}</span>
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
                    value={route}
                    schema={props.schema}
                    flow={props.flow}
                    onSubmit={item => {
                        try {
                            changeValues(props.flow.map(field => ({ name: field, value: read(item, field) })))
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

export default ({ lineId, value }) => {
    const { routeId } = useParams()

    const [nodes, setNodes] = useState([])
    const [elements, setElements] = useState([])
    const [filteredElements, setFilteredElements] = useState([])
    const [selectedNode, setSelectedNode] = useState()
    const [components, setComponents] = useState([])

    const [route, setRoute] = useState(value)

    function filterDefaultElements(components) {
        const groupedElements = components.map((r, i) => ({
            ...components[i],
            elements: components[i].elements.map(el => ({
                ...el,
                parent: components[i].group
            }))
        }))

        const elementsWithSchema = groupedElements.map(elt => ({
            ...elt,
            elements: elt.elements
                .filter(f => {
                    if (f.configSchema || f.default || f.switch)
                        return true
                    if (!f.props)
                        return false
                    return Object.keys(f.props.schema || []).length > 0
                })
        }))

        const { nodes, elements } = elementsWithSchema.reduce((acc, element) => {
            if (element.default) {
                return {
                    ...acc,
                    nodes: [...acc.nodes, element]
                }
            }

            if (element.group) {
                return {
                    elements: [
                        ...acc.elements,
                        {
                            ...element,
                            elements: element.elements.filter(f => !f.default)
                        }
                    ],
                    nodes: [...acc.nodes, ...element.elements.filter(f => f.default)]
                }
            }

            return {
                ...acc,
                elements: [...acc.elements, element]
            }
        }, {
            nodes: [], elements: []
        })

        // console.log(nodes, elements)
        setNodes(nodes)
        setElements(elements)
        setFilteredElements(elements)
    }

    useEffect(() => {
        const components = COMPONENTS;

        // console.log(components)
        setComponents(components)


        BackOfficeServices.fetchRoute(routeId)
            .then(setRoute)

        filterDefaultElements(components)
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
                    ...components.find(e => e.id === node.parent),
                    parent: node.parent
                }
            ])
        else
            setElements(elements.map(element => ({
                ...element,
                elements: element.group === node.parent ? [
                    ...element.elements,
                    {
                        ...components.find(e => e.group === node.parent).elements.find(elt => elt.id === node.id),
                        parent: element.group
                    }
                ] : element.elements
            })))
    }

    const addNode = (onFlow, node) => {
        if ((onFlow === "onOutputStream" && node.onOutputStream) ||
            (onFlow === 'onTargetStream' && node.onTargetStream) ||
            onFlow === 'onInFlow' && (!node.onOutputStream && !node.onTargetStream)) {
            setElements(elements.map(n => ({
                ...n,
                elements: n.elements.filter(element => element.id !== node.id)
            })))

            setNodes([
                ...nodes,
                node
            ])

            if (node.switch)
                changeValues([
                    { name: node.property, value: true }
                ])

            setSelectedNode(node)
        }
        // TODO - disable out anchor when IN element is selected
    }

    const Dot = ({ icon, children, flat, revertFlat, clickable, onClick, highlighted = true, style = {} }) => <div className="dot" style={{
        borderRadius: "50%",
        borderBottomLeftRadius: flat ? "25%" : "50%",
        borderBottomRightRadius: flat ? "25%" : "50%",
        borderTopLeftRadius: revertFlat ? "25%" : "50%",
        borderTopRightRadius: revertFlat ? "25%" : "50%",
        cursor: clickable ? 'pointer' : 'initial',
        opacity: highlighted ? 1 : .25,
        ...style
    }} onClick={onClick ? e => {
        e.stopPropagation()
        onClick(e)
    } : e => e.stopPropagation()}>
        {icon && <i className={`fas fa-${icon}`}
            style={{ color: "#fff", fontSize: 20 }} />}
        {children && children}
    </div>

    const Anchor = ({ flow = 'onInFlow', flat, text, highlighted = true, mt = 0 }) => <div className={`anchor mt-${mt}`}
        onDragOver={allowDrop} onDrop={e => onDrop(e, flow)}
        style={{ opacity: highlighted ? 1 : .25 }}>
        <span style={{
            textAlign: 'center',
            border: flat ? 'transparent' : '1px dotted #f9b000'
        }}>{text}</span>
    </div>

    const Link = ({ highlighted = true }) => <div className="link" style={{
        opacity: highlighted ? 1 : .25
    }}></div>

    const Tab = ({ text }) => (
        <div className="studio-tab">
            {text}
        </div>
    )

    const NodeElement = ({ element, setSelectedNode, hideLink, selectedNode }) => {
        const { id, parent } = element
        const group = components.find(element => element.group === parent || element.id === id)
        const highlighted = !selectedNode || selectedNode.id === id

        return <>
            <Dot clickable={true} onClick={e => {
                e.stopPropagation()
                setSelectedNode(element)
            }} highlighted={highlighted}>
                <span style={{
                    position: 'relative',
                    backgroundColor: !selectedNode ? '#494948' : selectedNode.id === element.id ? '#f9b000' : '#494948',
                    padding: '4px 12px',
                    border: '1px solid #f9b000',
                    borderRadius: '6px',
                    color: "#fff",
                    whiteSpace: 'nowrap',
                    width: "fit-content"
                }}>
                    {id}
                    <div style={{
                        backgroundColor: "#f9b000",
                        position: 'absolute',
                        left: '-16px',
                        top: '-16px',
                        borderRadius: '8px'
                    }}>
                        <i
                            className={`fas fa-${group.icon || 'list'}`}
                            style={{
                                color: "#fff",
                                padding: '6px'
                            }}
                        />
                    </div>
                </span>
            </Dot>
            {!hideLink && <Link highlighted={highlighted} />}
        </>
    }

    const GroupElement = ({ values, hideLink, selectedNode, setSelectedNode }) => {
        if (values.length === 1)
            return <NodeElement element={values[0]}
                selectedNode={selectedNode}
                setSelectedNode={setSelectedNode} />

        const { id, parent } = values[0]
        const group = components.find(element => element.group === parent || element.id === id)

        return <>
            <Dot clickable={true} style={{
                height: `${values.length * 36}px`
            }}>
                <span style={{
                    position: 'relative',
                    backgroundColor: '#494948',
                    padding: '4px 12px',
                    border: '1px solid #f9b000',
                    borderRadius: '6px',
                    color: "#fff",
                    whiteSpace: 'nowrap',
                    width: "fit-content"
                }}>
                    {values.map((node, i) => {
                        return <div style={{
                            padding: "3px 8px",
                            textAlign: 'center',
                            border: "1px solid #f9b000",
                            backgroundColor: !selectedNode ? 'transparent' : selectedNode.id === node.id ? '#f9b000' : 'transparent',
                            color: "#fff",
                            borderRadius: "8px",
                            margin: "4px 0",
                            opacity: !selectedNode ? 1 : selectedNode.id === node.id ? 1 : .25
                        }} key={`${node.parent}${i}`} onClick={() => setSelectedNode(node)}>
                            {node.id}
                        </div>
                    })}
                    <div style={{
                        backgroundColor: "#f9b000",
                        position: 'absolute',
                        left: '-16px',
                        top: '-16px',
                        borderRadius: '8px'
                    }}>
                        <i
                            className={`fas fa-${group.icon || 'list'}`}
                            style={{
                                color: "#fff",
                                padding: '6px'
                            }}
                        />
                    </div>
                </span>
            </Dot>
            {!hideLink && <Link />}
        </>
    }

    const handleSearch = search => {
        setFilteredElements(
            elements
                .map(element => {
                    if (element.group)
                        return {
                            ...element,
                            elements: element.elements.filter(f => f.id.toLowerCase().includes(search.toLowerCase()))
                        }
                    else if (element.id.toLowerCase().includes(search.toLowerCase()))
                        return element
                    return undefined
                })
                .filter(f => f)
        )
    }

    const shallowDiffers = (a, b) => {
        for (let i in a) if (!(i in b)) return true
        for (let i in b) if (a[i] !== b[i]) return true
        return false
    }

    const changeValues = ops => {
        const newRoute = ops.reduce((newRoute, { name, value }) => {
            return deepSet(_.cloneDeep(newRoute), name, value)
        }, route)
        setRoute(newRoute)
    }

    const saveChanges = () => {
        // TODO - check if the route is new
        BackOfficeServices.updateService(route.id, route)
            .then(newRoute => {
                setRoute(newRoute)
            })
    }

    const SaveButton = () => (
        <button
            className="btn btn-save"
            type="button"
            onClick={saveChanges}
            style={{
                position: 'absolute',
                bottom: '12px',
                right: '12px',
                zIndex: 1000
            }}>
            <i className="far fa-paper-plane" style={{ paddingRight: '6px' }} />
            <span>Update route</span>
        </button>
    )

    return (
        <div className="route-designer" onClick={() => setSelectedNode(undefined)}>
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
                    <div className="group">
                        <div className="group-header">
                            <i className={`fas fa-list group-icon`} />
                            <span style={{ color: "#fff", paddingLeft: "12px" }}>Available</span>
                            <div style={{ marginLeft: 'auto', display: 'flex', width: '64px' }}>
                                <span className="group-size">{filteredElements.reduce((acc, curr) => acc + (curr.request || []).length + (curr.out || []).length, 0)}</span>
                            </div>
                        </div>
                    </div>
                    <SearchBar handleSearch={handleSearch} />
                    <ElementsStack elements={filteredElements} onDrag={onDrag} addNode={addNode} />
                </div>
            </div>
            <div className="col-sm-8">
                <div className="row">
                    <div className="col-sm-4">
                        <Tab text="Route" />
                        <div className="main-view">
                            <Dot icon="arrow-down" flat={true} />
                            <Link highlighted={!selectedNode} />
                            {Object.entries(nodes
                                .filter(f => !f.onOutputStream && !f.onTargetStream)
                                .reduce((elts, node) => {
                                    if (elts[node.parent])
                                        elts[node.parent] = [
                                            ...elts[node.parent],
                                            node
                                        ]
                                    else
                                        elts[node.parent] = [node]
                                    return elts
                                }, {}))
                                .map(([_, value], i) => <GroupElement
                                    values={value}
                                    key={`inNodes${i}`}
                                    selectedNode={selectedNode}
                                    setSelectedNode={setSelectedNode}
                                />)}
                            <Anchor text="Drop in elements here" highlighted={!selectedNode} />
                            <Link highlighted={!selectedNode} />
                        </div>
                        <div className="main-view"
                            style={{
                                backgroundColor: "#494948",
                                border: '1px solid #f9b000',
                                paddingBottom: "8px",
                                paddingTop: '32px',
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
                            {Object.entries(nodes
                                .filter(f => f.onTargetStream)
                                .reduce((elts, node) => {
                                    if (elts[node.parent])
                                        elts[node.parent] = [
                                            ...elts[node.parent],
                                            node
                                        ]
                                    else
                                        elts[node.parent] = [node]
                                    return elts
                                }, {}))
                                .map(([_, value], i, arr) => <GroupElement
                                    values={value}
                                    key={`targetNodes${i}`}
                                    selectedNode={selectedNode}
                                    setSelectedNode={setSelectedNode}
                                    hideLink={arr.length - 1 === i} />)}
                            <Anchor out={true} flat={true}
                                text="Drop targets elements here"
                                stream="onTargetStream"
                                highlighted={!selectedNode}
                                mt={4} />
                        </div>
                        <div className="main-view">
                            <Link highlighted={!selectedNode} />
                            {Object.entries(nodes
                                .filter(f => f.onOutputStream && !f.onTargetStream)
                                .reduce((elts, node) => {
                                    if (elts[node.parent])
                                        elts[node.parent] = [
                                            ...elts[node.parent],
                                            node
                                        ]
                                    else
                                        elts[node.parent] = [node]
                                    return elts
                                }, {}))
                                .map(([_, value], i) => <GroupElement
                                    values={value}
                                    key={`outNodes${i}`}
                                    setSelectedNode={setSelectedNode}
                                    selectedNode={selectedNode} />)}
                            <Anchor
                                out={true}
                                text="Drop out elements here"
                                stream="onOutputStream"
                                highlighted={!selectedNode} />
                            <Link highlighted={!selectedNode} />
                            <Dot icon="arrow-down" revertFlat={true} />
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
                            components={components} />
                    </div>
                </div>
            </div>
        </div>
    )
}