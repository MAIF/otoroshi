import { AddNode } from "../nodes/AddNode"
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
import { WorkflowNode } from "../nodes/WorkflowNode"

export const NODES = {
    "assign": AssignNode,
    "parallel": ParallelFlowsNode,
    "switch": SwitchNode,
    "if": IfThenElseNode,
    "foreach": ForEachNode,
    "map": MapNode,
    "filter": FilterNode,
    "flatmap": FlatMapNode,
    "workflow": WorkflowNode,
    "call": CallNode,
    "wait": WaitNode,
    "error": ErrorNode,
    "value": ValueNode,
    "addnode": AddNode
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