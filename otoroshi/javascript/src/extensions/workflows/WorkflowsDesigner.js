import React, { useCallback, useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom";
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Flow } from './Flow'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import Loader from '../../components/Loader';

import {
    applyNodeChanges,
    applyEdgeChanges,
    addEdge,
} from '@xyflow/react';
import { NewTask } from './NewTask';

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

function WorkflowsDesigner() {
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
            position: { x: 0, y: 0 }, // required
            type: 'simple',
            data: { label: <i className='fas fa-arrow-pointer' />, onDoubleClick: setSelectedNode },
        },
        {
            id: '2',
            data: { label: 'World', onDoubleClick: setSelectedNode },
            type: 'simple',
            position: { x: 250, y: 0 },
        },
        {
            id: '3',
            data: { label: 'alsut', onDoubleClick: setSelectedNode },
            type: 'simple',
            position: { x: 550, y: 0 },
        },
    ]

    const initialEdges = [
        { id: '1-2', source: '1', target: '2', type: 'customEdge' },
        { id: '2-3', source: '2', target: '3', type: 'customEdge' }
    ];

    const [nodes, setNodes] = useState(initialNodes);
    const [edges, setEdges] = useState(initialEdges);

    const onNodesChange = useCallback(
        (changes) => setNodes((nds) => applyNodeChanges(changes, nds)),
        [],
    );
    const onEdgesChange = useCallback(
        (changes) => setEdges((eds) => applyEdgeChanges(changes, eds)),
        [],
    );

    const onConnect = useCallback(
        (params) => setEdges((eds) => addEdge(params, eds)),
        [],
    );

    return <Loader loading={!workflow.data}>
        <div className='workflow'>
            <DesignerActions />
            <Navbar workflow={workflow.data} save={() => Promise.resolve('saved')} />

            <NewTask onClick={() => setOnCreationMode(true)}/>

            <NodesExplorer
                isOpen={isOnCreation}
                isEdition={selectedNode}
                node={selectedNode} />
            {/* <NewTaskButton /> */}
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