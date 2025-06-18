const NODE_SIZE = 200
const NODE_HEIGHT = 100
const PADDING = 20

const DIRECTIONS = [
    { dy: 0, dx: NODE_SIZE + PADDING },
    // { dy: 0, dx: -TOTAL_SIZE },
    { dx: 0, dy: NODE_HEIGHT + PADDING },
    { dx: 0, dy: -NODE_HEIGHT + PADDING }
];

function isOverlapping(x, y, nodes) {
    return nodes.some(node => {
        return x < node.x + NODE_SIZE &&
            x + NODE_SIZE > node.x &&
            y < node.y + NODE_HEIGHT &&
            y + NODE_HEIGHT > node.y
    })
}

function findNearbyPosition(existingNodes) {
    if (existingNodes.length === 0) {
        return { x: 0, y: 0 };
    }

    for (const node of existingNodes) {
        for (const dir of DIRECTIONS) {
            const x = node.x + dir.dx;
            const y = node.y + dir.dy;

            if (!isOverlapping(x, y, existingNodes)) {
                return { x, y };
            }
        }
    }
    return null;
}

export function findNonOverlappingPosition(existingNodes) {
    const nodes = existingNodes.filter(f => f)
    let position = findNearbyPosition(nodes);
    if (position) {
        return position
    }

    // return {
    //     x: nodes[0].x,
    //     y: nodes[0].y + PADDING
    // }
}