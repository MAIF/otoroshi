import React from 'react'

export const PredicateNode = (_workflow) => {
    return {
        label: <i className='fas fa-filter' />,
        kind: 'PredicateNode',
        name: 'Predicate',
        type: 'simple',
        workflow: _workflow,
        sources: ['output']
    }
}