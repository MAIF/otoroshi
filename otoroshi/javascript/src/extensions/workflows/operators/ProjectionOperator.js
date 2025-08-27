import React from 'react';
import { ValueToCheck } from './ValueToCheck';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ProjectionOperator = {
    kind: '$projection',
    flow: ['projection', 'fromMemory', 'value', FromMemoryFlow],
    form_schema: {
        ...FromMemory(),
        projection: ValueToCheck('Projection'),
        value: ValueToCheck("Value to project"),
    },
    sources: ['output']
}