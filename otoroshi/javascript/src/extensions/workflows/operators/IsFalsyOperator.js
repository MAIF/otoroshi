export const IsFalsyOperator = {
  kind: '$is_falsy',
  flow: ['fromMemory', 'value', 'name', 'path'],
  form_schema: {
    fromMemory: {
      type: 'box-bool',
      label: 'Read memory',
      props: {
        description: 'Is the value from memory?',
      },
    },
    value: {
      type: 'json',
      label: 'Value to check',
      visible: (props) => !props?.fromMemory,
    },
    name: {
      type: 'string',
      label: 'Variable name',
    },
    path: {
      type: 'string',
      label: 'Variable path',
      help: 'Only useful if the variable is an object',
    },
  },
  sources: ['output'],
  operators: true,
};
