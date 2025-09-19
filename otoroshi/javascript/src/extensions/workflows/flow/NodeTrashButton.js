import React from 'react';

export default function NodeTrashButton(props) {
  return <div className='d-flex node-trash' style={{ gap: '.75rem' }}>
    {!props.isStart && <i
      className="fas fa-trash"
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
    />}
    <i className={`fas fa-circle breakpoint ${props.breakpoint ? 'breakpoint--enabled' : ''}`} onClick={props.toggleBreakPoint} />
  </div>
}
