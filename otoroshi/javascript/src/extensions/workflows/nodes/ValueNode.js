import React from 'react'
import { ValueToCheck } from '../operators/ValueToCheck'

export const ValueNode = (_workflow) => ({
    label: <i className='fas fa-cube' />,
    name: 'Value',
    description: 'Apply operators on value',
    workflow: _workflow,
    kind: 'value',
    sources: ['output'],
    schema: {
        value: ValueToCheck('Value', false)
    },
    flow: ['value'],
    nodeRenderer: props => {
        // return <div className='assign-node'>
        //     {JSON.stringify(props.data.workflow?.value, null, 4)}
        // </div>
        return <div style={{
            position: 'absolute',
            top: 30,
            left: 24,
            right: 0,
            bottom: 0,
            // borderBottomLeftRadius: '.75rem',
            borderBottomRightRadius: '.75rem',
            overflow: 'hidden'
        }}>
            <NgCodeRenderer
                ngOptions={{ spread: true }}
                rawSchema={{
                    props: {
                        showGutter: false,
                        ace_config: {
                            fontSize: 8,
                            readOnly: true
                        },
                        editorOnly: true,
                        height: '100%',
                        mode: 'json',
                    },
                }}
                value={props.data.workflow?.returned}
                onChange={(e) => {
                    props.data.functions.handleWorkflowChange(props.id, {
                        ...props.workflow,
                        returned: JSON.parse(e)
                    })
                }}
            />
        </div>
    }
})