import React from 'react';
import { ValueToCheck } from './ValueToCheck';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ProjectionOperator = _workflow => ({
    label: <i className="fas fa-filter" />,
    name: 'Projection',
    kind: '$projection',
    description: 'Projects a value',
    workflow: _workflow,
    flow: ['projection', 'fromMemory', 'value', FromMemoryFlow],
    schema: {
        ...FromMemory(),
        projection: ValueToCheck('Projection'),
        value: ValueToCheck("Value to project"),
    },
    sources: ['output']
});

// Less Than or Equal Operator;