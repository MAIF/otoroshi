export const ValueToCheck = (label = 'Value to Check', singleLine = true) => ({
  type: 'json',
  label: label,
  props: {
    ace_config: {
      maxLines: singleLine ? 1 : Infinity,
      fontSize: 14,
    }
  },
});
