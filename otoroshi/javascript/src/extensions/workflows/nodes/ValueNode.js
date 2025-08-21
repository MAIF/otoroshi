import React from 'react'
import { ValueToCheck } from '../operators/ValueToCheck'
import { NgCodeRenderer } from '../../../components/nginputs'

export const ValueNode = {
    label: 'fas fa-cube',
    name: 'Value',
    kind: 'value',
    sources: ['output'],
    schema: {
        value: ValueToCheck('Value', false)
    },
    flow: ['value'],
    nodeRenderer: props => {
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
                value={props.data?.value}
            />
        </div>
    }
}