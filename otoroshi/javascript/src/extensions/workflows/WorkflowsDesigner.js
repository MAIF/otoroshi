import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom";
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Flow } from './Flow'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import Loader from '../../components/Loader';
import { v4 as uuid } from 'uuid';
import ELK from 'elkjs/lib/elk.bundled.js';

import {
    applyNodeChanges,
    applyEdgeChanges,
    addEdge,
    ReactFlowProvider,
    useReactFlow,
    useUpdateNodeInternals,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { findNonOverlappingPosition } from './NewNodeSpawn';
import { NODES, OPERATORS } from './models/Functions';
import ReportExplorer from './ReportExplorer';
import { conforms } from 'lodash';

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            retry: false,
            refetchOnWindowFocus: false,
        },
    },
});

const elk = new ELK();

const elkOptions = {
    'elk.algorithm': 'layered',
    'elk.layered.spacing.nodeNodeBetweenLayers': '100',
    'elk.spacing.nodeNode': '80',
};

const applyStyles = () => {
    const pageContainer = document.getElementById('content-scroll-container');
    const parentPageContainer = document.getElementById('content-scroll-container-parent');

    const pagePadding = pageContainer.style.paddingBottom
    pageContainer.style.paddingBottom = 0

    const parentPadding = parentPageContainer.style.padding
    parentPageContainer.style.setProperty('padding', '0px', 'important')

    return () => {
        pageContainer.style.paddingBottom = pagePadding
        parentPageContainer.style.padding = parentPadding
    }
}

export function QueryContainer(props) {
    useEffect(() => {
        props.setTitle(undefined)
        return applyStyles()
    }, [])

    return <QueryClientProvider client={queryClient}>
        <Container {...props} />
    </QueryClientProvider>
}

function Container(props) {
    const params = useParams()

    const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    const workflow = useQuery(
        ['getWorkflow', params.workflowId],
        () => client.findById(params.workflowId));

    return <Loader loading={workflow.isLoading}>
        <ReactFlowProvider>
            <WorkflowsDesigner {...props} workflow={workflow.data} />
        </ReactFlowProvider>
    </Loader>
}

export function createSimpleNode(nodes, node, firstStep) {
    let data = NODES[(node.kind || node.data.kind).toLowerCase()]

    if (data)
        data = data(node)

    if (!data) {
        data = OPERATORS[(node.kind || node.data.kind).toLowerCase()](node)
    }

    return {
        id: uuid(),
        position: findNonOverlappingPosition([nodes[nodes.length - 1]].filter(f => f)),
        type: node.type || data.type || 'simple',
        data: {
            isFirst: firstStep,
            ...data
        }
    }
}

function createNode(id, existingNodes, child, isFirst, addInformationsToNode) {
    const newNode = addInformationsToNode(createSimpleNode(existingNodes, child, isFirst))
    return {
        ...newNode,
        id,
        data: {
            ...newNode.data,
            targetHandles: [],
            sourceHandles: []
        }
    }
}

function createAndLinkChildNode(parentId, nodes, node, addInformationsToNode, handle) {
    let config = node

    // create child operator node
    if (typeof node === "object" && node !== null && Object.keys(node).find(key => key.startsWith('$'))) {
        const kind = Object.keys(node).find(key => key.startsWith('$'))
        config = {
            kind,
            ...node[kind]
        }
    }

    const childNode = createNode(`${parentId}-${handle}`, nodes.map(r => r.position), config, false, addInformationsToNode)
    childNode.data.isInternal = true

    return {
        node: childNode,
        edge: {
            id: uuid(),
            source: parentId,
            sourceHandle: `${handle}-${parentId}`,
            target: childNode.id,
            targetHandle: `input-${childNode.id}`,
            type: 'customEdge',
            animated: true,
        }
    }
}

function getInitialNodesFromWorkflow(workflow, addInformationsToNode) {
    if (!workflow)
        return { edges: [], nodes: [] }

    let edges = []

    if (workflow.kind === 'workflow') {
        let nodes = workflow.steps.reduce((acc, child, idx) => {
            return [
                ...acc,
                createNode(uuid(), acc.map(r => r.position), child, idx === 0, addInformationsToNode)
            ]
        }, [])

        for (let i = 0; i < nodes.length; i++) {
            const { targets = [], sources = [] } = nodes[i].data

            const { workflow } = nodes[i].data
            const parentId = nodes[i].id

            if (workflow.node) {
                const { node, edge } = createAndLinkChildNode(parentId, nodes, workflow.node, addInformationsToNode, 'node')
                nodes.push(node)
                edges.push(edge)
            } else if (workflow.kind === "if") {
                const ifOperator = createAndLinkChildNode(parentId, nodes, workflow.predicate, addInformationsToNode, 'predicate')
                nodes.push(ifOperator.node)
                edges.push(ifOperator.edge)

                const elseOperator = createAndLinkChildNode(parentId, nodes, workflow.else, addInformationsToNode, 'else')
                nodes.push(elseOperator.node)
                edges.push(elseOperator.edge)

                const thenOperator = createAndLinkChildNode(parentId, nodes, workflow.then, addInformationsToNode, 'then')
                nodes.push(thenOperator.node)
                edges.push(thenOperator.edge)
            } else if (workflow.steps) {
                nodes[i] = {
                    ...nodes[i],
                    data: {
                        ...nodes[i].data,
                        sourceHandles: [...Array(workflow.steps.length)].map((_, i) => {
                            return { id: `path-${i}` }
                        })
                    }
                }

                workflow.steps.forEach(step => {
                    console.log(step)
                    const subFlow = getInitialNodesFromWorkflow(step, addInformationsToNode)

                    console.log(subFlow)
                })
            }

            nodes[i] = {
                ...nodes[i],
                data: {
                    ...nodes[i].data,
                    targetHandles: i === 0 ? [] : ['input', ...targets].map(target => {
                        return { id: `${target}-${nodes[i].id}` }
                    }),
                    sourceHandles: [
                        ...nodes[i].data.sourceHandles,
                        ...[...sources].map(source => ({ id: `${source}-${nodes[i].id}` }))
                    ]
                }
            }
        }

        const parentNodes = nodes.filter(node => !node.data.isInternal && node.data.kind !== 'returned')
        for (let i = 0; i < parentNodes.length - 1; i++) {
            const parent = parentNodes[i]
            const me = parent.id
            const optTarget = parentNodes[i + 1]?.id

            if (optTarget) {
                if (parent.data.kind === 'if') {
                    ['predicate', 'else', 'then'].map(handle => {
                        const childId = `${me}-${handle}`
                        edges.push({
                            id: `${me}-${handle}-edge`,
                            source: childId,
                            sourceHandle: `output-${childId}`,
                            target: optTarget,
                            targetHandle: `input-${optTarget}`,
                            type: 'customEdge',
                            animated: true,
                        })
                    })
                } else {
                    edges.push({
                        id: uuid(),
                        source: me,
                        sourceHandle: `output-${me}`,
                        target: optTarget,
                        targetHandle: `input-${optTarget}`,
                        type: 'customEdge',
                        animated: true,
                    })
                }
            }
        }

        let returnedNode = createNode(uuid(), nodes.map(r => r.position), {
            returned: {
                ...(workflow.returned || {}),
            },
            kind: 'returned'
        }, false, addInformationsToNode)

        returnedNode.id = 'returned-node'

        returnedNode = {
            ...returnedNode,
            data: {
                ...returnedNode.data,
                targetHandles: [{ id: `input-${returnedNode.id}` }],
                sourceHandles: []
            }
        }

        nodes.push(returnedNode)

        edges = linkTheLastNodeToReturnedNode(nodes, edges)

        return { edges, nodes }

    } else {
        // TODO - manage other kind
        return { edges: [], nodes: [] }
    }
}

function linkTheLastNodeToReturnedNode(nodes, edges) {
    const newEdges = edges

    if (!edges.find(edge => edge.id === 'returned-edge')) {
        const parentNodes = nodes.filter(node => !node.data.isInternal && node.data.kind !== 'returned')

        const lastNode = parentNodes[parentNodes.length - 1]
        if (lastNode) {

            if (lastNode.kind === 'if') {
                // newEdges.push({
                //     id: 'predicate-edge',
                //     source: `${lastNode.id}-${handle}`,
                //     sourceHandle: `${lastNode.id}-${handle}`,
                //     target: returnedNode.id,
                //     targetHandle: `input-${returnedNode.id}`,
                //     type: 'customEdge',
                //     animated: true,
                // })
                // console.log(edges)
            }
            else {
                newEdges.push({
                    id: 'returned-edge',
                    source: lastNode.id,
                    sourceHandle: `output-${lastNode.id}`,
                    target: 'returned-node',
                    targetHandle: `input-returned-node`,
                    type: 'customEdge',
                    animated: true,
                })
            }
        }
    }

    return newEdges
}

const buildingGraph = (workflow, addInformationsToNode, parentId) => {

    const me = uuid()

    let edges = []
    let nodes = []

    console.log('kind', workflow.kind)

    if (workflow.kind === 'workflow') {
        let childrenNodes = []

        for (let i = 0; i < workflow.steps.length; i++) {
            const subflow = workflow.steps[i]
            const child = buildingGraph(subflow, addInformationsToNode, me)

            childrenNodes = childrenNodes.concat(child.nodes)
            edges = edges.concat(child.edges)
        }

        let current = createNode(me, [], workflow, !parentId, addInformationsToNode)
        current.customSourceHandles = [...Array(workflow.steps.length)].map((_, i) => ({ id: `path-${i}` }))
        nodes.push(current)

        childrenNodes.forEach((child, i) => {
            edges.push({
                id: `${me}-${child.id}`,
                source: me,
                sourceHandle: `path-${i}`,
                target: child.id,
                targetHandle: `input-${child.id}`,
                type: 'customEdge',
                animated: true,
            })
            nodes.push(child)
        })

        let returnedNode = createNode(parentId ? `${me}-returned-node` : 'returned-node', [], {
            returned: {
                ...(workflow.returned || {}),
            },
            kind: 'returned'
        }, false, addInformationsToNode)

        returnedNode = {
            ...returnedNode,
            data: {
                ...returnedNode.data,
                targetHandles: [{ id: `input-${returnedNode.id}` }],
                sourceHandles: []
            }
        }

        edges.push({
            id: `${me}-returned-node`,
            source: me,
            sourceHandle: `output-${me}`,
            target: returnedNode.id,
            targetHandle: `input-${returnedNode.id}`,
            type: 'customEdge',
            animated: true,
        })

        nodes.push(returnedNode)

        // edges = linkTheLastNodeToReturnedNode(nodes, edges)
    } else if (workflow.kind === "if") {
        // const ifOperator = createAndLinkChildNode(parentId, nodes, workflow.predicate, addInformationsToNode, 'predicate')
        // nodes.push(ifOperator.node)
        // edges.push(ifOperator.edge)

        // const elseOperator = createAndLinkChildNode(parentId, nodes, workflow.else, addInformationsToNode, 'else')
        // nodes.push(elseOperator.node)
        // edges.push(elseOperator.edge)

        // const thenOperator = createAndLinkChildNode(parentId, nodes, workflow.then, addInformationsToNode, 'then')
        // nodes.push(thenOperator.node)
        // edges.push(thenOperator.edge)
    } else if (workflow.kind === 'flatmap') {

    } else if (workflow.kind === 'foreach') {
        const { node, edge } = createAndLinkChildNode(me, [], workflow.node, addInformationsToNode, 'node')

        const current = createNode(me, [], workflow, !parentId, addInformationsToNode)
        nodes.push(current)

        nodes.push(node)
        edges.push(edge)
    } else if (workflow.kind === 'map') {

    } else if (workflow.kind === 'switch') {

    } else {
        const simpleNode = createNode(me, [], workflow, false, addInformationsToNode)
        nodes.push(simpleNode)

        // edges.push({
        //     id: `${parentId}-${me}`,
        //     source: parentId,
        //     sourceHandle: `output-${parentId}`,
        //     target: me,
        //     targetHandle: `input-${me}`,
        //     type: 'customEdge',
        //     animated: true,
        // })
    }

    console.log('nodes', nodes)

    for (let i = 0; i < nodes.length; i++) {
        const { targets = [], sources = [] } = nodes[i].data

        nodes[i] = {
            ...nodes[i],
            data: {
                ...nodes[i].data,
                targetHandles: i === 0 ? [] : ['input', ...targets].map(target => {
                    return { id: `${target}-${nodes[i].id}` }
                }),
                sourceHandles: [
                    ...(nodes[i].customSourceHandles || []),
                    ...[...sources].map(source => ({ id: `${source}-${nodes[i].id}` }))
                ]
            }
        }

        delete nodes[i].customSourceHandles
    }

    return { edges, nodes }
}

function WorkflowsDesigner(props) {
    const [selectedNode, setSelectedNode] = useState()
    const [isOnCreation, setOnCreationMode] = useState(false)

    const [rfInstance, setRfInstance] = useState(null);

    // const initialState = getInitialNodesFromWorkflow(props.workflow?.config, addInformationsToNode)

    const initialState = buildingGraph(props.workflow?.config, addInformationsToNode)

    const [nodes, internalSetNodes] = useState(initialState.nodes)
    const [edges, setEdges] = useState(initialState.edges)

    const [report, setReport] = useState()
    const [reportIsOpen, setReportStatus] = useState(false)

    useEffect(() => {
        // FIX ??
        window.addEventListener('error', e => {
            if (e.message === 'ResizeObserver loop completed with undelivered notifications.') {
                const resizeObserverErrDiv = document.getElementById('webpack-dev-server-client-overlay-div');
                const resizeObserverErr = document.getElementById('webpack-dev-server-client-overlay');
                if (resizeObserverErr) {
                    resizeObserverErr.setAttribute('style', 'display: none');
                }
                if (resizeObserverErrDiv) {
                    resizeObserverErrDiv.setAttribute('style', 'display: none');
                }
            }
        });
    }, [])

    const { fitView } = useReactFlow();
    const updateNodeInternals = useUpdateNodeInternals()

    const getLayoutedElements = (nodes, edges, options = {}) => {
        const isHorizontal = options?.['elk.direction'] === 'RIGHT';
        const graph = {
            id: 'root',
            layoutOptions: options,
            children: nodes.map((node) => ({
                ...node,
                // Adjust the target and source handle positions based on the layout
                // direction.
                targetPosition: isHorizontal ? 'left' : 'top',
                sourcePosition: isHorizontal ? 'right' : 'bottom',

                // Hardcode a width and height for elk to use when layouting.
                width: 200,
                height: 100,
            })),
            edges: edges,
        };

        return elk
            .layout(graph)
            .then((layoutedGraph) => ({
                nodes: layoutedGraph.children.map((node) => ({
                    ...node,
                    // React Flow expects a position property on the node instead of `x`
                    // and `y` fields.
                    position: { x: node.x, y: node.y },
                })),

                edges: layoutedGraph.edges,
            }))
            .catch(console.error);
    };

    const onLayout = ({ direction, nodes, edges, from }) => {
        const opts = { 'elk.direction': direction, ...elkOptions };

        getLayoutedElements(nodes, edges, opts)
            .then(({ nodes: layoutedNodes, edges: layoutedEdges }) => {
                setNodes(layoutedNodes)
                setEdges(layoutedEdges)
                fitView();
            });
    };

    useLayoutEffect(() => {
        onLayout({ direction: 'RIGHT', nodes, edges, from: 'useLayoutEffect' });
    }, []);

    const graphToJson = () => {
        const nodesWithConnections = nodes
            .filter(node => node.id !== 'returned-node')
            .reduce((acc, node) => {
                const connections = edges
                    .filter(edge => edge.source === node.id && !edge.sourceHandle.startsWith('output'))
                return [
                    ...acc,
                    {
                        node,
                        connections: connections.map(connection => ({
                            node: nodes.find(n => n.id === connection.target),
                            handle: connection.sourceHandle.replace(`-${connection.source}`, '')
                        }))
                    }
                ]
            }, [])

        const nodesWithChildren = nodesWithConnections.reduce((acc, item) => {
            const { node } = item
            if (!nodesWithConnections.find(n => n.connections.find(conn => conn.node.id === node.id))) {
                return [...acc, item]
            }
            return acc
        }, [])

        return nodesWithChildren.reduce((outputWorkflow, { node, connections }) => {
            let { kind, workflow } = node.data

            if (kind === 'workflow') {
                workflow.steps = connections.map(connection => connection.node.data.workflow)
            } else if (workflow.node) {
                workflow.node = connections.find(connection => connection.handle === 'node')?.node.data.workflow
            }
            return {
                ...outputWorkflow,
                steps: [
                    ...outputWorkflow.steps,
                    workflow
                ]
            }
        }, {
            kind: "workflow",
            steps: [],
            returned: nodes.find(node => node.id === 'returned-node').data.workflow.returned || {}
        })

    }

    const handleSave = () => {
        const config = graphToJson()

        const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

        client.update({
            ...props.workflow,
            config
        })

        return Promise.resolve()
    }

    function updateData(props, changes) {
        setNodes(nodes.map(node => {
            if (node.id === props.id) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        ...changes
                    }
                }
            }
            return node
        }))
    }

    function addInformationsToNode(node) {
        return {
            ...node,
            data: {
                ...(node.data || {}),
                functions: {
                    onDoubleClick: setSelectedNode,
                    openNodesExplorer: setOnCreationMode,
                    handleDeleteNode: handleDeleteNode,
                    updateData: updateData,
                    addHandleSource: addHandleSource,
                    handleWorkflowChange: handleWorkflowChange
                }
            },
        }
    }

    function handleWorkflowChange(nodeId, workflow) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        workflow
                    }
                }
            }
            return node
        }))
    }

    function addHandleSource(nodeId, handlePrefix) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        sourceHandles: [
                            ...node.data.sourceHandles,
                            { id: `${handlePrefix ? handlePrefix : path}-${node.data.sourceHandles.length}` }
                        ]
                    }
                }
            }
            return node
        }))

        const sourceEl = document.querySelector(`[data-id="${nodeId}"]`);
        sourceEl.style.height = `${Number(sourceEl.style.height.split('px')[0]) + 20}px`

        updateNodeInternals(nodeId)
    }

    function handleDeleteNode(nodeId) {
        setNodes((nds) => nds.filter((node) => node.id !== nodeId));
        setEdges((eds) => eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
    }

    const setNodes = nodes => internalSetNodes(nodes)

    const onNodesChange = useCallback(
        (changes) => {
            return setNodes(eds => applyNodeChanges(changes, eds))
        }, [])
    const onEdgesChange = useCallback(
        (changes) => setEdges((eds) => applyEdgeChanges(changes, eds)),
        [],
    )

    const onConnectEnd = useCallback(
        (event, connectionState) => {
            if (!connectionState.isValid) {
                event.stopPropagation()

                setTimeout(() => {
                    setOnCreationMode({
                        ...connectionState.fromNode,
                        handle: connectionState.fromHandle
                    })
                }, 2)
            }
        },
        [rfInstance],
    );

    const onConnect = useCallback(
        (connection) => {
            const edge = {
                ...connection, type: 'customEdge',
                animated: true,
            }

            const newEdges = !edges.find(e => e.sourceHandle === edge.sourceHandle) ? addEdge(edge, edges) : edges

            setEdges(newEdges)
            onLayout({ direction: 'RIGHT', nodes, edges: newEdges, from: 'onConnect' })
        },
        [setEdges, nodes],
    );

    const handleSelectNode = item => {
        const targetId = uuid()

        let newNode = addInformationsToNode({
            ...createSimpleNode([], item, false),
            id: targetId,
            position: isOnCreation.fromOrigin ? isOnCreation.fromOrigin : findNonOverlappingPosition(nodes.map(n => n.position)),
            type: item.type || 'simple',
        })

        // Is it the first node of the flow ?
        if (nodes.filter(node => node.data?.kind !== 'returned').length === 0)
            newNode = {
                ...newNode,
                data: {
                    ...newNode.data,
                    isFirst: true
                }
            }

        let newEdges = []

        if (isOnCreation && isOnCreation.handle) {
            const sourceHandle = isOnCreation.handle.id

            newEdges.push({
                id: uuid(),
                source: isOnCreation.id,
                sourceHandle,
                target: newNode.id,
                targetHandle: `input-${newNode.id}`,
                type: 'customEdge',
                animated: true,
            })

        }

        const { targets = [], sources = [] } = newNode.data
        newNode = {
            ...newNode,
            data: {
                ...newNode.data,
                targetHandles: ['input', ...targets].map(target => {
                    return { id: `${target}-${newNode.id}` }
                }),
                sourceHandles: [...sources].map(source => {
                    return { id: `${source}-${newNode.id}` }
                })
            }
        }

        const newNodes = [...nodes, newNode]
        newEdges = [...edges, ...newEdges]

        newEdges = linkTheLastNodeToReturnedNode(newNodes, newEdges)

        setNodes(newNodes)
        setEdges(newEdges)

        setOnCreationMode(false)

        onLayout({ direction: 'RIGHT', nodes: newNodes, edges: newEdges, from: 'handleSelect' });
    }

    function run() {
        fetch('/extensions/workflows/_test', {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                input: JSON.stringify({}, null, 4),
                workflow: graphToJson()
            }),
        })
            .then((r) => r.json())
            .then(report => {
                setReport(report)
                setReportStatus(true)
            })
    }

    return <div className='workflow'>
        <DesignerActions run={run} />
        <Navbar workflow={props.workflow} save={handleSave} />

        <NewTask onClick={() => setOnCreationMode(true)} />

        <ReportExplorer report={report} isOpen={reportIsOpen} handleClose={() => setReportStatus(false)} />

        <NodesExplorer
            isOpen={isOnCreation}
            isEdition={selectedNode}
            node={selectedNode}
            handleSelectNode={handleSelectNode} />
        <Flow
            setRfInstance={setRfInstance}
            onConnectEnd={onConnectEnd}
            onConnect={onConnect}
            onEdgesChange={onEdgesChange}
            onNodesChange={onNodesChange}
            onClick={() => {
                setOnCreationMode(false)
                setSelectedNode(false)
                setReportStatus(false)
            }}
            onGroupNodeClick={groupNode => {
                setOnCreationMode(groupNode)
                setSelectedNode(groupNode)
            }}
            nodes={nodes}
            edges={edges}>
        </Flow>
    </div>
}