import React, { useCallback, useEffect, useState } from 'react';
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
    console.log(NODES, node.kind.toLowerCase(), NODES[node.kind.toLowerCase()])
    return {
        id: uuid(),
        position: findNonOverlappingPosition(nodes),
        type: node.type || 'simple',
        data: {
            isFirst: firstStep,
            ...NODES[node.kind.toLowerCase()](node)
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
    // const params = useParams()
    // const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    const [selectedNode, setSelectedNode] = useState()
    const [isOnCreation, setOnCreationMode] = useState(false)

    const initialState = getInitialNodesFromWorkflow(props.workflow?.config, addInformationsToNode);

    const [nodes, internalSetNodes] = useState(initialState.nodes);
    const [edges, setEdges] = useState(initialState.edges);

    function addInformationsToNode(node) {
        return {
            ...node,
            data: {
                ...(node.data || {}),
                functions: {
                    onDoubleClick: setSelectedNode,
                    openNodesExplorer: setOnCreationMode,
                },
                edges
            },
        }
    }

    const setNodes = nodes => {
        internalSetNodes(nodes.map(node => ({
            ...node,
            data: {
                ...node.data,
                edges
            }
        })))
    }

    const onNodesChange = changes => setNodes(applyNodeChanges(changes, nodes))
    const onEdgesChange = useCallback(
        (changes) => setEdges((eds) => applyEdgeChanges(changes, eds)),
        [],
    )
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

    const handleSelectNode = item => {
        const targetId = uuid()

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


        setNodes([
            ...nodes,
            ...addInformationsToNode({
                id: targetId,
                position: findNonOverlappingPosition(nodes.map(n => n.position)),
                type: item.type || 'simple'
            })
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
            onConnect={onConnect}
            onEdgesChange={onEdgesChange}
            onNodesChange={onNodesChange}
            onClick={() => {
                setOnCreationMode(false)
                setSelectedNode(false)
            }}
            nodes={nodes}
            edges={edges}>
        </Flow>
    </div>
}