import React from 'react'
import { Button } from '../../components/Button'

export function DesignerActions() {

    return <div className='designer-actions'>
        <Button type="primaryColor" className='p-2 px-4'>
            <i className='fas fa-flask me-1' /> Test worfklow
        </Button>
        <Button type="primaryColor">
            <i className='fas fa-trash' />
        </Button>
    </div>
}