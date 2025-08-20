import React from 'react'

export const ParallelFlowsNode = (_workflow) => ({
    label: 'fas fa-code-branch',
    name: 'Parallel',
    type: 'group',
    workflow: _workflow,
    kind: 'parallel',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: () => `${110 + 20 * _workflow?.paths?.length}px`,
    targets: []
})