import React from 'react';
import { NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';
import { nodesCatalogSignal } from '../models/Functions';

export const WorkflowFunction = {
  kind: 'core.workflow_call',
  flow: ['workflow_id', 'input'],
  form_schema: {
    workflow_id: {
      renderer: (props) => {
        console.log('value', props.value)

        const options = [
          ...(Object.values(nodesCatalogSignal.value.workflow.functions)
            .map(func => ({ ...func, self: true })) || []),
          ...nodesCatalogSignal.value.workflows
        ]

        return (
          <Row title="Workflow">
            <NgSelectRenderer
              value={options.find(option => option.id === props.value)}
              placeholder="Select an existing workflow"
              label={' '}
              ngOptions={{
                spread: true,
              }}
              isClearable
              onChange={(workflowRef) => {
                props.onChange(workflowRef.id);
              }}
              components={{
                Option: (props) => {
                  return (
                    <div
                      className="d-flex align-items-center m-0 p-2"
                      style={{ gap: '.5rem' }}
                      onClick={() => {
                        props.selectOption(props.data.value);
                      }}
                    >
                      <span className={`badge ${props.data.value.self ? 'bg-warning' : 'bg-success'}`}>
                        {props.data.value.self ? 'LOCAL' : 'GLOBAL'}
                      </span>
                      {props.data.label}
                    </div>
                  );
                },
                SingleValue: (props) => {
                  return (
                    <div className="d-flex align-items-center m-0" style={{ gap: '.5rem' }}>
                      <span
                        className={`badge ${props.data.value.self ? 'bg-warning' : 'bg-success'}`}
                      >
                        {props.data.value.self ? 'LOCAL' : 'GLOBAL'}
                      </span>
                      {props.data.label}
                    </div>
                  );
                },
              }}
              options={options}
              optionsTransformer={(arr) =>
                arr.map((item) => ({ label: item.name, value: item }))
              }
            />
          </Row>
        );
      },
    },
    input: {
      type: 'object',
      label: 'Input',
    },
  },
};
