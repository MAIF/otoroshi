export const ValueToCheck = (label = 'Value to Check', singleLine = true) => ({
  type: 'code',
  label: label,
  props: {
    ace_config: {
      maxLines: singleLine ? 1 : Infinity,
      fontSize: 14,
    },
    editorOnly: true,
  },
});
