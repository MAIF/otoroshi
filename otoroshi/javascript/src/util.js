import React from 'react';
import { useLocation } from 'react-router-dom';

export const REQUEST_STEPS_FLOW = ['MatchRoute', 'PreRoute', 'ValidateAccess', 'TransformRequest'];

export const firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

export const toUpperCaseLabels = (obj) => {
  return Object.entries(obj).reduce((acc, [key, value]) => {
    const isLabelField = key === 'label';
    const v = isLabelField && value ? value.replace(/_/g, ' ') : value;

    return {
      ...acc,
      [key]: !value
        ? null
        : isLabelField
          ? v.charAt(0).toUpperCase() + v.slice(1)
          : typeof value === 'object' &&
              value !== null &&
              key !== 'transformer' &&
              key !== 'optionsTransformer' &&
              !Array.isArray(value)
            ? toUpperCaseLabels(value)
            : value,
    };
  }, {});
};

export function useQuery() {
  const { search } = useLocation();
  return React.useMemo(() => new URLSearchParams(search), [search]);
}

//     isRouteInstance,
//     capitalizePlural: 'Routes',
//     capitalize: 'Route',
//     lowercase: 'route',
//     fetchName: 'ROUTES',
//     link: 'routes',

export const humanMillisecond = function (ms, digits = 1) {
  const levels = [
    ['ms', 1000],
    ['sec', 60],
    ['min', 60],
    ['hrs', 24],
    ['days', 7],
    ['weeks', 30 / 7],
    ['months', 12.1666666666666666],
    ['years', 10],
    ['decades', 10],
    ['centuries', 10],
    ['millenia', 10],
  ];
  var value = ms;
  var name = '';
  var step = 1;
  for (var i = 0, max = levels.length; i < max; ++i) {
    value /= step;
    name = levels[i][0];
    step = levels[i][1];
    if (value < step) {
      break;
    }
  }
  return value.toFixed(digits) + ' ' + name;
};

const BYTE_UNIT_POWERS = {
  B: 0,
  KB: 10,
  MB: 20,
  GB: 30,
  TB: 40,
  PB: 50,
  EB: 60,
  ZB: 70,
  YB: 80,
};

export const converterBase2 = (value, originalUnit, targetUnit) =>
  value * Math.pow(2, BYTE_UNIT_POWERS[originalUnit] - BYTE_UNIT_POWERS[targetUnit]);

export const classNames = (...args) => args.filter(Boolean).join(' ');

const ALPHANUMERIC = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

export const randomAlphaNumeric = (length) => {
  const values = new Uint32Array(length);
  window.crypto.getRandomValues(values);
  return Array.from(values, (value) => ALPHANUMERIC[value % ALPHANUMERIC.length]).join('');
};

const FIRST_NAMES = [
  'Alex',
  'Camille',
  'Dominique',
  'Eliott',
  'Farah',
  'Gabriel',
  'Hana',
  'Ines',
  'Jules',
  'Karim',
  'Lena',
  'Marius',
  'Nadia',
  'Oscar',
  'Paul',
  'Sofia',
];
const LAST_NAMES = [
  'Bernard',
  'Chevalier',
  'Dubois',
  'Fontaine',
  'Girard',
  'Henry',
  'Lambert',
  'Martin',
  'Moreau',
  'Perrin',
  'Robin',
  'Roussel',
  'Simon',
  'Vincent',
];
const LOREM = ['aperture', 'binary', 'cluster', 'gateway', 'lattice', 'proxy', 'route', 'signal'];

const pick = (values) => values[Math.floor(Math.random() * values.length)];

export const randomFirstName = () => pick(FIRST_NAMES);
export const randomLastName = () => pick(LAST_NAMES);
export const randomWords = () => [pick(LOREM), pick(LOREM), pick(LOREM)].join(' ');

export const unsecuredCopyToClipboard = (text) => {
  const textArea = document.createElement('textarea');
  textArea.value = text;
  document.body.appendChild(textArea);
  textArea.focus();
  textArea.select();
  try {
    document.execCommand('copy');
  } catch (err) {
    console.error('Unable to copy to clipboard', err);
  }
  document.body.removeChild(textArea);
};
