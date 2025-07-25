import React from 'react'
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton'
import { Link } from 'react-router-dom'

export function Navbar({ workflow, save, manageTags }) {

    return <div className='workflow-navbar d-flex align-items-center justify-content-between'>
        <div className='d-flex-center gap-3'>
            <Link
                className='m-0 cursor-pointer'
                to={`/extensions/workflows/workflows/edit/${workflow.id}`}>{workflow.name}</Link>
            <button className='add-tag'
                onClick={manageTags}>
                {workflow.tags
                    .filter(tag => tag.length > 0)
                    .map(tag => <span className="tag" key={tag}>
                        {tag}
                    </span>)}
                <i className='fas fa-plus' />Add tag</button>
        </div>

        <FeedbackButton
            type="primaryColor"
            onPress={save}
            text="Save" />
    </div>
}