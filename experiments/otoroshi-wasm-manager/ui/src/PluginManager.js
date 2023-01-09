import React from 'react';

const Plugin = ({ onPluginClick, filename, newFilename, ...props }) => {
  return <button type="button" style={{ border: 'none' }}
    className="d-flex align-items-center justify-content-between"
    onClick={() => {
      if (!props.new)
        onPluginClick(filename)
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
      <span style={{
        whiteSpace: 'nowrap',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        maxWidth: '85%'
      }}>{filename}</span>
      <i className='fas fa-chevron-right' />
    </>
    }

  </button>
}

function PluginManager({ plugins, onNewPlugin, onPluginClick, setFilename }) {
  return (
    <div className='d-flex flex-column' style={{ minWidth: 250, background: '#eee' }}>
      <Header onNewPlugin={onNewPlugin} />
      {plugins.map(plugin => {
        return <Plugin {...plugin}
          key={plugin.filename}
          onPluginClick={onPluginClick}
          setFilename={setFilename} />
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

export default PluginManager;
