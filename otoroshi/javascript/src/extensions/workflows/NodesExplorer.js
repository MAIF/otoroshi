
import React from 'react'
import { ModalEditor } from './ModalEditor'
import { WhatsNext } from './WhatsNext'

export function NodesExplorer({ isOpen, isEdition, node, handleSelectNode }) {

    return <div className={`nodes-explorer ${isOpen ? 'nodes-explorer--opened' : isEdition ? 'nodes-explorer--large-opened' : ''
        }`}>

        {isEdition && <ModalEditor node={node} />}

        {!isEdition && <WhatsNext handleSelectNode={handleSelectNode} isOpen={isOpen} node={node}/>}
    </div>
}