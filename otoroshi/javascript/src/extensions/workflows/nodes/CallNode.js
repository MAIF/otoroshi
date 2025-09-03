import React from 'react';
import { NgCodeRenderer, NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';
import { nodesCatalogSignal } from '../models/Functions';

export const CallNode = {
  kind: 'call',
  flow: ['function', 'args'],
  form_schema: {
    function: {
      renderer: (props) => {
        const functions = Object.values(nodesCatalogSignal.value.nodes).filter(node => node.category === 'functions')
        return (
          <Row title="Select a function to execute">
            <NgSelectRenderer
              options={functions.map((func) => ({
                label: func.display_name,
                value: func.name,
              }))}
              ngOptions={{ spread: true }}
              value={props.value}
              onChange={props.onChange}
            />
          </Row>
        );
      },
    },
    args: {
      renderer: (props) => {
        return (
          <Row title="Arguments">
            <NgCodeRenderer
              ngOptions={{ spread: true }}
              rawSchema={{
                props: {
                  showGutter: false,
                  ace_config: {
                    onLoad: (editor) => editor.renderer.setPadding(10),
                    fontSize: 14,
                  },
                  editorOnly: true,
                  height: '10rem',
                  mode: 'json',
                },
              }}
              value={props.value}
              onChange={(e) => {
                props.onChange(JSON.parse(e));
              }}
            />
          </Row>
        );
      },
    },
  },
  sources: ['output'],
  // nodeRenderer: props => {
  //     return <div className='assign-node'>
  //         <span >{props.data.content?.function}</span>
  //     </div>
  // }
};
