import React from 'react'
import { Row } from '../../../components/Row';
import { NgCodeRenderer } from '../../../components/nginputs';

export const AssignNode = _workflow => ({
    label: <i className="fas fa-equals" />,
    name: 'Assign',
    kind: 'assign',
    description: 'Assign value to an another variable',
    workflow: _workflow,
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
                    // type: 'code',
                    // label: 'Value',
                    // props: {
                    //     editorOnly: true,
                    // },
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
    sources: [],
    nodeRenderer: props => {
        return <div className='assign-node'>
            {props.data.workflow.values?.map(value => {
                return <span key={value.name}>{value.name}</span>
            })}
        </div>
    }
})