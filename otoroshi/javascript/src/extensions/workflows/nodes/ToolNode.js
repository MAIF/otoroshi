import React from 'react';


export const ToolNode = {
  kind: 'tool',
  flow: [''],
  form_schema: {
    
  },
  sources: ['output'],
  nodeRenderer: (props) => {
    return (
      <div className="node-text-renderer">
        COUCOU
      </div>
    );
  },
};
