import React, { useCallback, useEffect, useRef, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom";
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Flow } from './Flow'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import Loader from '../../components/Loader';
import { v4 as uuid } from 'uuid';

import {
    applyNodeChanges,
    applyEdgeChanges,
    addEdge,
    ReactFlowProvider,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { findNonOverlappingPosition } from './NewNodeSpawn';
import { NODES, OPERATORS } from './models/Functions';
import ReportExplorer from './ReportExplorer';

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            retry: false,
            refetchOnWindowFocus: false,
        },
    },
});

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

export function defaultNode(nodes, node, firstStep) {
    let data = NODES[(node.kind || node.data.kind).toLowerCase()]

    if (data)
        data = data(node)

    if (!data)
        data = OPERATORS[(node.kind || node.data.kind).toLowerCase()](node)

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
    const newNode = addInformationsToNode(defaultNode(existingNodes, child, isFirst))
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
        config = {
            kind: Object.keys(node).find(key => key.startsWith('$'))
        }
    }

    // console.log(config)

    const childNode = createNode(`${parentId}-${uuid()}`, nodes.map(r => r.position), config, false, addInformationsToNode)
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

    const edges = []

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
            }

            nodes[i] = {
                ...nodes[i],
                data: {
                    ...nodes[i].data,
                    targetHandles: i === 0 ? [] : ['input', ...targets].map(target => {
                        return { id: `${target}-${nodes[i].id}` }
                    }),
                    sourceHandles: [...sources].map(source => {
                        return { id: `${source}-${nodes[i].id}` }
                    })
                }
            }
        }


        const parentNodes = nodes.filter(node => !node.data.isInternal)
        for (let i = 0; i < parentNodes.length - 1; i++) {
            const me = parentNodes[i].id
            const optTarget = parentNodes[i + 1]?.id

            if (optTarget)
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

        let returnedNode = createNode(uuid(), nodes.map(r => r.position), {
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

        nodes.push(returnedNode)

        const lastNode = parentNodes[parentNodes.length - 1]
        if (lastNode)
            edges.push({
                id: 'returned-edge',
                source: lastNode.id,
                sourceHandle: `output-${lastNode.id}`,
                target: returnedNode.id,
                targetHandle: `input-${returnedNode.id}`,
                type: 'customEdge',
                animated: true,
            })

        console.log(returnedNode.data)

        return { edges, nodes }

    } else {
        // TODO - manage other kind
        return { edges: [], nodes: [] }
    }
}

function WorkflowsDesigner(props) {
    const [selectedNode, setSelectedNode] = useState()
    const [isOnCreation, setOnCreationMode] = useState(false)

    const [rfInstance, setRfInstance] = useState(null);

    const initialState = getInitialNodesFromWorkflow(props.workflow?.config, addInformationsToNode)

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

    const graphToJson = () => {
        const nodesWithConnections = nodes.reduce((acc, node) => {
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
            returned: {}
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

    function addHandleSource(nodeId) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        sourceHandles: [
                            ...node.data.sourceHandles,
                            { id: `path-${node.data.sourceHandles.length}` }
                        ]
                    }
                }
            }
            return node
        }))
    }

    function handleDeleteNode(nodeId) {
        setNodes((nds) => nds.filter((node) => node.id !== nodeId));
        setEdges((eds) => eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
    }

    const setNodes = nodes => internalSetNodes(nodes)

    const onNodesChange = changes => setNodes(applyNodeChanges(changes, nodes))
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

            setEdges((eds) => {
                if (!eds.find(e => e.sourceHandle === edge.sourceHandle))
                    return addEdge(edge, eds)
                else
                    return eds
            });
        },
        [setEdges],
    );

    const handleSelectNode = item => {
        const targetId = uuid()

        // console.log("handleSelectNode", "isOnCreation", isOnCreation, item)

        let newNode = addInformationsToNode({
            ...defaultNode([], item, false),
            id: targetId,
            position: isOnCreation.fromOrigin ? isOnCreation.fromOrigin : findNonOverlappingPosition(nodes.map(n => n.position)),
            type: item.type || 'simple',
        })

        if (nodes.length === 0)
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

        setNodes([
            ...nodes,
            newNode
            // ...childrenNodes
        ])

        setEdges([...edges, ...newEdges])

        setOnCreationMode(false)
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