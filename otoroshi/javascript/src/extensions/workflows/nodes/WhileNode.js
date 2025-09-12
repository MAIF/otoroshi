import React from 'react'
import { OperatorSelector } from "../OperatorSelector";

export const WhileNode = {
  kind: 'while',
  sources: ['Loop Body', 'output'],
  flow: ['predicate'],
  form_schema: {
    predicate: {
      renderer: (props) => {
        const predicate = props.value || {};

        return <OperatorSelector
          predicate={predicate}
          handleOperatorChange={newOperator => props.onChange(newOperator.predicate)}
        />
      }
    }
  }
};
