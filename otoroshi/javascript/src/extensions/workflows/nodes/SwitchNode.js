export const SwitchNode = {
    type: 'group',
    kind: 'switch',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: (data) => `${110 + 20 * data?.paths.length}px`,
    targets: []
}