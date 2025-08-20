import React from 'react'

import { NgCodeRenderer } from '../../../components/nginputs';

export function LogNode(_workflow) {
    return {
        label: 'fa fa-plug',
        name: 'Log',
        kind: 'log',
        description: 'This function writes whatever the user want to the otoroshi logs',
        workflow: _workflow,
        sources: ['output'],
        schema: {
            args: {
                type: 'form',
                label: 'Arguments',
                schema: {
                    message: {
                        type: "string",
                        label: "Message",
                        description: "The message to log"
                    },
                    params: {
                        type: 'array',
                        label: "Params",
                        description: "The parameters to log",
                        itemRenderer: (props) => {
                            return <div style={{ flex: 1 }}>
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
                                        props.onChange(JSON.parse(e))
                                    }}
                                />
                            </div>
                        },
                    },
                },
                flow: ['message', 'params']
            }
        },
        flow: ['args'],
        nodeRenderer: props => {
            const { args } = props.data.workflow || {}
            return <div>
                {(args?.message || "") + " " + args?.params?.map(param => JSON.stringify(param))}
            </div>
        }
    }
}