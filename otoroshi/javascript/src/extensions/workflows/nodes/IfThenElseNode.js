import React from 'react'
import { AddNode } from './AddNode'

export const IfThenElseNode = (_workflow) => {
    return {
        label: <i className='fas fa-question' />,
        name: 'IfThenElse',
        description: 'Route items to different branches (true/false)',
        workflow: _workflow,
        kind: 'if',
        type: 'IfThenElse',
        create: (createNode, createEdge) => {
            // const IfNode = createNode(AddNode("If"));
            // const ThenNode = createNode(AddNode("Then"));
            // const ElseNode = createNode(AddNode("Else"));

            // return {
            //     nodes: [IfNode, ThenNode, ElseNode],
            //     edges: [
            //         createEdge(IfNode, ThenNode),
            //         createEdge(ThenNode, ElseNode)
            //     ]
            // }
        }
    }
}