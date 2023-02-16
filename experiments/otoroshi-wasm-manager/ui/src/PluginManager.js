import React, { useState } from 'react';
import { ReactComponent as As } from './assets/as.svg';
import { ReactComponent as Rust } from './assets/rust.svg';

function PluginManager({ plugins, onNewPlugin, ...props }) {
  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#ddd' }}>
      <Header onNewPlugin={onNewPlugin} />
      {plugins.map(plugin => {
        return <Plugin {...plugin}
          key={plugin.filename}
          {...props} />
      })}
    </div >
  );
}

function NewPluginModal({ onNewPlugin, setProjectSelector }) {
  return <div style={{
    position: 'absolute',
    left: 252,
    zIndex: 100,
    background: '#ddd'
  }} className="d-flex justify-content-center project-confirm-box rounded">
    {[
      { icon: <Rust style={{ height: 30, width: 32, marginLeft: -4, transform: 'scale(1.5)' }} />, onClick: () => onNewPlugin('rust') },
      { icon: <As style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('assembly-script') },
      {
        icon: <i className='fas fa-times fa-lg' style={{
          width: 22
        }} />,
        onClick: () => setProjectSelector(false)
      }
    ].map(({ icon, onClick }, i) => {
      return <button
        type="button"
        key={`action-${i}`}
        className='p-1 px-2 my-1 me-1 btn btn-sm btn-light'
        onClick={onClick}
        style={{ border: 'none' }}>
        {icon}
      </button>
    })}
  </div>
}

function Header({ onNewPlugin }) {
  const [showProjectSelector, setProjectSelector] = useState(false)

  return <div className='px-2 py-1 d-flex justify-content-between align-items-center'
    onClick={() => setProjectSelector(false)}
    style={{
      backgroundColor: '#a1a1a1',
      position: 'relative'
    }}>
    <div className='d-flex align-items-center text-white'>
      <i className='fas fa-rocket fa-sm me-1 text-white' />
      <span className='fw-bold text-white'>Plugins</span>
    </div>

    <i className='fas fa-plus text-white' onClick={e => {
      e.stopPropagation();
      setProjectSelector(!showProjectSelector)
    }} />

    {showProjectSelector && <NewPluginModal onNewPlugin={onNewPlugin} setProjectSelector={setProjectSelector} />}
  </div>
}

function Plugin({ onPluginClick, filename, pluginId, newFilename, ...props }) {
  return <button type="button" style={{ border: 'none' }}
    className="d-flex align-items-center justify-content-between py-1"
    onClick={() => {
      if (!props.new)
        onPluginClick(pluginId)
    }}
    onDoubleClick={e => {
      e.stopPropagation()
      props.enablePluginRenaming(pluginId)
    }}>
    {props.new ? <>
      <i className='fas fa-file ' style={{ minWidth: 18, marginLeft: -4, marginRight: 4 }} />
      <input type='text'
        autoFocus
        class="form-control"
        value={newFilename}
        onChange={e => {
          e.stopPropagation()
          props.setFilename(e.target.value)
        }} />
    </> : <>
      <div className='d-flex align-items-center'>
        <i className='fas fa-times me-2'
          onClick={e => {
            e.stopPropagation()
            props.removePlugin(pluginId)
          }} />
        <span style={{
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          maxWidth: '90%'
        }}>{filename}</span>
      </div>
    </>
    }

  </button>
}

export default PluginManager;
