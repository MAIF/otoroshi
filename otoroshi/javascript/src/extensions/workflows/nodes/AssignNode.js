import React from 'react'
import { NgCodeRenderer } from '../../../components/nginputs';

export const AssignNode = {
    kind: 'assign',
    flow: ['values'],
    schema: {
        values: {
            type: 'array',
            array: true,
            label: 'Values',
            format: 'form',
            flow: ['name', 'value'],
            schema: {
                name: {
                    type: 'string',
                    label: 'Name'
                },
                value: {
                    renderer: props => <NgCodeRenderer
                        ngOptions={{ spread: true }}
                        rawSchema={{
                            props: {
                                showGutter: false,
                                ace_config: {
                                    onLoad: (editor) => editor.renderer.setPadding(10),
                                    fontSize: 14,
                                },
                                editorOnly: true,
                                height: '10rem',
                                mode: 'json',
                            },
                        }}
                        value={props.value}
                        onChange={(e) => {
                            props.onChange(JSON.parse(e));
                        }}
                    />
                }
            },
        }
    },
    sources: ['output'],
    nodeRenderer: props => {
        return <div className='assign-node'>
            {props.data.workflow?.values?.map(value => {
                return <span key={value.name}>{value.name}</span>
            })}
        </div>
    }
}