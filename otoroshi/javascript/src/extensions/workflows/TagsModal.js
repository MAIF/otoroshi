import React from 'react'
import { NgForm } from '../../components/nginputs'

export function TagsModal({ isOpen, tags, setTags }) {

    const schema = {
        tags: {
            type: 'array',
            array: true,
            format: 'string',
            label: 'Tags',
            props: {
                ngOptions: {
                    spread: true,
                }
            }
        }
    }

    const flow = ['tags']

    return <div className={`nodes-explorer ${isOpen ? 'nodes-explorer--opened' : ''}`}>
        <div className="p-3 whats-next-title">
            Manage tags
        </div>
        <div className='p-3 d-flex flex-column'>
            <NgForm
                value={tags}
                onChange={e => setTags(e.tags)}
                flow={flow}
                schema={schema}
            />
        </div>
    </div>
}