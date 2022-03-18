const reservedCamelWords = [
    "isMulti", "optionsFrom", "createOption", "onCreateOption",
    "defaultKeyValue", "defaultValue", "className", "onChange", "itemRender", "conditionalSchema"
]

export const camelToSnakeCase = str => str.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`)

export const camelToSnake = obj => {
    return Object.fromEntries(Object.entries(obj).map(([key, value]) => {
        const isFlowField = key === "flow"
        return [
            reservedCamelWords.includes(key) ? key : camelToSnakeCase(key),
            isFlowField ? value.map(step => camelToSnakeFlow(step)) :
                ((typeof value === 'object' && value !== null && !Array.isArray(value)) ? camelToSnake(value) : value)
        ]
    }))
}

export const camelToSnakeFlow = step => {
    return typeof step === 'object' ? {
        ...step,
        flow: step.flow.map(f => camelToSnakeFlow(f))
    } : camelToSnakeCase(step)
}

export const toUpperCaseLabels = obj => {
    return Object.entries(obj).reduce((acc, [key, value]) => {
        const isLabelField = key === "label"
        const v = isLabelField ? value.replace(/_/g, ' ') : value
        const [prefix, ...sequences] = isLabelField ? v.split(/(?=[A-Z])/) : []

        return {
            ...acc,
            [key]: isLabelField ? prefix.charAt(0).toUpperCase() + prefix.slice(1) + " " + sequences.join(" ").toLowerCase() :
                ((typeof value === 'object' && value !== null && key !== "transformer" && !Array.isArray(value)) ? toUpperCaseLabels(value) : value)
        }
    }, {})
}