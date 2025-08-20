import React from 'react'

export const SwitchNode = (_workflow) => ({
    label: 'fas fa-exchange-alt',
    name: 'Switch',
    type: 'group',
    workflow: _workflow,
    kind: 'switch',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: () => `${110 + 20 * _workflow?.paths.length}px`,
    targets: []
})