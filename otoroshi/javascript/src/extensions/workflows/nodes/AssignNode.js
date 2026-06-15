import React from 'react';
import { JsonObjectAsCodeInputUpdatable } from '../../../components/inputs/CodeInput';
import { OperatorSelector } from '../OperatorSelector';

export const AssignNode = {
  kind: 'assign',
  flow: ['values'],
  form_schema: {
    values: {
      type: 'array',
      array: true,
      label: 'Values',
      format: 'form',
      flow: ['name', 'value'],
      schema: {
        name: {
          type: 'string',
          label: 'Name',
        },
        value: {
          renderer: (props) => {
            const predicate = props.value || {};

            return (
              <OperatorSelector
                predicate={predicate}
                handleOperatorChange={(newOperator) => props.onChange(newOperator.predicate)}
              />
            );
            // <JsonObjectAsCodeInputUpdatable
            //   ngOptions={{ spread: true }}
            //   showGutter={false}
            //   ace_config={{
            //     mode: 'json',
            //     onLoad: (editor) => editor.renderer.setPadding(10),
            //     fontSize: 14,
            //   }}
            //   editorOnly
            //   height='10rem'
            //   value={props.value}
            //   onChange={props.onChange}
            // />
          },
        },
      },
    },
  },
  sources: ['output'],
  nodeRenderer: (props) => {
    return (
      <div className="node-text-renderer">
        {props.data.content?.values?.map((value) => {
          return <span key={value.name}>{value.name}</span>;
        })}
      </div>
    );
  },
};
