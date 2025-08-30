import React from 'react';
import { ValueToCheck } from '../operators/ValueToCheck';

export const PredicateNode = {
  label: 'fas fa-filter',
  description: 'Predicate node',
  kind: 'predicate',
  name: 'Predicate',
  type: 'simple',
  sources: ['output'],
  targets: ['PredicateOperator'],
  form_schema: {
    value: ValueToCheck('Predicate', false),
  },
  flow: ['value'],
};
