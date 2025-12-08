import React from 'react';
import { NgCustomFormsRenderer } from '../../components/nginputs';
import { JwtVerifierLauncher } from '../wizards/JwtVerifierLauncher';

export default {
  id: 'cp:otoroshi.next.plugins.JwtVerification',
  config_schema: {
    custom_response: {
      type: 'bool',
      label: 'Custom error',
    },
    custom_response_status: {
      type: 'number',
      label: 'Custom error status',
    },
    custom_response_headers: {
      type: 'object',
      label: 'Custom error headers',
    },
    custom_response_body: {
      type: 'code',
      label: 'Custom error body',
    },
    verifiers: {
      label: 'Chain of verifiers',
      type: 'array',
      itemRenderer: (props) => {
        return (
          <NgCustomFormsRenderer
            rawSchema={{
              type: 'JwtVerifierWizard',
              props: {
                componentLauncher: JwtVerifierLauncher,
                componentsProps: {
                  // allowedStrategy: 'PassThrough',
                  // allowedNewStrategy: 'PassThrough',
                },
              },
            }}
            value={props.value}
            onChange={props.onChange}
          />
        );
      },
    },
  },
  config_flow: [
    'verifiers',
    'custom_response',
    'custom_response_status',
    'custom_response_headers',
    'custom_response_body',
  ],
};
