import React, { useState, useEffect } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { rust } from '@codemirror/lang-rust';

function Tab({ content, handleContent, selected }) {

  const onChange = React.useCallback((value, viewUpdate) => {
    handleContent(value)
  }, []);


  if (!content)
    return null

  return selected && <CodeMirror
    height='100%'
    maxWidth='calc(100vw - 250px)'
    value={content}
    extensions={[rust()]}
    onChange={onChange}
  />
}
export default Tab;
