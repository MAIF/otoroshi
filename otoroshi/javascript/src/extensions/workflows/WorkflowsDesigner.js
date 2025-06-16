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
    useReactFlow,
    ReactFlowProvider,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { findNonOverlappingPosition } from './NewNodeSpawn';
import { NODES } from './models/Functions';

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
    const data = NODES[(node.kind || node.data.kind).toLowerCase()](node)

    return {
        id: uuid(),
        position: findNonOverlappingPosition(nodes),
        type: node.type || data.type || 'simple',
        data: {
            isFirst: firstStep,
            ...data
        }
    }
}

function getInitialNodesFromWorkflow(workflow, addInformationsToNode) {
    if (!workflow)
        return { edges: [], nodes: [] }

    if (workflow.kind === 'workflow') {
        let nodes = workflow.steps.reduce((acc, child, idx) => {
            const newNode = addInformationsToNode(
                defaultNode(acc.map(r => r.position),
                    child,
                    idx === 0
                ))
            return [
                ...acc,
                {
                    ...newNode,
                    id: `${idx}`,
                    data: {
                        ...newNode.data,
                        targetHandles: [],
                        sourceHandles: []
                    }
                }
            ]
        }, [])

        for (let i = 0; i < nodes.length; i++) {
            const me = nodes[i].id
            // const optSource = nodes[i + 1]?.id
            // const optTarget = nodes[i - 1]?.id

            // const sourceHandles = !optSource ? [] : [{ id: `${me}-s-${optSource}` }]
            // const targetHandles = !optTarget ? [] : [{ id: `${optTarget}-t-${me}` }]

            const { targets = [], sources = [] } = nodes[i].data
            nodes[i] = {
                ...nodes[i],
                data: {
                    ...nodes[i].data,
                    targetHandles: i === 0 ? [] : ['input', ...targets].map((target, i) => {
                        return { id: target }
                    }),
                    sourceHandles: [...sources, 'output'].map((source, i) => {
                        return { id: source }
                    })
                }
            }
        }

        const edges = []
        for (let i = 0; i < nodes.length - 1; i++) {
            // const me = nodes[i].id
            // const optSource = nodes[i + 1]?.id
            // const optTarget = nodes[i + 1]?.id

            // if (optTarget)
            //     edges.push({
            //         id: uuid(),
            //         source: me,
            //         sourceHandle: `output`,
            //         target: optTarget,
            //         targetHandle: `input`,
            //         type: 'customEdge',
            //         animated: true,
            //     })
        }

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

    const initialState = getInitialNodesFromWorkflow(props.workflow?.config, addInformationsToNode);

    const [nodes, internalSetNodes] = useState(initialState.nodes);
    const [edges, setEdges] = useState(initialState.edges);

    // console.log(nodes.map(node => {
    //     return `${node.id} - ${node.data.sourceHandles.map(i => i.id).join(" | ")} - ${node.data.targetHandles.map(i => i.id).join(" | ")}`
    // }), edges)

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
                    addHandleSource: addHandleSource
                }
            },
        }
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
        console.log('handle delete note')
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
                console.log({ connectionState })
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
            console.log(edge)
            setEdges((eds) => {
                if (!eds.find(e => e.source === edge.source))
                    return addEdge(edge, eds)
                else
                    return eds
            });
        },
        [setEdges],
    );

    const handleSelectNode = item => {
        const targetId = uuid()

        console.log("handleSelectNode", "isOnCreation", isOnCreation, item)

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
            // id: 'a-b',
            // source: 'a',
            // sourceHandle: 'a-s-a',
            // target: 'b',
            // targetHandle: 'b-t-a',

            const sourceHandle = isOnCreation.handle.id

            newEdges.push({
                id: uuid(),
                source: isOnCreation.id,
                sourceHandle,
                target: newNode.id,
                targetHandle: 'input',
                type: 'customEdge',
                animated: true,
            })

            console.log(newEdges)
        }

        const { targets = [], sources = [] } = newNode.data
        newNode = {
            ...newNode,
            data: {
                ...newNode.data,
                targetHandles: ['input', ...targets].map((target, i) => {
                    return { id: target }
                }),
                sourceHandles: [...sources, 'output'].map((source, i) => {
                    return { id: source }
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
                workflow: props.workflow.config,
            }),
        })
            .then((r) => r.json())
            .then((r) => {
                console.log({
                    result: r.returned,
                    run: r.run,
                    error: r.error,
                    running: false,
                })
            })
    }

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

    console.log(nodes)

    return <div className='workflow'>
        <DesignerActions run={run} />
        <Navbar workflow={props.workflow} save={() => Promise.resolve('saved')} />

        <NewTask onClick={() => setOnCreationMode(true)} />

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