import React from 'react'
import { Button } from '../../components/Button'

export function DesignerActions({ run }) {

    return <div className='designer-actions'>
        <Button type="primaryColor" className='p-2 px-4' onClick={run}>
            <i className='fas fa-flask me-1' />Test Workflow
        </Button>
        <Button type="primaryColor">
            <i className='fas fa-trash' />
        </Button>
    </div>
}