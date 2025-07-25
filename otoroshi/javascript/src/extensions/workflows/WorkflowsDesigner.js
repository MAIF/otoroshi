import React, { useCallback, useEffect, useState } from 'react';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Flow } from './Flow'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import { v4 as uuid } from 'uuid';

import {
    addEdge,
    useReactFlow,
    useUpdateNodeInternals,
    useNodesState,
    useEdgesState,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { findNonOverlappingPosition } from './NewNodeSpawn';
import { NODES, OPERATORS } from './models/Functions';
import ReportExplorer from './ReportExplorer';
import { onLayout } from './ElkOptions';
import { TagsModal } from './TagsModal';

const GROUP_NODES = ['if', 'switch', 'parallel', 'foreach', 'map', 'filter', 'flatmap']

export function createSimpleNode(nodes, node) {
    // console.log('createSimpleNode', node.kind || node.data?.kind, node)

    let data = NODES[(node.kind || node.data.kind).toLowerCase()]

    if (data)
        data = data("workflow" in node ? node.workflow : node)

    if (!data) {
        data = OPERATORS[(node.kind || node.data.kind).toLowerCase()](node)
    }

    if (data.operator) {
        data = {
            ...data,
            workflow: {
                [data.kind]: {
                    ...node.workflow,
                    description: node.description
                }
            }
        }
    }

    console.log(data, node)

    // maybe try catch here and create a node Value with raw value
    return {
        id: uuid(),
        position: findNonOverlappingPosition([nodes[nodes.length - 1]].filter(f => f)),
        type: node.type || data.type || 'simple',
        data: {
            ...data
        }
    }
}

function createNode(id, existingNodes, child, addInformationsToNode) {
    const newNode = addInformationsToNode(createSimpleNode(existingNodes, child))
    return {
        ...newNode,
        id,
        data: {
            ...newNode.data,
            targetHandles: [],
            sourceHandles: []
        }
    }
}

const buildGraph = (workflows, addInformationsToNode, targetId, handleId) => {

    if (workflows.filter(f => f).length === 0) {
        return { edges: [], nodes: [] }
    }

    const [workflow, ...rest] = workflows

    const me = uuid()

    let edges = []
    let nodes = []

    const useCurrent = workflow.kind !== 'workflow'

    let current = useCurrent ? createNode(me, [], workflow, addInformationsToNode) : undefined

    if (useCurrent)
        nodes.push(current)

    if (workflow.kind === 'workflow') {
        if (workflow.returned) {
            let returnedNode = createNode(`${me}-returned-node`, [], {
                returned: {
                    ...(workflow.returned || {}),
                },
                kind: 'returned'
            }, addInformationsToNode)

            returnedNode = {
                ...returnedNode,
                data: {
                    ...returnedNode.data,
                    targetHandles: [{ id: `input-${returnedNode.id}` }],
                    sourceHandles: []
                }
            }

            const child = buildGraph(workflow.steps.slice().reverse(), addInformationsToNode, returnedNode.id)
            nodes = [...child.nodes, returnedNode]

            edges = edges.concat(child.edges)
            if (targetId && handleId)
                edges.push({
                    id: `${targetId}-returned-node`,
                    source: returnedNode.id,
                    sourceHandle: `output-${returnedNode.id}`,
                    target: targetId,
                    targetHandle: handleId,
                    type: 'customEdge',
                    animated: true,
                })
        } else {
            const child = buildGraph(workflow.steps.slice().reverse(), addInformationsToNode, targetId, handleId)
            nodes = [...child.nodes]

            if (workflow.predicate) {
                const predicate = buildGraph([{
                    kind: 'predicate',
                }], addInformationsToNode, me, 'node')
                const predicateNode = predicate.nodes[0]

                nodes = [predicateNode, ...child.nodes]

                edges.push({
                    id: uuid(),
                    source: predicateNode.id,
                    sourceHandle: `output-${predicateNode.id}`,
                    target: child.nodes[0].id,
                    targetHandle: `input-${child.nodes[0].id}`,
                    type: 'customEdge',
                    animated: true,
                })
            }

            edges = edges.concat(child.edges)
        }
    } else if (workflow.kind === "if") {
        const thensubGraph = workflow.then ? buildGraph([workflow.then], addInformationsToNode, targetId, handleId) : undefined
        const elseGraph = workflow.else ? buildGraph([workflow.else], addInformationsToNode, targetId, handleId) : undefined

        let predicate = workflow.predicate
        if (typeof workflow.predicate === "object" && workflow.predicate !== null && Object.keys(workflow.predicate).find(key => key.startsWith('$'))) {
            const kind = Object.keys(workflow.predicate).find(key => key.startsWith('$'))
            predicate = {
                kind,
                ...workflow.predicate[kind]
            }
        }

        predicate = predicate ? buildGraph([predicate], addInformationsToNode) : undefined

        const hasThenSubGraph = thensubGraph && thensubGraph.nodes.length > 0
        const hasElseGraph = elseGraph && elseGraph.nodes.length > 0
        const hasPredicate = predicate && predicate.nodes.length > 0

        console.log(predicate)

        if (hasThenSubGraph) {
            nodes = nodes.concat(thensubGraph.nodes)
            edges = edges.concat(thensubGraph.edges)
        }

        if (hasElseGraph) {
            nodes = nodes.concat(elseGraph.nodes)
            edges = edges.concat(elseGraph.edges)
        }

        if (hasPredicate) {
            nodes = nodes.concat(predicate.nodes)
            edges = edges.concat(predicate.edges)
        }

        if (hasThenSubGraph)
            edges.push({
                id: `${me}-then`,
                source: me,
                sourceHandle: `then-${me}`,
                target: thensubGraph.nodes[0].id,
                targetHandle: `input-${thensubGraph.nodes[0].id}`,
                type: 'customEdge',
                animated: true,
            })

        if (hasElseGraph)
            edges.push({
                id: `${me}-else`,
                source: me,
                sourceHandle: `else-${me}`,
                target: elseGraph.nodes[0].id,
                targetHandle: `input-${elseGraph.nodes[0].id}`,
                type: 'customEdge',
                animated: true,
            })

        if (hasPredicate)
            edges.push({
                id: `${me}-predicate`,
                source: predicate.nodes[predicate.nodes.length - 1].id,
                sourceHandle: `output-${predicate.nodes[predicate.nodes.length - 1].id}`,
                target: me,
                targetHandle: `predicate-${me}`,
                type: 'customEdge',
                animated: true,
            })

    } else if (
        workflow.kind === 'foreach' ||
        workflow.kind === 'flatmap' ||
        workflow.kind === 'map') {
        const subGraph = buildGraph([workflow.node], addInformationsToNode)

        if (subGraph.nodes.length > 0) {

            nodes = nodes.concat(subGraph.nodes)
            edges = edges.concat(subGraph.edges)

            const handle = current.data.sources[0]

            edges.push({
                id: `${me}-${handle}`,
                source: me,
                sourceHandle: `${handle}-${me}`,
                target: subGraph.nodes[0].id,
                targetHandle: `input-${subGraph.nodes[0].id}`,
                type: 'customEdge',
                animated: true,
            })
        }
    } else if (workflow.kind === 'switch' || workflow.kind === 'parallel') {
        let paths = []

        for (let i = 0; i < workflow.paths.length; i++) {
            const subflow = workflow.paths[i]
            const nestedPath = buildGraph([subflow], addInformationsToNode, targetId, handleId)
            paths.push(nestedPath)
        }

        current.customSourceHandles = [...Array(workflow.paths.length)].map((_, i) => ({ id: `path-${i}` }))

        paths.forEach((path, idx) => {
            if (path.nodes.length > 0)
                edges.push({
                    id: `${me}-path-${idx}`,
                    source: me,
                    sourceHandle: `path-${idx}`,
                    target: path.nodes[0].id,
                    targetHandle: `input-${path.nodes[0].id}`,
                    type: 'customEdge',
                    animated: true,
                })

            nodes = nodes.concat(path.nodes)
            edges = edges.concat(path.edges)
        })
    } else if (workflow.kind === 'filter') {
        let predicate = workflow.predicate
        if (typeof workflow.predicate === "object" && workflow.predicate !== null && Object.keys(workflow.predicate).find(key => key.startsWith('$'))) {
            const kind = Object.keys(workflow.predicate).find(key => key.startsWith('$'))
            predicate = {
                kind,
                ...workflow.predicate[kind]
            }
        }

        predicate = buildGraph([predicate], addInformationsToNode)

        nodes = nodes.concat(predicate.nodes)
        edges = edges.concat(predicate.edges)

        edges.push({
            id: `${me}-predicate`,
            source: predicate.nodes[predicate.nodes.length - 1].id,
            sourceHandle: `output-${predicate.nodes[predicate.nodes.length - 1].id}`,
            target: me,
            targetHandle: `predicate-${me}`,
            type: 'customEdge',
            animated: true,
        })
    } else if (workflow.predicate !== undefined) {
        // sub path of switch or parallel node
        const predicate = buildGraph([{
            kind: 'predicate',
        }], addInformationsToNode, me, 'node')
        const predicateNode = predicate.nodes[0]

        nodes = [predicateNode, current]

        edges.push({
            id: `${me}-${predicateNode.id}`,
            source: predicateNode.id,
            sourceHandle: `output-${predicateNode.id}`,
            target: me,
            targetHandle: `input-${me}`,
            type: 'customEdge',
            animated: true,
        })
    }

    for (let i = 0; i < nodes.length; i++) {
        nodes[i] = setupTargetsAndSources(nodes[i])
    }

    if (targetId && useCurrent && current.data.sources.includes('output')) {
        edges.push({
            id: `${me}-${targetId}`,
            source: me,
            sourceHandle: `${handleId ? handleId : "output"}-${me}`,
            target: targetId,
            targetHandle: `input-${targetId}`,
            type: 'customEdge',
            animated: true,
        })
    }

    if (useCurrent) {
        const subGraph = buildGraph(rest, addInformationsToNode, me)

        return {
            edges: [...subGraph.edges, ...edges],
            nodes: [...subGraph.nodes, ...nodes]
        }
    }

    return { nodes, edges }
}

const setupTargetsAndSources = (node) => {
    const { targets = [], sources = [] } = node.data

    node = {
        ...node,
        data: {
            ...node.data,
            targetHandles: [...targets, 'input'].map(target => {
                return { id: `${target}-${node.id}` }
            }),
            sourceHandles: [
                ...(node.customSourceHandles || []),
                ...sources.map(source => ({ id: `${source}-${node.id}` }))
            ]
        }
    }

    // delete node.customSourceHandles

    return node
}

const initializeGraph = (config, orphans, addInformationsToNode) => {

    let startingNode = createNode('start', [], {
        kind: 'start'
    }, addInformationsToNode)

    startingNode = {
        ...startingNode,
        data: {
            ...startingNode.data,
            targetHandles: [],
            sourceHandles: [{ id: `output-${startingNode.id}` }]
        }
    }

    let returnedNode = createNode('returned-node', [], {
        returned: {
            ...(config.returned || {}),
        },
        kind: 'returned'
    }, addInformationsToNode)

    returnedNode = {
        ...returnedNode,
        data: {
            ...returnedNode.data,
            targetHandles: [{ id: `input-returned-node` }],
            sourceHandles: []
        }
    }
    const subGraph = buildGraph(config.steps.slice().reverse(), addInformationsToNode, returnedNode.id)

    let startingEdge = {
        id: 'start-edge',
        source: startingNode.id,
        sourceHandle: `output-${startingNode.id}`,
        target: returnedNode.id,
        targetHandle: `input-${returnedNode.id}`,
        type: 'customEdge',
        animated: true,
    }

    if (subGraph.nodes.length > 0) {
        startingEdge = {
            id: 'start-edge',
            source: startingNode.id,
            sourceHandle: `output-${startingNode.id}`,
            target: subGraph.nodes[0].id,
            targetHandle: `input-${subGraph.nodes[0].id}`,
            type: 'customEdge',
            animated: true,
        }
    }

    // const orphansNodes = orphans.nodes
    //     .map(orphan => {
    //         const node = createNode(orphan.id, [], orphan.data, addInformationsToNode)
    //         return {
    //             ...setupTargetsAndSources(node),
    //             position: orphan.position
    //         }
    //     })


    return {
        nodes: [
            startingNode,
            ...subGraph.nodes,
            // ...orphansNodes,
            returnedNode
        ],
        edges: [
            ...subGraph.edges,
            // ...orphans.edges,
            startingEdge
        ]
    }
}

const emptyWorkflow = {
    kind: 'workflow',
    steps: [],
}

export function WorkflowsDesigner(props) {
    const updateNodeInternals = useUpdateNodeInternals()
    const { screenToFlowPosition } = useReactFlow();

    const [activeNode, setActiveNode] = useState(false)
    const [showTagModal, openTagModal] = useState(false)

    const [report, setReport] = useState()
    const [reportIsOpen, setReportStatus] = useState(false)

    const [nodes, setNodes, onNodesChange] = useNodesState([])
    const [edges, setEdges, onEdgesChange] = useEdgesState([])

    const [workflow, setWorkflow] = useState(props.workflow)

    useEffect(() => {
        const initialState = initializeGraph(workflow?.config, workflow.orphans, addInformationsToNode)

        onLayout({
            direction: 'RIGHT',
            nodes: initialState.nodes,
            edges: initialState.edges
        })
            .then(({ nodes, edges }) => {
                setNodes(nodes)
                setEdges(edges)
            })
    }, [])

    const graphToJson = () => {
        const start = {
            kind: 'workflow',
            steps: [],
            returned: nodes.find(node => node.id === 'returned-node').data.workflow?.returned,
            id: 'start'
        }

        const startOutput = edges.find(edge => edge.source === 'start')
        const firstNode = nodes.find(node => node.id === startOutput.target)

        const graph = nodeToJson(firstNode, start, false, [], true)

        return graph
    }

    const nodeToJson = (node, currentWorkflow, disableRecursion, alreadySeen, isStart) => {
        const connections = edges.filter(edge => edge.source === node.id)

        const { kind } = node.data

        let subflow = undefined
        let nextNode = undefined

        alreadySeen = alreadySeen.concat([node.id])

        if (node.id.endsWith('returned-node')) {
            return [{
                ...currentWorkflow,
                returned: node.data.workflow?.returned,
                // id: node.id
            }, alreadySeen]
        }

        if (kind === "if") {
            const ifFlow = node.data.workflow

            const then = connections.find(conn => conn.sourceHandle.startsWith('then'))
            const elseTarget = connections.find(conn => conn.sourceHandle.startsWith('else'))

            const targets = edges.filter(edge => edge.target === node.id)
            const predicate = targets.find(conn => conn.targetHandle.startsWith('predicate'))

            let thenNode, predicateNode, elseNode;

            if (predicate) {
                const res = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === predicate.source), emptyWorkflow, true, alreadySeen))
                predicateNode = Object.fromEntries(
                    Object.entries(res[0]?.steps[0]).filter(([key]) => key.startsWith('$'))
                )
                alreadySeen = alreadySeen.concat([res[1]])
            }

            if (then) {
                const res = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === then.target), emptyWorkflow, false, alreadySeen))
                thenNode = res[0]
                alreadySeen = alreadySeen.concat([res[1]])
            }

            if (elseTarget) {
                const res = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === elseTarget.target), emptyWorkflow, false, alreadySeen))
                elseNode = res[0]
                alreadySeen = alreadySeen.concat([res[1]])
            }

            const elseNodeIds = elseNode ? elseNode.steps.map(n => n.id) : []
            const thenNodeIds = thenNode ? thenNode.steps.map(n => n.id) : []
            const commonEnd = thenNode ? thenNode.steps.findIndex(node => elseNodeIds.includes(node.id)) : -1

            if (commonEnd !== -1) {
                nextNode = nodes.find(n => n.id === thenNode.steps[commonEnd].id)

                thenNode = thenNode.steps.slice(0, commonEnd)
                elseNode = elseNode.steps.slice(0, elseNode.steps.findIndex(node => thenNodeIds.includes(node.id)))
            }

            subflow = {
                ...ifFlow,
                predicate: predicateNode,
                then: thenNode,
                else: elseNode,
                kind
            }

        } else if (kind === 'foreach') {
            const foreachFlow = node.data.workflow
            const foreachLoop = connections.find(conn => conn.sourceHandle.startsWith('ForEachLoop'))

            if (foreachLoop) {
                const [node, seen] = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === foreachLoop.target), emptyWorkflow, false, alreadySeen))
                alreadySeen = alreadySeen.concat([seen])

                subflow = {
                    ...foreachFlow,
                    node,
                    kind
                }
            } else {
                subflow = {
                    ...foreachFlow,
                    kind
                }
            }
        } else if (kind === 'map' || kind === 'flatmap' || kind === 'foreach') {
            const flow = node.data.workflow
            const nodeLoop = connections.find(conn => conn.sourceHandle.startsWith('Item'))

            if (nodeLoop) {
                let [node, seen] = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === nodeLoop.target), emptyWorkflow, false, alreadySeen))
                alreadySeen = alreadySeen.concat([seen])

                if (node.steps.length === 1)
                    node = node.steps[0]

                subflow = {
                    ...flow,
                    node,
                    kind
                }
            } else {
                subflow = {
                    ...flow,
                    kind
                }
            }
        } else if (kind === 'filter') {
            const targets = edges.filter(edge => edge.target === node.id)
            const predicateFlow = node.data.workflow
            const predicate = targets.find(conn => conn.targetHandle.startsWith('predicate'))

            if (predicate) {
                const [predicateNode, seen] = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === predicate.source), undefined, true, alreadySeen))
                alreadySeen = alreadySeen.concat([seen])
                subflow = {
                    ...predicateFlow,
                    predicate: predicateNode,
                    kind
                }
            } else {
                subflow = {
                    ...predicateFlow,
                    kind
                }
            }
        } else if (kind === 'parallel') {
            const paths = connections.map(conn => nodes.find(n => n.id === conn.target))

            subflow = paths.reduce((acc, path) => {
                const [pathNode, seen] = removeReturnedFromWorkflow(nodeToJson(path, emptyWorkflow, false, alreadySeen))

                if (GROUP_NODES.includes(pathNode.kind)) {
                    return {
                        ...acc,
                        paths: [...acc.paths, pathNode]
                    }
                } else {
                    const nestedFlow = pathNode
                    const steps = nestedFlow.steps

                    const hasPredicate = steps.length > 1 && steps[0].kind === 'predicate'

                    alreadySeen = alreadySeen.concat([seen])
                    return {
                        ...acc,
                        paths: [...acc.paths, {
                            ...nestedFlow,
                            steps: hasPredicate ? steps.slice(1) : steps,
                            predicate: hasPredicate ? nestedFlow.steps[0] : undefined
                        }]
                    }
                }
            }, {
                ...node.data.workflow,
                kind,
                paths: []
            })
        } else if (kind === 'switch') {
            const paths = connections.map(conn => nodes.find(n => n.id === conn.target))

            subflow = paths.reduce((acc, path) => {
                const [pathNode, seen] = removeReturnedFromWorkflow(nodeToJson(path, emptyWorkflow, false, alreadySeen))

                const nestedFlow = pathNode
                const steps = nestedFlow.steps

                if (steps.length > 1)
                    steps[1].predicate = nestedFlow.steps[0] // Retrieve the first node in the list; it corresponds to the predicate node.

                alreadySeen = alreadySeen.concat([seen])
                return {
                    ...acc,
                    paths: [...acc.paths, {
                        ...nestedFlow,
                        steps: steps.slice(1)
                    }]
                }
            }, {
                ...node.data.workflow,
                paths: []
            })
        } else if (kind === 'predicate') {
            const targets = edges.filter(edge => edge.target === node.id)
            const predicate = targets.find(conn => conn.targetHandle.startsWith('PredicateOperator'))

            let predicateNode;

            if (predicate) {
                const res = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === predicate.source), emptyWorkflow, true, alreadySeen))
                predicateNode = res[0]
                alreadySeen = alreadySeen.concat([res[1]])
            } else {
                predicateNode = {
                    kind: 'predicate'
                }
            }

            subflow = predicateNode
        } else {
            subflow = {
                ...node.data.workflow,
                id: node.id,
                kind
            }
        }

        let outputWorkflow = subflow ? {
            ...subflow,
            id: node.id,
            kind
        } : undefined

        if (currentWorkflow && currentWorkflow.kind === 'workflow') {
            outputWorkflow = {
                ...currentWorkflow,
                steps: [...currentWorkflow.steps, subflow]
            }
        }

        if (nextNode)
            return removeReturnedFromWorkflow(nodeToJson(nextNode, outputWorkflow, false, alreadySeen))

        const output = connections.find(conn => conn.sourceHandle.startsWith('output'))

        if (output && !disableRecursion)
            return removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === output.target), outputWorkflow, false, alreadySeen))

        // if (outputWorkflow && !isStart && outputWorkflow.kind === 'workflow' && outputWorkflow.steps.length === 1)
        //     return removeReturnedFromWorkflow([outputWorkflow.steps[0], alreadySeen])

        if (isStart)
            return [outputWorkflow, alreadySeen]

        return removeReturnedFromWorkflow([outputWorkflow, alreadySeen])
    }

    const removeReturnedFromWorkflow = output => {
        if (output[0].kind === 'workflow' && output[0].id !== 'start') {
            return [
                { ...output[0], returned: undefined },
                output[1]
            ]
        }
        return output
    }

    const handleSave = () => {
        const graph = graphToJson()

        console.log(graph[0])

        const [config, seen] = graph
        const alreadySeen = seen.flatMap(f => f)

        const orphans = nodes.filter(node => node.id !== 'start' && !alreadySeen.includes(node.id))
        const orphansEdges = orphans.flatMap(orphan => edges.filter(edge => edge.target === orphan.id || edge.source === orphan.id))
            .reduce((edges, edge) => {
                if (!edges.find(e => e.id === edge.id) && edge.id !== 'start-edge') {
                    return [...edges, edge]
                }
                return edges
            }, [])

        const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

        client.update({
            ...workflow,
            config,
            orphans: {
                nodes: orphans.map(r => ({
                    id: r.id,
                    position: r.position,
                    data: r.data.workflow
                })),
                edges: orphansEdges
            }
        })

        return Promise.resolve()
    }

    function updateData(props, changes) {
        console.log('update data', props, changes)
        setNodes(nodes.map(node => {
            if (node.id === props.id) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        ...changes
                    }
                }
            }
            return node
        }))
    }

    function addInformationsToNode(node) {
        return {
            ...node,
            data: {
                ...(node.data || {}),
                functions: {
                    onDoubleClick: setActiveNode,
                    onNodeDelete: onNodeDelete,
                    updateData: updateData,
                    appendSourceHandle: appendSourceHandle,
                    handleWorkflowChange: handleNodeDataChange,
                    deleteHandle: deleteHandle
                }
            },
        }
    }

    function handleNodeDataChange(nodeId, workflow) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        workflow
                    }
                }
            }
            return node
        }))
    }

    function appendSourceHandle(nodeId, handlePrefix) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        sourceHandles: [
                            ...node.data.sourceHandles,
                            { id: `${handlePrefix ? handlePrefix : path}-${node.data.sourceHandles.length}` }
                        ]
                    }
                }
            }
            return node
        }))

        const sourceEl = document.querySelector(`[data-id="${nodeId}"]`);
        sourceEl.style.height = `${Number(sourceEl.style.height.split('px')[0]) + 20}px`

        setTimeout(() => {
            updateNodeInternals(nodeId)
        }, 250)
    }

    function deleteHandle(nodeId, handleId) {
        setNodes(eds => eds.map(node => {
            if (node.id === nodeId) {
                return {
                    ...node,
                    data: {
                        ...node.data,
                        sourceHandles: node.data.sourceHandles.filter(ha => ha.id !== handleId)
                    }
                }
            }
            return node
        }))

        setEdges(eds => eds.filter(edge => edge.sourceHandle !== handleId))

        const sourceEl = document.querySelector(`[data-id="${nodeId}"]`);
        sourceEl.style.height = `${Number(sourceEl.style.height.split('px')[0]) - 20}px`

        updateNodeInternals(nodeId)
    }

    function onNodeDelete(nodeId) {
        setNodes((nds) => nds.filter((node) => node.id !== nodeId));
        setEdges((eds) => eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
    }

    const onConnectEnd = useCallback(
        (event, connectionState) => {
            event.stopPropagation()
            if (!connectionState.isValid) {

                console.log('set active node')
                setActiveNode({
                    ...connectionState.fromNode,
                    handle: connectionState.fromHandle,
                    event
                })
            }
        },
        [],
    );

    const onConnect = useCallback(
        (connection) => {
            const edge = {
                ...connection, type: 'customEdge',
                animated: true,
            }

            onLayout({
                direction: 'RIGHT',
                nodes,
                edges: !edges.find(e => e.sourceHandle === edge.sourceHandle) ? addEdge(edge, edges) : edges
            })
                .then(({ edges, nodes }) => {
                    setEdges(edges)
                    setNodes(nodes)
                })
        },
        [setEdges, nodes],
    );

    const handleSelectNode = item => {
        let targetId = uuid()

        let position = activeNode.fromOrigin ? activeNode.fromOrigin : findNonOverlappingPosition(nodes.map(n => n.position))
        if (activeNode.event) {
            const { clientX, clientY } = 'changedTouches' in activeNode.event ? activeNode.event.changedTouches[0] : activeNode.event
            position = { x: clientX, y: clientY }
        }

        if (item.operator)
            targetId = `${targetId}-operator`

        let newNode = addInformationsToNode({
            ...createSimpleNode([], item),
            id: targetId,
            type: item.type || 'simple',
            position: screenToFlowPosition(position),
        })

        let newEdges = []
        let predicateNode = undefined

        if (activeNode && activeNode.handle) {
            const sourceHandle = activeNode.handle.id

            const parent = nodes.find(node => node.id === activeNode.id)

            // if the node is the first child of a parallel or switch node, we need to insert an intermediate "predicate" node
            if (item.kind !== 'predicate' &&
                parent &&
                (/*parent.data.kind === 'parallel' ||*/ parent.data.kind === 'switch')) {
                predicateNode = addInformationsToNode(createSimpleNode([], {
                    kind: 'predicate',
                }))

                const { targets = [], sources = [] } = predicateNode.data
                predicateNode = {
                    ...predicateNode,
                    data: {
                        ...predicateNode.data,
                        targetHandles: ['input', ...targets].map(target => {
                            return { id: `${target}-${predicateNode.id}` }
                        }),
                        sourceHandles: [...sources].map(source => {
                            return { id: `${source}-${predicateNode.id}` }
                        })
                    }
                }

                edges.push({
                    id: `${activeNode.id}-${predicateNode.id}`,
                    source: activeNode.id,
                    sourceHandle,
                    target: predicateNode.id,
                    targetHandle: `input-${predicateNode.id}`,
                    type: 'customEdge',
                    animated: true,
                })

                newEdges.push({
                    id: uuid(),
                    source: predicateNode.id,
                    sourceHandle: `output-${predicateNode.id}`,
                    target: newNode.id,
                    targetHandle: `input-${newNode.id}`,
                    type: 'customEdge',
                    animated: true,
                })

            } else {
                // If the handle is on the left, we have to reverse the edge direction
                if (activeNode.handle.position === 'left') {
                    newEdges.push({
                        id: uuid(),
                        source: newNode.id,
                        sourceHandle: `output-${newNode.id}`,
                        target: activeNode.id,
                        targetHandle: sourceHandle,
                        type: 'customEdge',
                        animated: true,
                    })
                } else {
                    newEdges.push({
                        id: uuid(),
                        source: activeNode.id,
                        sourceHandle,
                        target: newNode.id,
                        targetHandle: `input-${newNode.id}`,
                        type: 'customEdge',
                        animated: true,
                    })
                }
            }
        }

        const { targets = [], sources = [] } = newNode.data
        newNode = {
            ...newNode,
            data: {
                ...newNode.data,
                targetHandles: ['input', ...targets].map(target => {
                    return { id: `${target}-${newNode.id}` }
                }),
                sourceHandles: [...sources].map(source => {
                    return { id: `${source}-${newNode.id}` }
                })
            }
        }

        console.log(newNode)

        const newNodes = [...nodes, predicateNode, newNode].filter(f => f)
        newEdges = [...edges, ...newEdges]

        setNodes(newNodes)
        setEdges(newEdges)

        console.log('set active node to false 1019')
        setActiveNode(false)
    }

    function run() {
        fetch('/extensions/workflows/_test', {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                input: JSON.stringify({}, null, 4),
                workflow: graphToJson()[0]
            }),
        })
            .then((r) => r.json())
            .then(report => {
                setReport(report)
                setReportStatus(true)
            })
    }

    const closeAllModals = () => {
        setActiveNode(false)
        setReportStatus(false)
        openTagModal(false)
    }

    const handleFlowClick = useCallback(() => {
        if (!activeNode.handle)
            closeAllModals()
    }, [activeNode])

    const handleGroupNodeClick = useCallback(groupNode => {
        setActiveNode(groupNode)
    }, [])

    const manageTags = useCallback(() => {
        closeAllModals(false)
        openTagModal(true)
    }, [])

    const setTags = useCallback(newTags => {
        setWorkflow(workflow => ({
            ...workflow,
            tags: newTags
        }))
    }, [workflow])

    return <div className='workflow'>
        <DesignerActions run={run} />
        <Navbar
            workflow={workflow}
            save={handleSave}
            manageTags={manageTags} />

        <NewTask onClick={() => setActiveNode(true)} />

        <ReportExplorer
            report={report}
            isOpen={reportIsOpen}
            handleClose={() => setReportStatus(false)} />

        <TagsModal
            isOpen={showTagModal}
            tags={workflow}
            setTags={setTags} />

        <NodesExplorer
            activeNode={activeNode}
            handleSelectNode={handleSelectNode} />
        <Flow
            onConnectEnd={onConnectEnd}
            onConnect={onConnect}
            onEdgesChange={onEdgesChange}
            onNodesChange={onNodesChange}
            onClick={handleFlowClick}
            onGroupNodeClick={handleGroupNodeClick}
            nodes={nodes}
            edges={edges} />
    </div>
}