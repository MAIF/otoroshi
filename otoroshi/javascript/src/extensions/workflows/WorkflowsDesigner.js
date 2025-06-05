import React, { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom";
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Background } from './Background'
import { Node } from './Node'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import Loader from '../../components/Loader';

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

    return <Loader loading={!workflow.data}>
        <div className='workflow'>
            <DesignerActions />
            <Navbar workflow={workflow.data} save={() => Promise.resolve('saved')} />

            <NodesExplorer isOpen={selectedNode} />
            <Background>
                {/* 
            <NewTaskButton /> */}

                <Node data={{}} onClick={setSelectedNode}>
                    <p>Coucou</p>
                </Node>

            </Background>
        </div>
    </Loader>
}