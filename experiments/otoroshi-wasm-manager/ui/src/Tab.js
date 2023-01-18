import React from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { rust } from '@codemirror/lang-rust';
import { json } from '@codemirror/lang-json';
import { javascript } from '@codemirror/lang-javascript';
import { autocompletion } from '@codemirror/autocomplete';

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
    extensions={[
      ext === 'rs' ? rust() : ext === 'ts' ? javascript({ typescript: true }) : json(),
      autocompletion()
    ]}
    onChange={onChange}
  />
}
export default Tab;
