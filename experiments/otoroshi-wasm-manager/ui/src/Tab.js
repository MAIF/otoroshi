import React from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { rust } from '@codemirror/lang-rust';
import { json } from '@codemirror/lang-json';

function Tab({ content, ext, handleContent, selected, readOnly }) {
  const onChange = React.useCallback((value, viewUpdate) => {
    handleContent(value)
  }, []);


  if (!content)
    return null

  return selected && <CodeMirror
    onKeyDown={e => {
      const charCode = String.fromCharCode(e.which).toLowerCase();

      if (!((e.ctrlKey || e.metaKey) && charCode === 's')) {
        e.stopPropagation()
      }
    }}
    height='100%'
    readOnly={readOnly}
    maxWidth='calc(100vw - 250px)'
    value={content}
    extensions={[ext === 'rs' ? rust() : json()]}
    onChange={onChange}
  />
}
export default Tab;
