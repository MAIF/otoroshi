import React from 'react';

function PluginManager({ plugins, onNewPlugin, onPluginClick, setFilename, removePlugin }) {
  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee' }}>
      <Header onNewPlugin={onNewPlugin} />
      {plugins.map(plugin => {
        return <Plugin {...plugin}
          key={plugin.filename}
          onPluginClick={onPluginClick}
          setFilename={setFilename}
          removePlugin={removePlugin} />
      })}
    </div >
  );
}

function Header({ onNewPlugin }) {
  return <div className='bg-light bg-gradient px-2 py-1 d-flex justify-content-between align-items-center'>
    <div className='d-flex align-items-center'>
      <i className='fas fa-rocket fa-sm me-1' />
      <span className='fw-bold'>Plugins</span>
    </div>

    <i className='fas fa-plus' onClick={onNewPlugin} />
  </div>
}

function Plugin({ onPluginClick, filename, pluginId, newFilename, ...props }) {
  return <button type="button" style={{ border: 'none' }}
    className="d-flex align-items-center justify-content-between py-1"
    onClick={() => {
      if (!props.new)
        onPluginClick(pluginId)
    }}>
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
      {/* <i className='fas fa-chevron-right' /> */}
    </>
    }

  </button>
}

export default PluginManager;
