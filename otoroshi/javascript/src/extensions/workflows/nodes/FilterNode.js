import React from 'react'

export const FilterNode = (_workflow) => ({
    label: <i className="fas fa-filter" />,
    name: 'Filter',
    description: 'Remove items matching a condition',
    workflow: _workflow,
    kind: 'filter',
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})