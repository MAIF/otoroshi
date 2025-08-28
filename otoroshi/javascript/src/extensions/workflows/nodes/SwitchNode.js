import React from 'react'
import { Row } from '../../../components/Row'
import { NgForm, NgSelectRenderer } from '../../../components/nginputs'

export const SwitchNode = docs => {
    return {
        type: 'group',
        kind: 'switch',
        sourcesIsArray: true,
        handlePrefix: 'path',
        sources: [],
        height: (data) => `${110 + 20 * data?.sourceHandles?.length}px`,
        targets: [],
        flow: ['paths'],
        form_schema: {
            paths: {
                type: 'array',
                label: 'Paths',
                array: true,
                props: {
                    disableActions: true
                },
                format: 'form',
                flow: ['predicate'],
                schema: {
                    predicate: {
                        renderer: props => {
                            const predicate = props.value || {}

                            const field = Object.keys(predicate || {})[0]

                            const operator = docs.operators.find(ope => ope.name === field)

                            const value = predicate[field]

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
                                            props.onChange({
                                                [operator]: {}
                                            })
                                        }}
                                    />
                                </Row>

                                {operator && <NgForm
                                    flow={operator.flow || Object.keys(operator.form_schema || {})}
                                    schema={operator.form_schema || {}}
                                    value={value}
                                    onChange={newValue => {
                                        props.onChange({
                                            [field]: newValue
                                        })
                                    }}
                                />}
                            </>
                        }
                    }
                }
            }
        }
    }
}