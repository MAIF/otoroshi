export const FromMemoryFlow = {
    type: 'group',
    name: 'Memory location',
    collapsable: false,
    collapsed: false,
    fields: ['name', 'path'],
    visible: (props) => props?.fromMemory
}

export const FromMemory = ({
    fromMemoryDescription,
    nameLabel,
    pathHelp,
    pathLabel,
    isArray
} = {}) => ({
    fromMemory: {
        type: 'box-bool',
        label: 'Read memory',
        props: {
            description: fromMemoryDescription || (isArray ? 'Is the array from memory?' : 'Is the value from memory?')
        }
    },
    name: {
        type: 'string',
        label: nameLabel || (isArray ? 'Variable name of array' : 'Variable name'),
    },
    path: {
        type: 'string',
        label: pathLabel || (isArray ? 'Child path' : 'Variable path'),
        help: pathHelp || (isArray ? 'Only useful if the array is nested inside an object' : 'Only useful if the variable is an object')
    }
})