import React from 'react'
import { CORE_FUNCTIONS } from '../models/Functions'
import { NgCodeRenderer, NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';

export function CallNode(_workflow) {
    return {
        label: <i className='fas fa-code' />,
        name: 'Call',
        kind: 'call',
        description: 'Execute a function with args',
        workflow: _workflow,
        flow: ['function', 'args'],
        schema: {
            function: {
                // props: {
                //     creatable: true,
                //     options: Object.keys(CORE_FUNCTIONS),
                // },
                renderer: props => <Row title="Select a function to execute">
                    <NgSelectRenderer
                        options={Object.keys(CORE_FUNCTIONS).map(func => ({ label: func, value: func }))}
                        ngOptions={{ spread: true }}
                        value={props.value}
                        onChange={(k) => props.onChange(k)}
                    />
                </Row>
            },
            args: {
                renderer: props => <Row title="Arguments">
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
                            props.onChange(e);
                        }}
                    />
                </Row>
            }
        },
        sources: ['output'],
        nodeRenderer: props => {
            console.log(props.data.workflow)
            return <div className='assign-node'>
                <span >{props.data.workflow?.function}</span>
            </div>
        }
    }
}