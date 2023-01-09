import React, { useState, useEffect } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { rust } from '@codemirror/lang-rust';
import default_rust_plugin from './data/default_rust_plugin.rs'

function Tab({ content, filename, ext, closeTab, setCurrentTab, selected }) {
  const [state, setState] = useState()

  useEffect(() => {
    if (!content)
      fetch(default_rust_plugin)
        .then(r => r.text())
        .then(setState)
    else
      setState(content)
  }, []);

  const onChange = React.useCallback((value, viewUpdate) => {
    console.log('value:', value);
  }, []);


  if (!state)
    return null;

  return selected && <CodeMirror
    height='100%'
    value={state}
    extensions={[rust()]}
    onChange={onChange}
  />
}
export default Tab;
