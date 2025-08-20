
import React from 'react'
import { ModalEditor } from './ModalEditor'
import { WhatsNext } from './WhatsNext'

export function NodesExplorer({ activeNode, handleSelectNode, docs }) {

    const isEdition = typeof activeNode === 'object' && !activeNode.handle

    return <div className={`nodes-explorer ${(activeNode && !isEdition) ? 'nodes-explorer--opened' : (activeNode ? 'nodes-explorer--large-opened' : '')}`}>
        {isEdition && <ModalEditor node={activeNode} />}

        {!isEdition && <WhatsNext handleSelectNode={handleSelectNode} isOpen={activeNode} node={activeNode} docs={docs} />}
    </div>
}