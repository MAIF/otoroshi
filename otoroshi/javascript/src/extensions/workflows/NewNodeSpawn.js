const NODE_SIZE = 100;
const PADDING = 150;
const TOTAL_SIZE = NODE_SIZE + PADDING;
const CANVAS_WIDTH = window.innerWidth;
const CANVAS_HEIGHT = window.innerHeight;
const MAX_RANDOM_ATTEMPTS = 100;

const DIRECTIONS = [
    // { dx: 0, dy: TOTAL_SIZE },
    // { dx: 0, dy: -TOTAL_SIZE }

    { dy: 0, dx: TOTAL_SIZE },
    { dy: 0, dx: -TOTAL_SIZE }
];

function isOverlapping(x, y, nodes) {
    return nodes.some(node => {
        const dx = Math.abs(x - node.x);
        const dy = Math.abs(y - node.y);
        return dx < TOTAL_SIZE && dy < TOTAL_SIZE;
    })
}

export function findRandomPosition(existingNodes) {
    for (let i = 0; i < MAX_RANDOM_ATTEMPTS; i++) {
        const x = Math.random() * (CANVAS_WIDTH - NODE_SIZE);
        const y = Math.random() * (CANVAS_HEIGHT - NODE_SIZE);

        if (!isOverlapping(x, y, existingNodes)) {
            return { x, y }
        }
    }
    return null;
}

function findNearbyPosition(existingNodes) {
    if (existingNodes.length === 0) {
        return { x: 0, y: 0 };
    }

    for (const node of existingNodes) {
        for (const dir of DIRECTIONS) {
            const x = node.x + dir.dx;
            const y = node.y + dir.dy;

            if (x >= 0 && y >= 0 && x + NODE_SIZE <= CANVAS_WIDTH && y + NODE_SIZE <= CANVAS_HEIGHT) {
                if (!isOverlapping(x, y, existingNodes)) {
                    return { x, y };
                }
            }
        }
    }
    return null;
}

export function findNonOverlappingPosition(existingNodes) {
    let position = findNearbyPosition(existingNodes);
    if (position) {
        return position
    }

    position = findRandomPosition(existingNodes);
    if (position) {
        return position
    }

    return {
        x: existingNodes[0].position.x,
        y: existingNodes[0].position.y + PADDING
    }
}