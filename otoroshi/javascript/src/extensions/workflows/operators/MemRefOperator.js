import React from 'react';

export const MemRefOperator = {
  kind: '$mem_ref',
  sources: ['output'],
  operators: true,
  nodeRenderer: (props) => {
    const memRef = props.data.content ? props.data.content['$mem_ref'] : {};
    return (
      <div className="assign-node">
        <span>{memRef?.name}</span>
      </div>
    );
  },
};
