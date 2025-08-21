import React from 'react'
import { NgCodeRenderer } from '../../../components/nginputs'
import { Row } from '../../../components/Row';

export const ReturnedNode = {
    label: 'fas fa-box',
    name: 'Returned',
    kind: 'returned',
    description: ' Overrides the output of the node with the result of an operator',
    flow: ['returned'],
    sources: ['output'],
    schema: {
        returned: {
            renderer: props => {
                return <Row title="Returned operator (optional)">
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
}