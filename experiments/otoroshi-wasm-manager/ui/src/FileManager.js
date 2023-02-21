import React from 'react';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Json } from './assets/json.svg';
import { ReactComponent as Ts } from './assets/ts.svg';

const LOGOS = {
  js: <Js style={{ height: 24, width: 24 }} />,
  json: <div className='d-flex justify-content-center'>
    <Json style={{ height: 24, width: 32 }} />
  </div>,
  log: <i className='fas fa-file' />,
  rs: <Rust style={{ height: 30, width: 32, marginLeft: -4 }} />,
  toml: <i className='fas fa-file' />,
  ts: <Ts style={{ height: 22, width: 22, marginBottom: 2 }} />,
}

function File({ newFilename, filename, content, ext, onClick, ...props }) {
  return <button className='d-flex align-items-center py-1' style={{
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
          {LOGOS[ext]}
        </div>
        <span className='ms-2'>{filename}</span>
      </div>
      {(['rs', 'ts', 'js'].includes(ext) && ![
        'plugin.ts',
        'lib.rs',
        'esbuild.js',
        'config.js'
      ].includes(filename)) && < i className='fas fa-times me-2'
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
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee' }}>
      <Header onNewFile={onNewFile} selectedPlugin={selectedPlugin} />
      {[...files, ...configFiles].map((file, i) => {
        return <File {...file}
          key={file.filename}
          currentTab={currentTab}
          removeFile={removeFile}
          onClick={() => onFileClick(file)}
          setFilename={newFilename => onFileChange(i, newFilename)} />
      })}
    </div>
  );
}

function Header({ onNewFile, selectedPlugin }) {
  return <div className='px-2 py-1 d-flex justify-content-between align-items-center' style={{
    backgroundColor: '#a1a1a1'
  }}>
    <div className='d-flex align-items-center'>
      <i className='fas fa-chess-rook me-1 text-white' />
      <span className='fw-bold text-white'>{selectedPlugin?.filename}</span>
    </div>

    <i className='fas fa-file-circle-plus text-white' onClick={onNewFile} />
  </div>
}

export default FileManager;
