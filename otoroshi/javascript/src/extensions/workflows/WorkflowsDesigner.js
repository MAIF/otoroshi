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

export default function Container(props) {
    useEffect(() => {
        props.setTitle(undefined)
        return applyStyles()
    }, [])

    return <QueryClientProvider client={queryClient}>
        <WorkflowsDesigner {...props} />
    </QueryClientProvider>
}

function WorkflowsDesigner(props) {
    const params = useParams()

    const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    const workflow = useQuery(
        ['getWorkflow', params.workflowId],
        () => client.findById(params.workflowId));

    const [selectedNode, setSelectedNode] = useState()

    const [isOnCreation, setOnCreationMode] = useState(false)

    const initialNodes = [
        {
            id: '1', // required
            position: { x: 250, y: 50 }, // required
            type: 'simple',
            data: {
                label: <i className='fas fa-arrow-pointer' />,
                onDoubleClick: setSelectedNode,
                openNodesExplorer: setOnCreationMode
            },
        },
        {
            id: '2',
            data: {
                label: 'World',
                onDoubleClick: setSelectedNode,
                openNodesExplorer: setOnCreationMode
            },
            type: 'simple',
            position: { x: 0, y: 0 },
        },
        // {
        //     id: '3',
        //     data: { label: 'alsut', onDoubleClick: setSelectedNode },
        //     type: 'simple',
        //     position: { x: 550, y: 0 },
        // }
    ]

    const initialEdges = [
        { id: '1-2', source: '1', target: '2', type: 'customEdge', animated: true, },
        // { id: '2-3', source: '2', target: '3', type: 'customEdge' }
    ]

    const [nodes, internalSetNodes] = useState(initialNodes);
    const [edges, setEdges] = useState(initialEdges);

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
            const edge = { ...connection, type: 'customEdge' }
            setEdges((eds) => {
                console.log(eds, edge)
                if (!eds.find(e => e.source === edge.source))
                    return addEdge(edge, eds)
                else
                    return eds
            });
        },
        [setEdges],
    );

    const handleSelectNode = item => {
        console.log('new item', item)

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


        setNodes([...nodes,
        {
            id: targetId,
            position: findNonOverlappingPosition(nodes.map(n => n.position)),
            type: item.type || 'simple',
            data: {
                onDoubleClick: setSelectedNode,
                item,
                edges,
                openNodesExplorer: setOnCreationMode
            },
        }
        ])

        setOnCreationMode(false)
    }

    console.log(workflow, isOnCreation)

    return <Loader loading={!workflow.data}>
        <div className='workflow'>
            <DesignerActions />
            <Navbar workflow={workflow.data} save={() => Promise.resolve('saved')} />

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
    </Loader >
}