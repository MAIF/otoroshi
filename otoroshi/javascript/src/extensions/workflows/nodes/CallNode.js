import React from 'react'
import { CORE_FUNCTIONS } from '../models/Functions'
import { NgCodeRenderer } from '../../../components/nginputs';
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
                type: 'select',
                props: {
                    creatable: true,
                    options: Object.keys(CORE_FUNCTIONS),
                },
                label: 'Function',
                placeholder: 'Select a function to execute'
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
            return <div className='assign-node'>
                <span >{props.data.workflow.function}</span>
            </div>
        }
    }
}