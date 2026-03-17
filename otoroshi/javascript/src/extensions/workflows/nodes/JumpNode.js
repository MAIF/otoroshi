import React from 'react';

import { OperatorSelector } from '../OperatorSelector';

export const JumpNode = {
  type: 'group',
  kind: 'jump',
  sources: ['to', 'output'],
  flow: ['predicate'],
  form_schema: {
    predicate: {
      renderer: (props) => {
        const { predicate } = props.rootValue;

        return (
          <OperatorSelector
            predicate={predicate}
            handleOperatorChange={(newOperator) =>
              props.rootOnChange({
                ...props.rootValue,
                ...newOperator,
              })
            }
          />
        );
      },
    },
  },
};
