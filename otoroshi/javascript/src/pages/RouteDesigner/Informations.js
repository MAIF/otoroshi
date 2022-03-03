import React, { useRef } from 'react'
import { Form, type, constraints, format } from '@maif/react-forms'
import { Location } from '../../components/Location'
import { nextClient } from '../../services/BackOfficeServices'
import { useHistory } from 'react-router-dom'

export const Informations = (props) => {
    const history = useHistory()

    const schema = {
        id: {
            type: type.string,
            visible: false,
            constraints: [constraints.nullable()]
        },
        name: {
            type: type.string,
            label: 'Route name',
            placeholder: 'Your route name',
            help: 'The name of your route. Only for debug and human readability purposes.'
        },
        enabled: {
            type: type.bool,
            label: 'Route enabled'
        },
        debug_flow: {
            type: type.bool,
            label: 'Debug the flow'
        },
        export_reporting: {
            type: type.bool,
            label: 'Export reporting'
        },
        description: {
            type: type.string,
            label: 'Description',
            placeholder: 'Your route description',
            help: 'The description of your route. Only for debug and human readability purposes.'
        },
        groups: {
            type: type.string,
            format: format.select,
            createOption: true,
            isMulti: true,
            label: 'Groups'
        },
        metadata: {
            type: type.object,
            label: 'Metadata',
        },
        tags: {
            type: type.string,
            format: format.select,
            createOption: true,
            isMulti: true,
            label: 'Tags'
        },
        _loc: {
            type: type.object,
            label: 'Location',
            render: ({ onChange, value }) => (
                <Location
                    {...value}
                    onChangeTenant={v => onChange({
                        ...value,
                        tenant: v
                    })}
                    onChangeTeams={v => onChange({
                        ...value,
                        teams: v
                    })}
                />
            )
        }
    }

    const flow = [
        'id',
        'name',
        'enabled',
        'debug_flow',
        'export_reporting',
        'description',
        'groups',
        {
            label: 'Advanced',
            flow: ['metadata', 'tags'],
            collapsed: true
        },
        {
            label: 'Location',
            flow: ['_loc'],
            collapsed: true
        }
    ]

    const ref = useRef()

    return (
        <div className='designer-form'>
            <h3>Route informations</h3>
            <Form
                schema={schema}
                flow={flow}
                value={props.value}
                ref={ref}
                onSubmit={item => {
                    if (props.isCreation)
                        nextClient.create(nextClient.ENTITIES.ROUTES, item)
                            .then(() => history.push(`/routes/${item.id}?tab=flow`))
                    else
                        nextClient.update(nextClient.ENTITIES.BACKENDS, item)
                }}
                footer={() => null}
            />
            <button className='btn btn-success btn-block mt-3'
                onClick={() => ref.current.handleSubmit()}>
                {props.isCreation ? "Create the route" : "Update the route"}
            </button>
        </div>
    )
}