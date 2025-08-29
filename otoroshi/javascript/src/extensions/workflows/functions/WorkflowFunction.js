import React from 'react'
import { NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';
import { nodesCatalogSignal } from '../models/Functions';

export const WorkflowFunction = {
    kind: 'core.workflow_call',
    flow: ['workflow_id', 'input'],
    form_schema: {
        workflow_id: {
            renderer: (props) => {
                return <Row title="Workflow">
                    <NgSelectRenderer
                        value={props.value}
                        placeholder="Select an existing workflow"
                        label={' '}
                        ngOptions={{
                            spread: true,
                        }}
                        isClearable
                        onChange={(workflowRef) => {
                            props.onChange(workflowRef)
                        }}
                        components={{
                            Option: (props) => {
                                return (
                                    <div
                                        className="d-flex align-items-center m-0 p-2"
                                        style={{ gap: '.5rem' }}
                                        onClick={() => {
                                            props.selectOption(props.data);
                                        }}
                                    >
                                        <span
                                            className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}
                                        >
                                            {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                                        </span>
                                        {props.data.label}
                                    </div>
                                );
                            },
                            SingleValue: (props) => {
                                return (
                                    <div className="d-flex align-items-center m-0" style={{ gap: '.5rem' }}>
                                        <span
                                            className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}
                                        >
                                            {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                                        </span>
                                        {props.data.label}
                                    </div>
                                );
                            },
                        }}
                        options={[...nodesCatalogSignal.value.workflows, ...Object.values(nodesCatalogSignal.value.workflow.functions) || []]}
                        optionsTransformer={(arr) =>
                            arr.map((item) => ({ label: item.name, value: item.id }))
                        }
                    />
                </Row>
            },
        },
        input: {
            type: 'object',
            label: 'Input'
        }
    }
}