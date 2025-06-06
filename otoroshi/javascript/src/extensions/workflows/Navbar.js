import React from 'react'
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton'

export function Navbar({ workflow, save }) {

    return <div className='workflow-navbar d-flex align-items-center justify-content-between'>
        <div className='d-flex-center gap-3'>
            <p>{workflow.name}</p>
            <button className='add-tag'><i className='fas fa-plus' />Add tag</button>
        </div>

        <FeedbackButton
            type="primaryColor"
            onPress={save}
            text="Save" />
    </div>
}