import React, { useEffect } from 'react'
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom"
import * as BackOfficeServices from '../../services/BackOfficeServices'

import Loader from '../../components/Loader'

import { ReactFlowProvider } from '@xyflow/react'
import { WorkflowsDesigner } from './WorkflowsDesigner'
import { WorkflowSidebar } from './WorkflowSidebar'

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            retry: false,
            refetchOnWindowFocus: false,
        },
    },
})

const applyStyles = () => {
    const pageContainer = document.getElementById('content-scroll-container')
    const parentPageContainer = document.getElementById('content-scroll-container-parent')

    const pagePadding = pageContainer.style.paddingBottom
    pageContainer.style.paddingBottom = 0

    const parentPadding = parentPageContainer.style.padding
    parentPageContainer.style.setProperty('padding', '0px', 'important')

    return () => {
        pageContainer.style.paddingBottom = pagePadding
        parentPageContainer.style.padding = parentPadding
    }
}

export function WorkflowsContainer(props) {
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
        () => client.findById(params.workflowId))

    useEffect(() => {
        if (workflow.data)
            props.setSidebarContent(<WorkflowSidebar {...props} workflow={workflow.data} />);
    }, [workflow.isLoading])

    return <Loader loading={workflow.isLoading}>
        <ReactFlowProvider>
            <WorkflowsDesigner {...props} workflow={workflow.data} />
        </ReactFlowProvider>
    </Loader>
}