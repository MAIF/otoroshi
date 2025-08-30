import React from 'react';

export default function NodeTrashButton(props) {
  return (
    <i
      className="fas fa-trash node-trash"
      onClick={(e) => {
        e.stopPropagation();
        window
          .newConfirm(`Delete the ${props.data.display_name || props.data.name} node ?`)
          .then((ok) => {
            if (ok) {
              props.onNodeDelete
                ? props.onNodeDelete()
                : props.data.functions.onNodeDelete(props.id);
            }
          });
      }}
    />
  );
}
