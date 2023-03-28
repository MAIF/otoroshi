

export function TabsHeader({
  selectedPlugin, onSave, onBuild, onDownload,
  showPlaySettings, showPublishSettings, children }) {

  return <Header
    selectedPluginType={selectedPlugin?.type}
    onSave={onSave}
    onBuild={onBuild}
    onDownload={onDownload}
    showActions={!!selectedPlugin}
    showPlaySettings={showPlaySettings}
    showPublishSettings={showPublishSettings}>
    {children}
  </Header>
}

function Header({
  children, onSave, onBuild, showActions, onDownload,
  showPlaySettings, showPublishSettings, selectedPluginType }) {

  return <div className='d-flex align-items-center justify-content-between bg-light'
    style={{ position: 'fixed', height: 42, zIndex: 10, width: 'calc(100vw - 250px)' }}>
    {children}

    <div className='d-flex align-items-center'>
      {showActions && <>
        <Save onSave={onSave} />
        <Build onBuild={onBuild} />
        <Download onDownload={onDownload} />
        {selectedPluginType !== 'go' && <Publish showPublishSettings={showPublishSettings} />}
      </>}
      <Play showPlaySettings={showPlaySettings} />
    </div>
  </div>
}

function Save({ onSave }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={onSave}>
    <i className='fas fa-save' />
  </button>
}

function Build({ onBuild }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={onBuild}>
    <i className='fas fa-hammer' />
  </button>
}

function Download({ onDownload }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={onDownload}>
    <i className='fas fa-download' />
  </button>
}

function Publish({ showPublishSettings }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={showPublishSettings}>
    <i className='fas fa-upload' />
  </button>
}

function Play({ showPlaySettings }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={showPlaySettings}
  >
    <i className='fas fa-play' />
  </button>
}