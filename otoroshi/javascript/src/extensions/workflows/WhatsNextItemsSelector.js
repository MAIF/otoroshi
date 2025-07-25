import React, { useEffect, useState } from 'react'

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

import { BasicAuthOperator } from './operators/BasicAuthOperator'
import { EqOperator } from './operators/EqOperator'
import { NowOperator } from './operators/NowOperator'
import { IsFalsyOperator } from './operators/IsFalsyOperator'
import { AddOperator } from './operators/AddOperator'
import { ArrayAppendOperator } from './operators/ArrayAppendOperator'
import { NeqOperator } from './operators/NeqOperator'
import { ArrayAtOperator } from './operators/ArrayAtOperator'
import { ParseDateOperator } from './operators/ParseDateOperator'
import { ParseDateTimeOperator } from './operators/ParseDateTimeOperator'
import { ArrayPageOperator } from './operators/ArrayPageOperator'
import { MapGetOperator } from './operators/MapGetOperator'
import { ParseTimeOperator } from './operators/ParseTimeOperator'
import { MemRefOperator } from './operators/MemRefOperator'
import { StrLowerCaseOperator } from './operators/StrLowerCaseOperator'
import { EncodeBase64Operator } from './operators/EncodeBase64Operator'
import { ExpressionLanguageOperator } from './operators/ExpressionLanguageOperator'
import { NotOperator } from './operators/NotOperator'
import { ProjectionOperator } from './operators/ProjectionOperator'
import { LteOperator } from './operators/LteOperator'
import { StrSplitOperator } from './operators/StrSplitOperator'
import { ArrayPrependOperator } from './operators/ArrayPrependOperator'
import { DecodeBase64Operator } from './operators/DecodeBase64Operator'
import { SubtractOperator } from './operators/SubtractOperator'
import { JsonParseOperator } from './operators/JsonParseOperator'
import { ContainsOperator } from './operators/ContainsOperator'
import { GteOperator } from './operators/GteOperator'
import { LtOperator } from './operators/LtOperator'
import { DivideOperator } from './operators/DivideOperator'
import { MapPutOperator } from './operators/MapPutOperator'
import { MultiplyOperator } from './operators/MultiplyOperator'
import { StrConcatOperator } from './operators/StrConcatOperator'
import { GtOperator } from './operators/GtOperator'
import { StrUpperCaseOperator } from './operators/StrUpperCaseOperator'
import { IsTruthyOperator } from './operators/IsTruthyOperator'
import { MapDelOperator } from './operators/MapDelOperator'
import { ArrayDelOperator } from './operators/ArrayDelOperator'
import { DecrOperator } from './operators/DecrOperator'
import { IncrOperator } from './operators/IncrOperator'
import { ReturnedNode } from './nodes/ReturnedNode'
import { PredicateNode } from './nodes/PredicateNode'
import { WasmNode } from './nodes/WasmNode'


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
            "call": CallNode(),
            "wait": WaitNode(),
            "error": ErrorNode(),
            "value": ValueNode(),
            "predicate": PredicateNode(),
            "returned": ReturnedNode(),
            "wasm": WasmNode()
        }
    },
    {
        name: 'Predicate',
        description: 'Operators are one-key JSON objects used to manipulate data',
        nodes: {
            "$mem_ref": MemRefOperator(),
            "$array_append": ArrayAppendOperator(),
            "$array_prepend": ArrayPrependOperator(),
            "$array_at": ArrayAtOperator(),
            "$array_del": ArrayDelOperator(),
            "$array_page": ArrayPageOperator(),
            "$projection": ProjectionOperator(),
            "$map_put": MapPutOperator(),
            "$map_get": MapGetOperator(),
            "$map_del": MapDelOperator(),
            "$json_parse": JsonParseOperator(),
            "$str_concat": StrConcatOperator(),
            "$is_truthy": IsTruthyOperator(),
            "$is_falsy": IsFalsyOperator(),
            "$contains": ContainsOperator(),
            "$eq": EqOperator(),
            "$neq": NeqOperator(),
            "$gt": GtOperator(),
            "$lt": LtOperator(),
            "$gte": GteOperator(),
            "$lte": LteOperator(),
            "$encode_base64": EncodeBase64Operator(),
            "$decode_base64": DecodeBase64Operator(),
            "$basic_auth": BasicAuthOperator(),
            "$now": NowOperator(),
            "$not": NotOperator(),
            "$parse_datetime": ParseDateTimeOperator(),
            "$parse_date": ParseDateOperator(),
            "$parse_time": ParseTimeOperator(),
            "$add": AddOperator(),
            "$subtract": SubtractOperator(),
            "$multiply": MultiplyOperator(),
            "$divide": DivideOperator(),
            "$incr": IncrOperator(),
            "$decr": DecrOperator(),
            "$str_upper_case": StrUpperCaseOperator(),
            "$str_lower_case": StrLowerCaseOperator(),
            "$str_split": StrSplitOperator(),
            "$expression_language": ExpressionLanguageOperator(),
        }
    }
]

function Category(item) {
    const { name, description, onClick } = item

    return <div className='whats-new-category d-flex-center justify-content-between px-3 py-2' onClick={() => onClick(item)}>
        <div className='whats-next-category-informations'>
            <p className='m-0'>{name}</p>
            <p className='m-0'>{description}</p>
        </div>
        <i className='fas fa-arrow-right' />
    </div>
}

function Node({ node, onClick }) {
    return <div
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
            <p className='m-0' style={{
                fontWeight: 'bold'
            }}>{node.name}</p>
            <p className='m-0'>{node.description}</p>
        </div>
    </div>
}

function UnFoldedCategory({ nodes, onClick }) {

    const filteredNodes = Object.entries(nodes)

    if (filteredNodes.length === 0)
        return <p className='text-center m-0'>No results found</p>

    return filteredNodes
        .map(([_, node], i) => <Node
            node={node}
            onClick={() => onClick(node)}
            key={`${node.label}-${i}`} />)
}

export function Items({ setTitle, handleSelectNode, isOpen, query, selectedCategory, setSelectedCategory }) {

    const onClick = item => {
        setSelectedCategory(item)
        setTitle(item.name)
    }

    useEffect(() => {
        setSelectedCategory(undefined)
    }, [isOpen])

    useEffect(() => {
        if (query.length === 0)
            setSelectedCategory(undefined)
    }, [query])


    if (query.length > 0) {
        const lowercaseQuery = query.toLowerCase()
        return ITEMS_BY_CATEGORY.flatMap(category => Object.entries(category.nodes))
            .filter(([_key, value]) => value.name.toLowerCase().includes(lowercaseQuery) ||
                value.description.toLowerCase().includes(lowercaseQuery) ||
                value.kind.toLowerCase().includes(lowercaseQuery))
            .map(([_, node], i) => <Node
                node={node}
                onClick={() => handleSelectNode(node)}
                key={`${node.label}-${i}`} />)
    }

    if (selectedCategory)
        return <UnFoldedCategory {...selectedCategory} onClick={item => handleSelectNode(item)} />

    const categories = ITEMS_BY_CATEGORY
        .filter(category => Object.entries(category.nodes))

    if (categories.length === 0)
        return <p className='text-center m-0'>No results found</p>

    return categories.map(category => <Category {...category}
        id={category.name}
        key={category.name}
        onClick={onClick} />)
}