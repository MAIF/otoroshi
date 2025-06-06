import React from 'react'
import { Node } from './Node'

export function Nodes({
    selectedNode,
    setSelectedNode,
    zoomToElement,
    openNodesExplorer }) {

    return <Node
        openNodesExplorer={openNodesExplorer}
        data={{}}
        onClick={data => {
            setSelectedNode(data)
            // zoomToElement("node", 1.5)
        }}
        isSelected={true}>
        <p>Coucou</p>
    </Node>
}