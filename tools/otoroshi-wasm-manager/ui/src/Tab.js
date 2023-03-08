import React from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { rust } from '@codemirror/lang-rust';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { go } from '@codemirror/legacy-modes/mode/go';
import { StreamLanguage } from '@codemirror/language';
import { javascript } from '@codemirror/lang-javascript';
import { autocompletion } from '@codemirror/autocomplete';
import { marked } from "marked";

function Tab({ content, ext, handleContent, selected, readOnly }) {
  if (!content || !selected)
    return null

  const renderCodeMirror = () => {
    return <CodeMirror
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
        ext === 'go' ? StreamLanguage.define(go) :
          ext === 'md' ? markdown() :
            ext === 'rs' ? rust() : (ext === 'ts' || ext === 'js') ? javascript({ typescript: true }) : json(),
        autocompletion()
      ]}
      onChange={value => {
        handleContent(value)
      }}
    />
  }

  if (ext === 'md') {
    return <div style={{
      display: 'grid',
      gridTemplateColumns: '1fr 1fr',
      flex: 1,
    }}>
      {renderCodeMirror()}

      <div
        className='p-3'
        style={{
          borderLeft: '1px solid #eee'
        }}
        dangerouslySetInnerHTML={{
          __html: marked.parse(content)
        }}
      />
    </div>
  } else {
    return renderCodeMirror();
  }
}
export default Tab;
