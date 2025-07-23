import ELK from 'elkjs/lib/elk.bundled.js';

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
}

const elk = new ELK();

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
        .catch(err => {
            console.log(err)
            return { edges: [], nodes: [] }
        });
};

export const onLayout = ({ direction, nodes, edges, setNodes, setEdges }) => {
    const opts = {
        'elk.direction': direction,
        ...elkOptions
    }

    const filteredEdges = edges.filter(f => f.target && f.source)

    if (edges.length > filteredEdges.length)
        console.error("got an edge with an empty source or an empty target")

    getLayoutedElements(nodes, filteredEdges, opts)
        .then(({ nodes: layoutedNodes, edges: layoutedEdges }) => {
            setNodes(layoutedNodes)
            setEdges(layoutedEdges)
            // fitView({
            //     padding: 2
            // });
        })
}