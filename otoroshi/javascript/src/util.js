export const REQUEST_STEPS_FLOW = ['MatchRoute', 'PreRoute', 'ValidateAccess', 'TransformRequest']

export const firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

export const toUpperCaseLabels = (obj) => {
  return Object.entries(obj).reduce((acc, [key, value]) => {
    const isLabelField = key === 'label';
    const v = isLabelField && value ? value.replace(/_/g, ' ') : value;
    const [prefix, ...sequences] = isLabelField ? (v ? v.split(/(?=[A-Z])/) : []) : [];

    return {
      ...acc,
      [key]: !value
        ? null
        : isLabelField
          ? prefix.charAt(0).toUpperCase() + prefix.slice(1) + ' ' + sequences.join(' ').toLowerCase()
          : typeof value === 'object' &&
            value !== null &&
            key !== 'transformer' &&
            !Array.isArray(value)
            ? toUpperCaseLabels(value)
            : value,
    };
  }, {});
};