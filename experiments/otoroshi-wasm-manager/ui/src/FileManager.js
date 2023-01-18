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
  return <button className='d-flex align-items-center bg-light'
    onClick={onClick}
    type="button" style={{ border: 'none' }}>
    {props.new ? <div>
      <i className='fas fa-file' />
      <input type='text'
        autoFocus
        value={newFilename}
        onChange={e => {
          e.stopPropagation()
          props.setFilename(e.target.value)
        }} />
    </div> : <div className='d-flex align-items-center justify-content-center'>
      <div style={{ minWidth: 32 }}>
        {LOGOS[ext]}
      </div>
      <span className='ms-2'>{filename}</span>
    </div>}
  </button>
}

function FileManager({
  files, onNewFile, onFileClick, onFileChange, selectedPlugin,
  configFiles }) {
  return (
    <div className='d-flex flex-column mt-1' style={{ minWidth: 250, background: '#eee' }}>
      <Header onNewFile={onNewFile} selectedPlugin={selectedPlugin}/>
      {[...files, ...configFiles].map((file, i) => {
        return <File {...file}
          key={file.filename}
          onClick={() => onFileClick(file)}
          setFilename={newFilename => onFileChange(i, newFilename)} />
      })}
    </div>
  );
}

function Header({ onNewFile, selectedPlugin }) {
  return <div className='px-2 py-1 d-flex justify-content-between align-items-center'
    style={{
      background: 'rgb(228, 229, 230)'
    }}>
    <div className='d-flex align-items-center'>
      <i className='fas fa-chess-rook me-1' />
      <span className='fw-bold'>{selectedPlugin?.filename}</span>
    </div>

    <i className='fas fa-file-circle-plus' onClick={onNewFile} />
  </div>
}

export default FileManager;
