import React from 'react';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Json } from './assets/json.svg';
import { ReactComponent as Ts } from './assets/ts.svg';

const LOGOS = {
  js: <Js style={{ height: 20, width: 20 }} />,
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
          {LOGOS[ext] || <i className='fas fa-file' />}
        </div>
        <span className='ms-2'>{filename}</span>
      </div>
      {!props.readOnly && (['rs', 'ts', 'js', 'sum'].includes(ext) && ![
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
      <Header onNewFile={onNewFile} selectedPlugin={selectedPlugin} readOnly={selectedPlugin.type === "github"} />
      {[...files, ...configFiles].map((file, i) => {
        return <File {...file}
          key={`${file.filename}-${i}`}
          readOnly={selectedPlugin.type === "github"}
          currentTab={currentTab}
          removeFile={removeFile}
          onClick={() => onFileClick(file)}
          setFilename={newFilename => onFileChange(i, newFilename)} />
      })}
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
