export const ValueToCheck = (label = 'Value to Check') => ({
  type: 'any',
  label: label,
  props: {
    mode: 'jsonOrPlaintext',
    height: '120px',
  },
});
