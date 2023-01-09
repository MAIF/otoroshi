import React from 'react';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Rust } from './assets/rust.svg';

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
    </div> : <>
      {ext === 'js' ? <Js style={{ height: 24, width: 24 }} /> : <Rust style={{ height: 32, width: 32 }} />}
      <span className='ms-2'>{filename}</span>
    </>}
  </button>
}

function FileManager({ files, onNewFile, onFileClick, onFileChange }) {
  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee' }}>
      <Header onNewFile={onNewFile} />
      {files.map(file => {
        return <File {...file}
          key={file.filename}
          onClick={() => onFileClick(file)}
          setFilename={newFilename => onFileChange(file, newFilename)} />
      })}
    </div>
  );
}

function Header({ onNewFile }) {
  return <div className='bg-light bg-gradient px-2 py-1 d-flex justify-content-between align-items-center'>
    <div className='d-flex align-items-center'>
      <i className='fas fa-chevron-down fa-sm me-1' />
      <span className='fw-bold'>Plugin</span>
    </div>

    <i className='fas fa-file-circle-plus' onClick={onNewFile} />
  </div>
}

export default FileManager;
