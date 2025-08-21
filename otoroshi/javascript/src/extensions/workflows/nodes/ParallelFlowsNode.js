export const ParallelFlowsNode = {
    type: 'group',
    kind: 'parallel',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: (data) => `${110 + 20 * data?.sourceHandles?.length}px`,
    targets: []
}