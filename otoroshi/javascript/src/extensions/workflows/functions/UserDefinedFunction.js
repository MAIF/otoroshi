import React from 'react'
import { NgCodeRenderer } from "../../../components/nginputs"
import { Row } from "../../../components/Row"

export const UserDefinedFunction = (functionName, value) => {
    return {
        name: functionName,
        kind: "Call",
        description: value.description,
        category: "udfs",
        display_name: value.name,
        // form_schema: {
            // args: {
            //     renderer: (props) => {
            //         return (
            //             <Row title="Arguments">
            //                 <NgCodeRenderer
            //                     ngOptions={{ spread: true }}
            //                     rawSchema={{
            //                         props: {
            //                             showGutter: false,
            //                             ace_config: {
            //                                 onLoad: (editor) => editor.renderer.setPadding(10),
            //                                 fontSize: 14,
            //                             },
            //                             editorOnly: true,
            //                             height: '10rem',
            //                             mode: 'json',
            //                         },
            //                     }}
            //                     value={props.value}
            //                     onChange={(e) => {
            //                         props.onChange(JSON.parse(e))
            //                     }}
            //                 />
            //             </Row>
            //         )
            //     }
            // }
        // },
        icon: "fas fa-code", // TODO - let user defines an icon,
        nodeRenderer: props => {
            return <div className="assign-node">
                <div className="d-flex align-items-center m-0" style={{ gap: '.5rem' }}>
                    <span className="badge bg-success">
                        LOCAL
                    </span>
                </div>
            </div>
        }
    }
}