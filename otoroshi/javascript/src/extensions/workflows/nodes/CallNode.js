import React from 'react'
import { CORE_FUNCTIONS } from '../models/Functions'
import { NgCodeRenderer, NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';

export function CallNode(_workflow) {
    return {
        label: 'fas fa-code',
        name: 'Call',
        kind: 'call',
        workflow: _workflow,
        flow: ['function', 'args'],
        schema: {
            function: {
                renderer: props => {
                    const options = Object.keys(CORE_FUNCTIONS).includes(props.value) ? CORE_FUNCTIONS : {
                        ...CORE_FUNCTIONS,
                        [props.value]: ""
                    }

                    return <Row title="Select a function to execute">
                        <NgSelectRenderer
                            creatable={true}
                            options={Object.keys(options).map(func => ({ label: func, value: func }))}
                            ngOptions={{ spread: true }}
                            value={props.value}
                            onChange={(k) => props.onChange(k.value)}
                        />
                    </Row>
                }
            },
            args: {
                renderer: props => {
                    return <Row title="Arguments">
                        <NgCodeRenderer
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
                    </Row>
                }
            }
        },
        sources: ['output'],
        nodeRenderer: props => {
            return <div className='assign-node'>
                <span >{props.data.workflow?.function}</span>
            </div>
        }
    }
}