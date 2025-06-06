import React from "react"
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

const NODES = {
    "workflow": WorkflowNode,
    "call": CallNode,
    "assign": AssignNode,
    "parallel": ParallelFlowsNode,
    "switch": SwitchNode,
    "if": IfThenElseNode,
    "foreach": ForEachNode,
    "map": MapNode,
    "filter": FilterNode,
    "flatmap": FlatMapNode,
    "wait": WaitNode,
    "error": ErrorNode,
    "value": ValueNode,
}

export function WhatsNext() {

    return <>
        What happens next ?


    </>
}