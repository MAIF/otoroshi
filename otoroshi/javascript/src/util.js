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

export const useEntityFromURI = () => {
  const location = useLocation();
  return entityFromURI(location);
};

export const entityFromURI = (location) => {
  const { pathname } = location;

  let entity = 'routes';
  try {
    entity = pathname.split('/')[1];
  } catch (_) {}

  const isRouteInstance = entity === 'routes';

  return {
    isRouteInstance,
    capitalizePlural: isRouteInstance ? 'Routes' : 'Route Compositions',
    capitalize: isRouteInstance ? 'Route' : 'Route Composition',
    lowercase: isRouteInstance ? 'route' : 'route composition',
    fetchName: isRouteInstance ? 'ROUTES' : 'SERVICES',
    link: isRouteInstance ? 'routes' : 'route-compositions',
  };
};
