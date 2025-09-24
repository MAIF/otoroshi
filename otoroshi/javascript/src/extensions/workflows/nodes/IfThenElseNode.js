import React from 'react';

import { OperatorSelector } from '../OperatorSelector';

export const IfThenElseNode = {
  type: 'group',
  kind: 'if',
  sources: ['then', 'else'],
  flow: ['predicate'],
  form_schema: {
    predicate: {
      renderer: (props) => {
        const { predicate } = props.rootValue

        return <OperatorSelector
          predicate={predicate}
          handleOperatorChange={newOperator => props.rootOnChange({
            ...props.rootValue,
            ...newOperator
          })}
        />
      }
    }
  }
}
