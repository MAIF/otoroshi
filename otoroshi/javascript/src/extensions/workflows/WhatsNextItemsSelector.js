import React, { useEffect, useState } from 'react'

import { WorkflowNode } from "./nodes/WorkflowNode"
import { CallNode } from "./nodes/CallNode"
import { AssignNode } from "./nodes/AssignNode"
import { ParallelFlowsNode } from "./nodes/ParallelFlowsNode"
import { SwitchNode } from "./nodes/SwitchNode"
import { IfThenElseNode } from "./nodes/IfThenElseNode"
import { ForEachNode } from "./nodes/ForEachNode"
import { MapNode } from "./nodes/MapNode"
import { FilterNode } from "./nodes/FilterNode"
import { FlatMapNode } from "./nodes/FlatMapNode"
import { WaitNode } from "./nodes/WaitNode"
import { ErrorNode } from "./nodes/ErrorNode"
import { ValueNode } from "./nodes/ValueNode"

const ITEMS_BY_CATEGORY = [
    {
        name: "Flow",
        description: "Branch, merge or loop the flow, etc",
        nodes: {
            "assign": AssignNode(),
            "parallel": ParallelFlowsNode(),
            "switch": SwitchNode(),
            "if": IfThenElseNode(),
            "foreach": ForEachNode(),
            "map": MapNode(),
            "filter": FilterNode(),
            "flatmap": FlatMapNode()
        }
    },
    {
        name: 'Core',
        description: 'Run code, make HTTP requests, etc',
        nodes: {
            "workflow": WorkflowNode(),
            "call": CallNode(),
            "wait": WaitNode(),
            "error": ErrorNode(),
            "value": ValueNode(),
        }
    }
]

function Category(item) {
    const { name, description, onClick } = item

    return <div className='whats-new-category d-flex-center justify-content-between px-3 py-2' onClick={() => onClick(item)}>
        <div className='whats-next-category-informations'>
            <p>{name}</p>
            <p>{description}</p>
        </div>
        <i className='fas fa-arrow-right' />
    </div>
}

function UnFoldedCategory({ nodes, onClick, query }) {

    const filteredNodes = Object.entries(nodes)
        .filter(([_, node]) => query.length === 0 ||
            node.name.toLowerCase().includes(query) ||
            node.description.toLowerCase().includes(query))

    if (filteredNodes.length === 0)
        return <p className='text-center'>No results found</p>

    return filteredNodes
        .map(([_, node]) => <div
            className='whats-news-category d-flex align-items-center px-3 py-2'
            style={{ cursor: 'pointer' }}
            onClick={() => onClick(node)}>
            <div className='d-flex-center' style={{
                minWidth: 32,
                fontSize: '1.15rem'
            }}>
                {node.label}
            </div>
            <div className=' d-flex flex-column px-2'>
                <p style={{
                    fontWeight: 'bold'
                }}>{node.name}</p>
                <p>{node.description}</p>
            </div>
        </div>
        )
}

export function Items({ setTitle, handleSelectNode, isOpen, query }) {
    const [selectedCategory, setSelectedCategory] = useState()

    useEffect(() => {
        setSelectedCategory(undefined)
    }, [isOpen])

    if (selectedCategory)
        return <UnFoldedCategory
            query={query}
            {...selectedCategory}
            onClick={item => {
                handleSelectNode(item)
            }} />

    const categories = ITEMS_BY_CATEGORY
        .filter(category => query.length === 0 || Object.entries(category.nodes)
            .find(([_, node]) => node.name.toLowerCase().includes(query) ||
                node.description.toLowerCase().includes(query)))

    if (categories.length === 0)
        return <p className='text-center'>No results found</p>

    return categories.map(category => <Category {...category}
        id={category.name}
        onClick={item => {
            setSelectedCategory(item)
            setTitle(item.name)
        }} />)
}