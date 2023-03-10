import React from 'react';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Json } from './assets/json.svg';
import { ReactComponent as Ts } from './assets/ts.svg';
import { ReactComponent as Go } from './assets/go.svg';

const LOGOS = {
  js: <Js style={{ height: 20, width: 20 }} />,
  json: <div className='d-flex justify-content-center'>
    <Json style={{ height: 18 }} />
  </div>,
  log: <i className='fas fa-file' style={{ fontSize: '.9em' }} />,
  rs: <Rust style={{ height: 30, width: 30, marginLeft: -4, transform: 'scale(.85)' }} />,
  toml: <i className='fas fa-file' />,
  ts: <Ts style={{ height: 22, width: 22, marginBottom: 2 }} />,
  go: <Go style={{ height: 22, width: 22 }} />,
}

function File({ newFilename, filename, content, ext, onClick, ...props }) {
  return <button className='d-flex align-items-center pb-1' style={{
    background: props.currentTab === filename ? '#ddd' : 'initial',
    border: 'none'
  }}
    onClick={onClick}
    type="button" >
    {props.new ? <div>
      <i className='fas fa-file' />
      <input type='text'
        autoFocus
        value={newFilename}
        onChange={e => {
          e.stopPropagation()
          props.setFilename(e.target.value)
        }} />
    </div> : <div className='d-flex align-items-center justify-content-between w-100'>
      <div className='d-flex align-items-center'>
        <div style={{ minWidth: 32 }}>
          {LOGOS[ext] || LOGOS.log}
        </div>
        <span style={{ fontSize: '.9em' }}>{filename}</span>
      </div>
      {!props.readOnly &&
        !['json', 'mod'].includes(ext) &&
        ![
          'plugin.ts',
          'lib.rs',
          'main.go',
          'esbuild.js',
          'config.js'
        ].includes(filename) && < i className='fas fa-times me-2'
          onClick={e => {
            e.stopPropagation()
            props.removeFile(filename)
          }} />}
    </div>}
  </button>
}

function FileManager({
  files, onNewFile, onFileClick, onFileChange, selectedPlugin,
  configFiles, currentTab, removeFile }) {
  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee', flex: 1 }}>
      <Header onNewFile={onNewFile} selectedPlugin={selectedPlugin} readOnly={selectedPlugin.type === "github"} />

      <div className='d-flex flex-column scroll-container'>
        {[...[...files, ...configFiles]
          .filter(f => f.filename !== '.DS_Store')]
          .sort((a, b) => a.filename.localeCompare(b.filename))
          .map((file, i) => {
            return <File {...file}
              key={`${file.filename}-${i}`}
              readOnly={selectedPlugin.type === "github"}
              currentTab={currentTab}
              removeFile={removeFile}
              onClick={() => onFileClick(file)}
              setFilename={onFileChange} />
          })}
      </div>
    </div>
  );
}

function Header({ onNewFile, selectedPlugin, readOnly }) {
  return <div className='d-flex justify-content-between align-items-center sidebar-header'
    style={{
      cursor: readOnly ? 'initial' : 'pointer',
      pointerEvents: readOnly ? 'none' : 'initial'
    }} onClick={onNewFile}>
    <div className='d-flex align-items-center'>
      <i className='fas fa-chess-rook me-1' />
      <span className='fw-bold'>{selectedPlugin?.filename}</span>
    </div>

    {!readOnly && <i className='fas fa-file-circle-plus' />}
  </div>
}

export default FileManager;
