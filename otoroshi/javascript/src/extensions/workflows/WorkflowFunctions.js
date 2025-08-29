import React, { useEffect, useState } from 'react'
import { useParams, Link, useHistory } from "react-router-dom"
import * as BackOfficeServices from '../../services/BackOfficeServices'

import Loader from '../../components/Loader'

import { WorkflowSidebar } from './WorkflowSidebar'
import { Table } from '../../components/inputs'

export function WorkflowFunctions(props) {
    const params = useParams()
    const history = useHistory()

    const [workflow, setWorkflow] = useState()

    const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    useEffect(() => {
        props.setTitle("Functions")

        client.findById(params.workflowId)
            .then(workflow => {
                props.setSidebarContent(<WorkflowSidebar {...props} workflow={workflow} />);
                setWorkflow(workflow)
            })
    }, [])

    const columns = [
        {
            title: 'Description',
            filterId: 'description',
            cell: (_, item) => item.description
        },
        {
            title: 'Enabled',
            filterId: 'enabled',
            id: 'enabled',
            style: { textAlign: 'center', width: 90 },
            notFilterable: true,
            cell: (_, item) =>
                item.enabled ? (
                    <span className="fas fa-check-circle" style={{ color: 'var(--color-green)' }} />
                ) : (
                    <span className="fas fa-times" style={{ color: 'var(--color-red)' }} />
                ),
        }
    ]

    const deleteItem = item => console.log('delete item')

    console.log(workflow?.functions)

    return <Loader loading={!workflow}>
        <Table
            parentProps={{ params }}
            navigateTo={(item) => history.push(`/extensions/workflows/${workflow.id}/functions/${item.id}/designer`)}
            navigateOnEdit={(item) => history.push(`/extensions/workflows/${workflow.id}/functions/${item.id}/designer`)}
            selfUrl="extensions/workflows"
            defaultTitle="Functions"
            itemName="Function"
            formSchema={null}
            formFlow={null}
            columns={columns}
            deleteItem={(item) => deleteItem(item)}
            defaultSort="metadata.updated_at"
            defaultSortDesc="true"
            fetchItems={() => Promise.resolve(Object.values(workflow.functions))}
            showActions={true}
            showLink={false}
            extractKey={(item) => item.id}
            rowNavigation={true}
            hideAddItemAction={true}
            itemUrl={(i) => `/bo/dashboard/extensions/workflows/${workflow.id}/functions/${i.id}/designer`}
            rawEditUrl={true}
            injectTopBar={() => (
                <div className="btn-group input-group-btn">
                    <Link className="btn btn-primary btn-sm" to={`functions/new`}>
                        <i className="fas fa-plus-circle" /> Create new function
                    </Link>
                    {props.injectTopBar}
                </div>
            )}
        />
    </Loader>
}