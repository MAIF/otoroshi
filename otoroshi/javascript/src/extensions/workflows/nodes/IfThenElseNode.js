import React from 'react'

import { NgForm, NgSelectRenderer } from "../../../components/nginputs"
import { Row } from '../../../components/Row'

export const IfThenElseNode = docs => ({
    type: 'group',
    kind: 'if',
    sources: ['then', 'else'],
    flow: ['predicate'],
    form_schema: {
        predicate: {
            renderer: props => {
                console.log(props)
                const field = Object.keys(props.rootValue.predicate || {})[0]

                const operator = docs.operators.find(ope => ope.name === field)

                const value = props.rootValue.predicate[field]

                return <>
                    <Row title="Operator">
                        <NgSelectRenderer
                            isClearable
                            ngOptions={{
                                spread: true
                            }}
                            options={docs.operators.map(r => r.name)}
                            value={field}
                            onChange={operator => {
                                props.rootOnChange({
                                    ...props.rootValue,
                                    predicate: {
                                        [operator]: {}
                                    }
                                })
                            }}
                        />
                    </Row>

                    {operator && <NgForm
                        flow={operator.flow || Object.keys(operator.form_schema || {})}
                        schema={operator.form_schema || {}}
                        value={value}
                        onChange={newValue => {
                            props.rootOnChange({
                                ...props.rootValue,
                                predicate: {
                                    [field]: newValue
                                }
                            })
                        }}
                    />}
                </>
            }
        }
    },
    // nodeRenderer: props => {
    //     return <div className='assign-node'>
    //         <span>{Object.keys(props.data.content.predicate || {})[0]}</span>
    //     </div>
    // }
})