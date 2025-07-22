import React from 'react'
import { ValueToCheck } from '../operators/ValueToCheck'

export const PredicateNode = (_workflow) => {
    return {
        label: <i className='fas fa-filter' />,
        description: 'Predicate node',
        kind: 'predicate',
        name: 'Predicate',
        type: 'simple',
        workflow: _workflow,
        sources: ['output'],
        targets: ['PredicateOperator'],
        schema: {
            value: ValueToCheck('Predicate', false)
        },
        flow: ['value']
    }
}