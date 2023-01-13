import React, { useState, useEffect } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { solarizedDark } from '@uiw/codemirror-theme-solarized';

import io from 'socket.io-client';
const socket = io();

function Terminal({ sizeTerminal, toggleResizingTerminal, changeTerminalSize, selectedPlugin }) {
  const [isConnected, setIsConnected] = useState(socket.connected);
  const [content, setContent] = useState('')

  useEffect(() => {
    if (selectedPlugin) {
      socket.on('connect', () => {
        setIsConnected(true);
      });

      socket.on('disconnect', () => {
        setIsConnected(false);
      });

      socket.on(selectedPlugin.pluginId, data => {
        const text = new TextDecoder("utf-8").decode(data)

        console.log(`received: ${text}`)
        setContent(content => content + text)
      })

      return () => {
        socket.off(selectedPlugin.pluginId)
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
      onMouseuUp={e => {
        e.stopPropagation();
        toggleResizingTerminal(false)
      }}>
    </div>
    <div className='d-flex justify-content-between align-items-center mx-2 me-3'>
      <div style={{ borderBottom: '2px solid #f9b000', textTransform: 'uppercase', width: 'fit-content' }} className='p-1'>
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
      // maxHeight={`calc(${(sizeTerminal) * 100}vh)`}
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