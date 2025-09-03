import React from 'react';
import { NgCodeRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';

export const ReturnedNode = {
  label: 'fas fa-box',
  name: 'Returned',
  kind: 'returned',
  description: ' Overrides the output of the node with the result of an operator',
  flow: ['returned'],
  sources: ['output'],
  form_schema: {
    returned: {
      renderer: (props) => {
        let value = props.value;

        if (typeof value === 'object') {
          delete value.position;
          delete value.description;
        }

        return (
          <Row title="Returned operator (optional)">
            <NgCodeRenderer
              ngOptions={{ spread: true }}
              rawSchema={{
                props: {
                  showGutter: false,
                  ace_config: {
                    mode: 'json',
                    onLoad: (editor) => editor.renderer.setPadding(10),
                    fontSize: 14,
                  },
                  editorOnly: true,
                  height: '10rem',
                },
              }}
              value={value}
              onChange={(e) => {
                try {
                  props.onChange(JSON.parse(e));
                } catch (_) {}
              }}
            />
          </Row>
        );
      },
    },
  },
  nodeRenderer: (props) => {
    const { position, description, ...rest } = props.data?.content?.returned;

    return (
      <div
        style={{
          position: 'absolute',
          top: 30,
          left: 24,
          right: 0,
          bottom: 0,
          borderBottomRightRadius: '.75rem',
          overflow: 'hidden',
        }}
      >
        <NgCodeRenderer
          ngOptions={{ spread: true }}
          rawSchema={{
            props: {
              showGutter: false,
              ace_config: {
                fontSize: 8,
                readOnly: true,
              },
              editorOnly: true,
              height: '100%',
              mode: 'json',
            },
          }}
          value={rest}
        />
      </div>
    );
  },
};
