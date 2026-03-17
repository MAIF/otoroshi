import React from 'react';
import { OperatorSelector } from '../OperatorSelector';

export const WhileNode = {
  kind: 'while',
  sources: ['Loop Body', 'output'],
  flow: ['max_budget', 'predicate'],
  form_schema: {
    max_budget: {
      type: 'number',
      label: 'Max loops',
      props: {
        description: 'The maximum number of times this node can execute',
      },
    },
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
};
