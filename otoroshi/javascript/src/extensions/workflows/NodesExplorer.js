
import React from 'react'
import { ModalEditor } from './ModalEditor'
import { WhatsNext } from './WhatsNext'

export function NodesExplorer({ isOpen, isEdition, node }) {

    return <div className={`nodes-explorer ${isOpen ? 'nodes-explorer--opened' : isEdition ? 'nodes-explorer--large-opened' : ''
        }`}>

        {isEdition && <ModalEditor node={node} />}

        {!isEdition && <WhatsNext />}
    </div>
}