import { AssignNode } from "../nodes/AssignNode"
import { CallNode } from "../nodes/CallNode"
import { ErrorNode } from "../nodes/ErrorNode"
import { FilterNode } from "../nodes/FilterNode"
import { FlatMapNode } from "../nodes/FlatMapNode"
import { ForEachNode } from "../nodes/ForEachNode"
import { IfThenElseNode } from "../nodes/IfThenElseNode"
import { MapNode } from "../nodes/MapNode"
import { ParallelFlowsNode } from "../nodes/ParallelFlowsNode"
import { SwitchNode } from "../nodes/SwitchNode"
import { ValueNode } from "../nodes/ValueNode"
import { WaitNode } from "../nodes/WaitNode"

import { BasicAuthOperator } from '../operators/BasicAuthOperator'
import { EqOperator } from '../operators/EqOperator'
import { NowOperator } from '../operators/NowOperator'
import { IsFalsyOperator } from '../operators/IsFalsyOperator'
import { ArrayAppendOperator } from '../operators/ArrayAppendOperator'
import { NeqOperator } from '../operators/NeqOperator'
import { ArrayAtOperator } from '../operators/ArrayAtOperator'
import { ParseDateOperator } from '../operators/ParseDateOperator'
import { ParseDateTimeOperator } from '../operators/ParseDateTimeOperator'
import { ArrayPageOperator } from '../operators/ArrayPageOperator'
import { MapGetOperator } from '../operators/MapGetOperator'
import { ParseTimeOperator } from '../operators/ParseTimeOperator'
import { MemRefOperator } from '../operators/MemRefOperator'
import { StrLowerCaseOperator } from '../operators/StrLowerCaseOperator'
import { EncodeBase64Operator } from '../operators/EncodeBase64Operator'
import { ExpressionLanguageOperator } from '../operators/ExpressionLanguageOperator'
import { NotOperator } from '../operators/NotOperator'
import { ProjectionOperator } from '../operators/ProjectionOperator'
import { LteOperator } from '../operators/LteOperator'
import { StrSplitOperator } from '../operators/StrSplitOperator'
import { ArrayPrependOperator } from '../operators/ArrayPrependOperator'
import { DecodeBase64Operator } from '../operators/DecodeBase64Operator'
import { SubtractOperator } from '../operators/SubtractOperator'
import { JsonParseOperator } from '../operators/JsonParseOperator'
import { ContainsOperator } from '../operators/ContainsOperator'
import { GteOperator } from '../operators/GteOperator'
import { LtOperator } from '../operators/LtOperator'
import { DivideOperator } from '../operators/DivideOperator'
import { MapPutOperator } from '../operators/MapPutOperator'
import { MultiplyOperator } from '../operators/MultiplyOperator'
import { StrConcatOperator } from '../operators/StrConcatOperator'
import { GtOperator } from '../operators/GtOperator'
import { StrUpperCaseOperator } from '../operators/StrUpperCaseOperator'
import { IsTruthyOperator } from '../operators/IsTruthyOperator'
import { MapDelOperator } from '../operators/MapDelOperator'
import { ArrayDelOperator } from '../operators/ArrayDelOperator'
import { DecrOperator } from '../operators/DecrOperator'
import { IncrOperator } from '../operators/IncrOperator'
import { ReturnedNode } from "../nodes/ReturnedNode"
import { StartNode } from "../nodes/StartNode"
import { PredicateNode } from "../nodes/PredicateNode"
import { WasmNode } from "../nodes/WasmNode"
import { LogNode } from "../nodes/LogNode"

export const NODES = (docs) => {

    let serverNodes = [
        ...docs.nodes.map(n => ({
            ...n,
            nodes: true,
            schema: n.form_schema,
            kind: n.kind || n.name
        })),
        ...docs.functions.map(n => ({
            ...n,
            functions: true,
            schema: n.form_schema,
            kind: n.kind || n.name
        })),
        ...docs.operators.map(n => ({
            ...n,
            operator: true,
            schema: n.form_schema,
            kind: n.kind || n.name
        }))
    ]

    let items = Object.fromEntries(Object.entries({
        "assign": AssignNode,
        "parallel": ParallelFlowsNode,
        "switch": SwitchNode,
        "if": IfThenElseNode,
        "foreach": ForEachNode,
        "map": MapNode,
        "filter": FilterNode,
        "flatmap": FlatMapNode,
        "call": CallNode,
        "wait": WaitNode,
        "error": ErrorNode,
        "value": ValueNode,
        "returned": ReturnedNode,
        "start": StartNode,
        "predicate": PredicateNode,
        "wasm": WasmNode,
        "log": LogNode,
        "$mem_ref": MemRefOperator,
        "$array_append": ArrayAppendOperator,
        "$array_prepend": ArrayPrependOperator,
        "$array_at": ArrayAtOperator,
        "$array_del": ArrayDelOperator,
        "$array_page": ArrayPageOperator,
        "$projection": ProjectionOperator,
        "$map_put": MapPutOperator,
        "$map_get": MapGetOperator,
        "$map_del": MapDelOperator,
        "$json_parse": JsonParseOperator,
        "$str_concat": StrConcatOperator,
        "$is_truthy": IsTruthyOperator,
        "$is_falsy": IsFalsyOperator,
        "$contains": ContainsOperator,
        "$eq": EqOperator,
        "$neq": NeqOperator,
        "$gt": GtOperator,
        "$lt": LtOperator,
        "$gte": GteOperator,
        "$lte": LteOperator,
        "$encode_base64": EncodeBase64Operator,
        "$decode_base64": DecodeBase64Operator,
        "$basic_auth": BasicAuthOperator,
        "$now": NowOperator,
        "$not": NotOperator,
        "$parse_datetime": ParseDateTimeOperator,
        "$parse_date": ParseDateOperator,
        "$parse_time": ParseTimeOperator,
        "$subtract": SubtractOperator,
        "$multiply": MultiplyOperator,
        "$divide": DivideOperator,
        "$incr": IncrOperator,
        "$decr": DecrOperator,
        "$str_upper_case": StrUpperCaseOperator,
        "$str_lower_case": StrLowerCaseOperator,
        "$str_split": StrSplitOperator,
        "$expression_language": ExpressionLanguageOperator
    })
        .map(([key, node]) => {
            const item = serverNodes.find(n => n.name === key)
            if (item)
                serverNodes = serverNodes.filter(f => f.name !== key)

            return [key,
                workflow => ({
                    ...(item || {}),
                    ...node(workflow)
                })]
        }))


    serverNodes.forEach(node => {
        items[node.name] = () => ({
            ...node,
            kind: node.name
        })
    })

    console.log(items)

    return items
}

export const CORE_FUNCTIONS = {
    "core.log": {

    },
    "core.hello": {

    },
    "core.http_client": {

    },
    "core.wasm_call": {

    },
    "core.workflow_call": {

    },
    "core.system_call": {

    },
    "core.store_keys": {

    },
    "core.store_mget": {

    },
    "core.store_match": {

    },
    "core.store_get": {

    },
    "core.store_set": {

    },
    "core.store_del": {

    },
    "core.emit_event": {

    },
    "core.file_read": {

    },
    "core.file_write": {

    },
    "core.file_del": {

    },
    "core.state_get_all": {

    },
    "core.state_get": {

    },
    "core.send_mail": {

    },
}