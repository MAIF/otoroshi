import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from "react-query"
import { useParams } from "react-router-dom";
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Flow } from './Flow'
import { DesignerActions } from './DesignerActions'
import { Navbar } from './Navbar'
import { NodesExplorer } from './NodesExplorer'
import Loader from '../../components/Loader';
import { v4 as uuid } from 'uuid';
import ELK from 'elkjs/lib/elk.bundled.js';

import {
    applyNodeChanges,
    applyEdgeChanges,
    addEdge,
    ReactFlowProvider,
    useReactFlow,
    useUpdateNodeInternals,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { findNonOverlappingPosition } from './NewNodeSpawn';
import { NODES, OPERATORS } from './models/Functions';
import ReportExplorer from './ReportExplorer';

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            retry: false,
            refetchOnWindowFocus: false,
        },
    },
});

const elk = new ELK();

const elkOptions = {
    'elk.algorithm': 'layered',
    'elk.layered.spacing.nodeNodeBetweenLayers': '120',
    'elk.spacing.nodeNode': '150',

    'elk.layered.edgeRouting.selfLoopDistribution': 'EQUALLY',
    'elk.layered.edgeRouting.selfLoopOrdering': 'SEQUENCED',
    'elk.layered.edgeRouting.splines.mode': 'ORTHOGONAL',
    'elk.layered.edgeRouting.polyline.slanted': 'false',
    'elk.layered.edgeRouting.orthogonal.nodesOnEdge': 'true',

    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.crossingMinimization.greedySwitch.type': 'TWO_SIDED',

    'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
    'elk.layered.nodePlacement.favorStraightEdges': 'true',
    'elk.layered.nodePlacement.linearSegments.deflectionDampening': '0.3',

    'elk.spacing.edgeNode': '80',
    'elk.spacing.edgeEdge': '40',
    'elk.layered.spacing.edgeNodeBetweenLayers': '50',
    'elk.layered.spacing.edgeEdgeBetweenLayers': '35',


    'elk.portConstraints': 'FIXED_ORDER',
    'elk.layered.unnecessaryBendpoints': 'false',
    'elk.layered.mergeEdges': 'false',

    'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    'elk.layered.considerModelOrder.longEdgeStrategy': 'DUMMY_NODE_OVER',
    'elk.layered.considerModelOrder.crossingCounterNodeInfluence': '0.05',

    'elk.layered.cycleBreaking.strategy': 'GREEDY',
    'elk.layered.layering.strategy': 'LONGEST_PATH',

    'elk.spacing.componentComponent': '60',
    'elk.spacing.portPort': '25',
    'elk.spacing.portsSurrounding': '[top=25,left=25,bottom=25,right=25]',

    'elk.layered.edgeRouting.orthogonal.searchHeuristic': 'MANHATTAN',
    'elk.layered.thoroughness': '10',
    'elk.layered.compaction.postCompaction.strategy': 'EDGE_LENGTH'
};

const applyStyles = () => {
    const pageContainer = document.getElementById('content-scroll-container');
    const parentPageContainer = document.getElementById('content-scroll-container-parent');

    const pagePadding = pageContainer.style.paddingBottom
    pageContainer.style.paddingBottom = 0

    const parentPadding = parentPageContainer.style.padding
    parentPageContainer.style.setProperty('padding', '0px', 'important')

    return () => {
        pageContainer.style.paddingBottom = pagePadding
        parentPageContainer.style.padding = parentPadding
    }
}

export function QueryContainer(props) {
    useEffect(() => {
        props.setTitle(undefined)
        return applyStyles()
    }, [])

    return <QueryClientProvider client={queryClient}>
        <Container {...props} />
    </QueryClientProvider>
}

function Container(props) {
    const params = useParams()

    const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    const workflow = useQuery(
        ['getWorkflow', params.workflowId],
        () => client.findById(params.workflowId));

    return <Loader loading={workflow.isLoading}>
        <ReactFlowProvider>
            <WorkflowsDesigner {...props} workflow={workflow.data} />
        </ReactFlowProvider>
    </Loader>
}

export function createSimpleNode(nodes, node) {
    // console.log('createSimpleNode', node.kind || node.data?.kind, node)

    let data = NODES[(node.kind || node.data.kind).toLowerCase()]

    if (data)
        data = data("workflow" in node ? node.workflow : node)

    if (!data) {
        data = OPERATORS[(node.kind || node.data.kind).toLowerCase()](node)
    }

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

            const child = buildGraph(workflow.steps.reverse(), addInformationsToNode, returnedNode.id)
            nodes = [...child.nodes, returnedNode]

            edges = edges.concat(child.edges)
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
            const child = buildGraph(workflow.steps.reverse(), addInformationsToNode, targetId, handleId)
            nodes = [...child.nodes]

            edges = edges.concat(child.edges)
        }

    } else if (workflow.kind === "if") {
        const thensubGraph = buildGraph([workflow.then], addInformationsToNode, targetId, handleId)
        const elseGraph = buildGraph([workflow.else], addInformationsToNode, targetId, handleId)

        let predicate = workflow.predicate
        if (typeof workflow.predicate === "object" && workflow.predicate !== null && Object.keys(workflow.predicate).find(key => key.startsWith('$'))) {
            const kind = Object.keys(workflow.predicate).find(key => key.startsWith('$'))
            predicate = {
                kind,
                ...workflow.predicate[kind]
            }
        }

        predicate = buildGraph([predicate], addInformationsToNode)

        nodes = nodes.concat(thensubGraph.nodes)
        edges = edges.concat(thensubGraph.edges)

        nodes = nodes.concat(elseGraph.nodes)
        edges = edges.concat(elseGraph.edges)

        nodes = nodes.concat(predicate.nodes)
        edges = edges.concat(predicate.edges)

        edges.push({
            id: `${me}-then`,
            source: me,
            sourceHandle: `then-${me}`,
            target: thensubGraph.nodes[0].id,
            targetHandle: `input-${thensubGraph.nodes[0].id}`,
            type: 'customEdge',
            animated: true,
        })
        edges.push({
            id: `${me}-else`,
            source: me,
            sourceHandle: `else-${me}`,
            target: elseGraph.nodes[0].id,
            targetHandle: `input-${elseGraph.nodes[0].id}`,
            type: 'customEdge',
            animated: true,
        })
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
    } else if (workflow.kind === 'switch' || workflow.kind === 'parallel') {
        let paths = []

        for (let i = 0; i < workflow.paths.length; i++) {
            const subflow = workflow.paths[i]
            const nestedPath = buildGraph([subflow], addInformationsToNode, targetId, handleId)
            paths.push(nestedPath)
        }

        current.customSourceHandles = [...Array(workflow.paths.length)].map((_, i) => ({ id: `path-${i}` }))

        paths.forEach((path, idx) => {
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
    } else {
        if (workflow.predicate !== undefined) {
            // sub path of switch group

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
    const subGraph = buildGraph(config.steps.reverse(), addInformationsToNode, returnedNode.id)

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

function WorkflowsDesigner(props) {
    const [selectedNode, setSelectedNode] = useState()
    const [isOnCreation, setOnCreationMode] = useState(false)

    const [rfInstance, setRfInstance] = useState(null);

    const initialState = initializeGraph(props.workflow?.config, props.workflow.orphans, addInformationsToNode)

    const [nodes, internalSetNodes] = useState(initialState.nodes)
    const [edges, setEdges] = useState(initialState.edges)

    const [report, setReport] = useState()
    const [reportIsOpen, setReportStatus] = useState(false)

    useEffect(() => {
        // FIX ??
        // window.addEventListener('error', e => {
        //     if (e.message === 'ResizeObserver loop completed with undelivered notifications.') {
        //         const resizeObserverErrDiv = document.getElementById('webpack-dev-server-client-overlay-div');
        //         const resizeObserverErr = document.getElementById('webpack-dev-server-client-overlay');
        //         if (resizeObserverErr) {
        //             resizeObserverErr.setAttribute('style', 'display: none');
        //         }
        //         if (resizeObserverErrDiv) {
        //             resizeObserverErrDiv.setAttribute('style', 'display: none');
        //         }
        //     }
        // });
    }, [])

    const { fitView, screenToFlowPosition } = useReactFlow();
    const updateNodeInternals = useUpdateNodeInternals()

    const getLayoutedElements = (nodes, edges, options = {}) => {
        const isHorizontal = options?.['elk.direction'] === 'RIGHT';
        const graph = {
            id: 'root',
            layoutOptions: options,
            children: nodes.map((n) => {
                const targetPorts = n.data.targetHandles.map((t) => ({
                    id: t.id,
                    properties: {
                        side: 'WEST',
                    },
                }));

                const sourcePorts = n.data.sourceHandles.map((s) => ({
                    id: s.id,
                    properties: {
                        side: 'EAST',
                    },
                }));

                return {
                    ...n,
                    properties: {
                        'org.eclipse.elk.portConstraints': 'FREE',
                    },
                    ports: [{ id: n.id }, ...targetPorts, ...sourcePorts],

                    targetPosition: isHorizontal ? 'left' : 'top',
                    sourcePosition: isHorizontal ? 'right' : 'bottom',

                    width: 200,
                    height: 100,
                }
            }),
            edges: edges,
        };

        return elk
            .layout(graph)
            .then((layoutedGraph) => ({
                nodes: layoutedGraph.children.map((node) => ({
                    ...node,
                    // React Flow expects a position property on the node instead of `x`
                    // and `y` fields.
                    position: { x: node.x, y: node.y },
                })),

                edges: layoutedGraph.edges,
            }))
            .catch(console.error);
    };

    const onLayout = ({ direction, nodes, edges, from }) => {
        const opts = {
            'elk.direction': direction,
            ...elkOptions
        };

        getLayoutedElements(nodes, edges, opts)
            .then(({ nodes: layoutedNodes, edges: layoutedEdges }) => {
                setNodes(layoutedNodes)
                setEdges(layoutedEdges)
                // fitView({
                //     padding: 2
                // });
            });
    };

    useLayoutEffect(() => {
        onLayout({ direction: 'RIGHT', nodes, edges, from: 'useLayoutEffect' });
    }, []);

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

    const emptyWorkflow = ({
        kind: 'workflow',
        steps: [],
    })

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
                id: node.id
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
                predicateNode = res[0]
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
                const [node, seen] = removeReturnedFromWorkflow(nodeToJson(nodes.find(n => n.id === nodeLoop.target), emptyWorkflow, false, alreadySeen))
                alreadySeen = alreadySeen.concat([seen])
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
                alreadySeen = alreadySeen.concat([seen])
                return {
                    ...acc,
                    paths: [...acc.paths, pathNode]
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

                console.log(nestedFlow)

                if (steps.length > 1)
                    steps[1].predicate = nestedFlow.steps[0] // assign the first node, which the predicate node

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

        if (outputWorkflow && !isStart && outputWorkflow.kind === 'workflow' && outputWorkflow.steps.length === 1)
            return removeReturnedFromWorkflow([outputWorkflow.steps[0], alreadySeen])

        if (isStart)
            return [outputWorkflow, alreadySeen]

        return removeReturnedFromWorkflow([outputWorkflow, alreadySeen])
    }

    const removeReturnedFromWorkflow = output => {
        if (output[0].kind === 'workflow') {
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
            ...props.workflow,
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
                    onDoubleClick: setSelectedNode,
                    openNodesExplorer: setOnCreationMode,
                    handleDeleteNode: handleDeleteNode,
                    updateData: updateData,
                    addHandleSource: addHandleSource,
                    handleWorkflowChange: handleWorkflowChange,
                    deleteHandle: deleteHandle
                }
            },
        }
    }

    function handleWorkflowChange(nodeId, workflow) {
        console.log(workflow)
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

    function addHandleSource(nodeId, handlePrefix) {
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

    function handleDeleteNode(nodeId) {
        setNodes((nds) => nds.filter((node) => node.id !== nodeId));
        setEdges((eds) => eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
    }

    const setNodes = nodes => internalSetNodes(nodes)

    const onNodesChange = useCallback(
        (changes) => {
            return setNodes(eds => applyNodeChanges(changes, eds))
        }, [])
    const onEdgesChange = useCallback(
        (changes) => setEdges((eds) => applyEdgeChanges(changes, eds)),
        [],
    )

    const onConnectEnd = useCallback(
        (event, connectionState) => {
            if (!connectionState.isValid) {
                event.stopPropagation()

                setTimeout(() => {
                    setOnCreationMode({
                        ...connectionState.fromNode,
                        handle: connectionState.fromHandle,
                        event
                    })
                }, 2)
            }
        },
        [rfInstance],
    );

    const onConnect = useCallback(
        (connection) => {
            const edge = {
                ...connection, type: 'customEdge',
                animated: true,
            }

            setEdges(edges => {
                const newEdges = !edges.find(e => e.sourceHandle === edge.sourceHandle) ? addEdge(edge, edges) : edges

                onLayout({ direction: 'RIGHT', nodes, edges: newEdges, from: 'onConnect' })

                return newEdges
            })

        },
        [setEdges, nodes],
    );

    const handleSelectNode = item => {
        let targetId = uuid()

        let position = isOnCreation.fromOrigin ? isOnCreation.fromOrigin : findNonOverlappingPosition(nodes.map(n => n.position))
        if (isOnCreation.event) {
            const { clientX, clientY } = 'changedTouches' in isOnCreation.event ? isOnCreation.event.changedTouches[0] : isOnCreation.event
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

        if (isOnCreation && isOnCreation.handle) {
            const sourceHandle = isOnCreation.handle.id

            newEdges.push({
                id: uuid(),
                source: isOnCreation.id,
                sourceHandle,
                target: newNode.id,
                targetHandle: `input-${newNode.id}`,
                type: 'customEdge',
                animated: true,
            })

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

        const newNodes = [...nodes, newNode]
        newEdges = [...edges, ...newEdges]

        setNodes(newNodes)
        setEdges(newEdges)

        setOnCreationMode(false)
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
                workflow: graphToJson()
            }),
        })
            .then((r) => r.json())
            .then(report => {
                setReport(report)
                setReportStatus(true)
            })
    }

    return <div className='workflow'>
        <DesignerActions run={run} />
        <Navbar workflow={props.workflow} save={handleSave} />

        <NewTask onClick={() => setOnCreationMode(true)} />

        <ReportExplorer report={report} isOpen={reportIsOpen} handleClose={() => setReportStatus(false)} />

        <NodesExplorer
            isOpen={isOnCreation}
            isEdition={selectedNode}
            node={selectedNode}
            handleSelectNode={handleSelectNode} />
        <Flow
            setRfInstance={setRfInstance}
            onConnectEnd={onConnectEnd}
            onConnect={onConnect}
            onEdgesChange={onEdgesChange}
            onNodesChange={onNodesChange}
            onClick={() => {
                setOnCreationMode(false)
                setSelectedNode(false)
                setReportStatus(false)
            }}
            onGroupNodeClick={groupNode => {
                setOnCreationMode(groupNode)
                setSelectedNode(groupNode)
            }}
            nodes={nodes}
            edges={edges}>
        </Flow>
    </div>
}