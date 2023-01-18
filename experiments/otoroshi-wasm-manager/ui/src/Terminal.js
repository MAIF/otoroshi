import React, { useState, useEffect } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { solarizedDark } from '@uiw/codemirror-theme-solarized';

import io from 'socket.io-client';
const socket = io();

function Terminal({ sizeTerminal, toggleResizingTerminal, changeTerminalSize, selectedPlugin }) {
  const [content, setContent] = useState('')

  useEffect(() => {
    if (selectedPlugin) {
      socket.on(selectedPlugin.pluginId, text => {
        if (text.includes('Starting build')) {
          setContent(text)
        } else {
          setContent(content => content + text)
        }
      })

      return () => {
        socket.removeAllListeners()
        socket.off('disconnect')
      };
    }
  }, [selectedPlugin]);

  return <div style={{
    flex: sizeTerminal
  }} className="bg-light">
    <div style={{
      height: 12,
      background: '#eee'
    }}
      onMouseDown={e => {
        e.stopPropagation();
        toggleResizingTerminal(true)
      }}
      onMouseMove={e => e.stopPropagation()}
      onMouseuUp={e => {
        e.stopPropagation();
        toggleResizingTerminal(false)
      }}>
    </div>
    <div className='d-flex justify-content-between align-items-center mx-2 me-3'>
      <div style={{
        borderBottom: '2px solid #f9b000', textTransform: 'uppercase',
        userSelect: 'none',
        width: 'fit-content'
      }} className='p-1'>
        <span>Terminal</span>
      </div>
      <div className='d-flex align-items-center'>
        <i className='fas fa-ban fa-sm me-3' style={{ cursor: 'pointer' }}
          onClick={() => setContent('')} />
        <i className='fas fa-times fa-sm' style={{ cursor: 'pointer' }}
          onClick={() => changeTerminalSize(.1)} />
      </div>
    </div>

    <CodeMirror
      maxWidth='calc(100vw - 250px)'
      height={`calc(${(sizeTerminal) * 100}vh)`}
      value={content}
      extensions={[]}
      theme={solarizedDark}
      readOnly={true}
      editable={false}
      basicSetup={{
        lineNumbers: false,
        dropCursor: false
      }}
    />
  </div >
}

export default Terminal;