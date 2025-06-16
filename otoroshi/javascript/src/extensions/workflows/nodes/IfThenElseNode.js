import React from 'react'
import { AddNode } from './AddNode'

export const IfThenElseNode = (_workflow) => ({
    label: <i className='fas fa-question' />,
    name: 'IfThenElse',
    description: 'Route items to different branches (true/false)',
    workflow: _workflow,
    kind: 'if',
    type: 'IfThenElse',
    sources: ['if', 'then', 'else'],
    targets: []
})