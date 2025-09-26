import React from 'react'
import { Row } from '../../components/Row';
import { NgAnyRenderer, NgForm, NgJsonRenderer, NgSelectRenderer } from '../../components/nginputs';
import { nodesCatalogSignal } from './models/Functions';

const isString = value => typeof value === 'string' || value instanceof String

export function OperatorSelector({ predicate, handleOperatorChange }) {

    const operators = Object.values(nodesCatalogSignal.value.nodes)
        .filter(node => node.category === 'operators')

    const isStringPredicate = isString(predicate)

    const field = isStringPredicate ? predicate : Object.keys(predicate || {})[0];
    const knownOperator = operators.find((ope) => ope.name === field)
    const operator = knownOperator || 'Others';

    const value = (!isStringPredicate && predicate) ? predicate[field] : undefined

    return <div>
        <Row title="Operator">
            <NgSelectRenderer
                isClearable
                ngOptions={{ spread: true }}
                options={[...operators.map((r) => r.name), 'Others']}
                value={knownOperator ? knownOperator.name : 'Others'}
                onChange={operator => {
                    if (operator === 'Others') {
                        handleOperatorChange({
                            predicate: ""
                        })
                    }
                    else
                        handleOperatorChange({
                            predicate: {
                                [operator]: {},
                            }
                        })
                }}
            />
            {(operator === 'Others' || isStringPredicate) && <div className='mt-2'>
                <NgAnyRenderer
                    label="Value"
                    height="120px"
                    value={predicate}
                    onChange={predicate => {
                        try {
                            handleOperatorChange({ predicate: JSON.parse(predicate) })
                        } catch (err) {
                            handleOperatorChange({ predicate })
                        }
                    }}
                />
            </div>}
        </Row>

        {operator && (
            <NgForm
                flow={operator.flow || Object.keys(operator.form_schema || {})}
                schema={operator.form_schema || {}}
                value={value}
                onChange={(newValue) => {
                    handleOperatorChange({
                        predicate: {
                            [field]: newValue,
                        },
                    })
                }}
            />
        )}
    </div>
}