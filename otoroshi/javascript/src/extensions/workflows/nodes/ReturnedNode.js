import React from 'react';
import { NgAnyRenderer, NgCodeRenderer, NgJsonRenderer } from '../../../components/nginputs';
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
            <NgAnyRenderer
              ngOptions={{ spread: true }}
              value={value}
              onChange={e => {
                console.log('changed', e)
                props.onChange(e)
              }}
            />
          </Row>
        );
      },
    },
  },
  nodeRenderer: (props) => {
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
        className='d-flex'>
        <NgAnyRenderer
          ngOptions={{ spread: true }}
          options={{
            fontSize: 8,
            readOnly: true,
          }}
          value={props.data?.content?.returned}
          onChange={() => { }}
        />
      </div>
    );
  },
};
