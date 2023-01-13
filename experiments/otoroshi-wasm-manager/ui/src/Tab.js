import React, { useState, useEffect } from 'react';
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
    height='100%'
    readOnly={readOnly}
    maxWidth='calc(100vw - 250px)'
    value={content}
    extensions={[ext === 'rs' ? rust() : json()]}
    onChange={onChange}
  />
}
export default Tab;
