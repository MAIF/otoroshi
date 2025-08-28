import React from 'react'
import { NgCodeRenderer } from '../../../components/nginputs';

export const AssignNode = {
    kind: 'assign',
    flow: ['values'],
    form_schema: {
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
                                    mode: 'json',
                                    onLoad: (editor) => editor.renderer.setPadding(10),
                                    fontSize: 14,
                                },
                                editorOnly: true,
                                height: '10rem',
                            },
                        }}
                        value={props.value}
                        onChange={props.onChange}
                    />
                }
            },
        }
    },
    sources: ['output'],
    nodeRenderer: props => {
        return <div className='assign-node'>
            {props.data.content?.values?.map(value => {
                return <span key={value.name}>{value.name}</span>
            })}
        </div>
    }
}