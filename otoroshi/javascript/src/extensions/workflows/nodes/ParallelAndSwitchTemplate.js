import React from 'react';
import { Row } from '../../../components/Row';
import { NgForm, NgSelectRenderer } from '../../../components/nginputs';
import { nodesCatalogSignal } from '../models/Functions';
import { OperatorSelector } from '../OperatorSelector';

export const ParallelAndSwitchTemplate = (kind) => {
  return {
    kind,
    type: 'group',
    sourcesIsArray: true,
    handlePrefix: 'path',
    sources: [],
    height: (data) => `${110 + 20 * data?.sourceHandles?.length}px`,
    targets: [],
    flow: ['paths'],
    form_schema: {
      paths: {
        type: 'array',
        label: 'Paths',
        array: true,
        props: {
          disableActions: true,
        },
        format: 'form',
        flow: ['predicate'],
        schema: {
          predicate: {
            renderer: (props) => {
              const predicate = props.value || {};

              return (
                <OperatorSelector
                  predicate={predicate}
                  handleOperatorChange={(newOperator) => props.onChange(newOperator.predicate)}
                />
              );
            },
          },
        },
      },
    },
  };
};
