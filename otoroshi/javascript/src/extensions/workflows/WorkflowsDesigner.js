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
} from '@xyflow/react';
import { NewTask } from './NewTask';
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

export default function QueryContainer(props) {
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
        <WorkflowsDesigner {...props} workflow={workflow.data} />
    </Loader>
}

function defaultNode(nodes, node, firstStep) {
    const data = NODES[node.kind.toLowerCase()](node)

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

    // console.log(workflow)

    if (workflow.kind === 'workflow') {
        const nodes = workflow.steps.reduce((acc, child, idx) => {
            return [
                ...acc,
                addInformationsToNode(
                    defaultNode(acc.map(r => r.position),
                        child,
                        idx === 0
                    ))
            ]
        }, [])

        // nodes.push()

        const edges = []
        for (let i = 0; i < nodes.length - 1; i++) {
            edges.push({
                id: uuid(),
                target: nodes[i].id,
                source: nodes[i + 1].id,
                type: 'customEdge',
                animated: true,
            })
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
                    updateData: updateData
                }
            },
        }
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
                setTimeout(() => {
                    const { clientX, clientY } = 'changedTouches' in event ? event.changedTouches[0] : event;

                    setOnCreationMode({
                        ...connectionState.fromNode,
                        fromOrigin: rfInstance.screenToFlowPosition({
                            x: clientX,
                            y: clientY
                        })
                    })
                    // setSelectedNode(connectionState.fromNode)
                }, 250)
            }
        },
        [rfInstance],
    );

    console.log(selectedNode)

    const onConnect = useCallback(
        (connection) => {
            const edge = {
                ...connection, type: 'customEdge',
                animated: true,
            }
            setEdges((eds) => {
                if (!eds.find(e => e.source === edge.source))
                    return addEdge(edge, eds)
                else
                    return eds
            });
        },
        [setEdges],
    );

    const extentParent = node => {
        return {
            ...node,
            data: {
                ...node.data,
                extent: 'parent'
            },
            extent: 'parent',
        }
    }

    const handleSelectNode = item => {
        const targetId = uuid()

        console.log("handleSelectNode", "isOnCreation", isOnCreation, item)

        if (isOnCreation && isOnCreation.isInternalNode) {
            const newNode = addInformationsToNode(defaultNode(nodes, item, false));
            setNodes([
                ...nodes,
                extentParent({
                    ...newNode,
                    parentId: isOnCreation.id,
                    data: {
                        ...newNode.data,
                        parentId: isOnCreation.id,
                        isFirst: true
                    },
                })
            ])
            // setNodes(nodes.map(node => {
            //     if (node.id === isOnCreation.id) {
            //         return {
            //             ...node,
            //             data: {
            //                 ...node.data,
            //                 workflow: {
            //                     ...node.data.workflow,
            //                     node: addInformationsToNode({
            //                         ...item,
            //                         ...NODES[item.kind.toLowerCase()](item)
            //                     })
            //                 }
            //             }
            //         }
            //     }
            //     return node
            // }))
            setOnCreationMode(false)
            return
        }

        if (isOnCreation) {
            setEdges([
                ...edges,
                {
                    id: uuid(),
                    source: targetId,
                    target: isOnCreation.id,
                    type: 'customEdge',
                    animated: true,
                }
            ])
        }

        const newNode = addInformationsToNode({
            ...defaultNode([], item, false),
            id: targetId,
            position: isOnCreation.fromOrigin ? isOnCreation.fromOrigin : findNonOverlappingPosition(nodes.map(n => n.position)),
            type: item.type || 'simple'
        })

        setNodes([
            ...nodes,
            {
                ...(isOnCreation.data.extent ? extentParent(newNode) : newNode),
                parentId: isOnCreation.data.parentId ? isOnCreation.data.parentId : undefined,
            }
        ])

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

    console.log(rfInstance)

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