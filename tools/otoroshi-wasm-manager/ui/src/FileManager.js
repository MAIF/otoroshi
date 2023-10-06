import React from 'react';
import ReleasesMenu from './ReleasesMenu';
import { LOGOS } from './FilesLogo';

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

function Group({ items, folder, onFileClick, component, ...props }) {
  const Component = component;

  return <>
    <div style={{ background: '#ddd', color: '#000', fontWeight: 'bold' }} className="ps-3 mb-1">
      <span>{folder}</span>
    </div>
    <div className='d-flex flex-column'>
      {component ? <Component {...props} /> :
        items
          .sort((a, b) => a.ext.localeCompare(b.ext))
          .map((item, i) => {
            return <File {...item}
              key={`item.filename-${i}`}
              {...props}
              onClick={() => onFileClick(item)} />
          })}
    </div>
  </>
}

const GROUPS = [
  {
    filter: item => ['go', 'rust', 'rs', 'ts', 'js', 'rego'].includes(item.ext) || item.new,
    folder: 'Sources'
  },
  {
    filter: item => ['package.json', 'go.mod', 'Cargo.toml'].includes(item.filename),
    folder: 'Manifest'
  },
  {
    filter: item => item.filename === 'config',
    folder: 'Configuration'
  },
  {
    filter: item => item.ext === 'log',
    folder: 'Logs'
  },
  {
    filter: item => ['README.md', 'wapm.toml'].includes(item.filename),
    folder: 'Deploy'
  },
  {
    folder: 'Miscellaneous',
    filter: () => false
  },
  {
    filter: () => false,
    folder: 'Releases',
    component: ReleasesMenu
  }
]

function FileManager({
  onNewFile, onFileClick, onFileChange, selectedPlugin, currentTab, removeFile, ...props
}) {

  const files = [...props.files, ...props.configFiles].filter(f => f.filename !== '.DS_Store')

  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee', flex: 1 }}>
      <Header onNewFile={onNewFile} selectedPlugin={selectedPlugin} readOnly={selectedPlugin.type === "github"} />

      <div className='d-flex flex-column scroll-container mt-1'>
        {[...files]
          .reduce((acc, file) => {
            const idx = GROUPS.findIndex(group => group.filter(file));
            if (idx !== -1) {
              return acc.map((c, i) => {
                if (i === idx) {
                  return {
                    ...c,
                    items: [...c.items, file]
                  }
                } else {
                  return c;
                }
              })
            } else {
              return acc.map(c => {
                if (c.folder === 'Miscellaneous') {
                  return {
                    ...c,
                    items: [...c.items, file]
                  }
                } else {
                  return c;
                }
              });
            }
          }, GROUPS.map(g => ({ items: [], ...g })))
          .filter(group => group.items.length > 0 || group.folder === 'Releases')
          .map(group => {
            return <Group
              files={files}
              {...group}
              key={`${group.folder}`}
              readOnly={selectedPlugin.type === "github"}
              currentTab={currentTab}
              removeFile={removeFile}
              onFileClick={onFileClick}
              setFilename={onFileChange} />
          })
        }
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

    {!readOnly && <div style={{
      background: '#eee',
      borderRadius: 4
    }}>
      <i className='fas fa-file-circle-plus p-1' />
    </div>}
  </div>
}

export default FileManager;
