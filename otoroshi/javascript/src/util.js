import { camelCase } from "lodash";

const reservedCamelWords = [
  'isMulti',
  'optionsFrom',
  'createOption',
  'onCreateOption',
  'defaultKeyValue',
  'defaultValue',
  'className',
  'onChange',
  'itemRender',
  'conditionalSchema',
  'visibleOnCollapse',
];

export const REQUEST_STEPS_FLOW = ['MatchRoute', 'PreRoute', 'ValidateAccess', 'TransformRequest']

export const firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

export const camelToSnake = (obj) => {
  return Object.fromEntries(
    Object.entries(obj).map(([key, value]) => {
      const isFlowField = key === 'flow';
      return [
        reservedCamelWords.includes(key) ? key : camelCase(key),
        isFlowField
          ? value.map((step) => camelToSnakeFlow(step))
          : typeof value === 'object' && value !== null && !Array.isArray(value)
            ? camelToSnake(value)
            : value,
      ];
    })
  );
};

export const camelToSnakeFlow = (step) => {
  return typeof step === 'object'
    ? {
      ...step,
      flow: step.flow.map((f) => camelToSnakeFlow(f)),
    }
    : camelCase(step);
};

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