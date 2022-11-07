import React from 'react';
import { NgCustomFormsRenderer } from '../../components/nginputs';
import { JwtVerifierLauncher } from "../wizards/JwtVerifierLauncher";

export default {
  id: "cp:otoroshi.next.plugins.JwtVerification",
  config_schema: {
    verifiers: {
      label: 'Chain of verifiers',
      type: 'array',
      itemRenderer: props => {
        return <NgCustomFormsRenderer
          rawSchema={{
            type: "JwtVerifierWizard",
            props: {
              componentLauncher: JwtVerifierLauncher,
              componentsProps: {
                allowedStrategy: 'PassThrough',
                allowedNewStrategy: 'PassThrough'
              }
            }
          }}
          value={props.value}
          onChange={props.onChange}
        />
      }
    }
  },
  config_flow: [
    "verifiers"
  ]
}