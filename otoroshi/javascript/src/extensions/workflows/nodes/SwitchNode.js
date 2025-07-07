import React from 'react'

export const SwitchNode = (_workflow) => ({
    label: <i className='fas fa-exchange-alt' />,
    name: 'Switch',
    description: 'Route items depending on defined expressions or rules',
    type: 'group',
    workflow: _workflow,
    kind: 'switch',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: () => `${110 + 20 * _workflow?.paths.length}px`,
    targets: []
})