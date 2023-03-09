

export function TabsHeader({
  selectedPlugin, onSave, onBuild, onDocs,
  showPlaySettings, showPublishSettings, children }) {

  return <Header
    selectedPluginType={selectedPlugin?.type}
    onSave={onSave}
    onBuild={onBuild}
    showActions={!!selectedPlugin}
    onDocs={onDocs}
    showPlaySettings={showPlaySettings}
    showPublishSettings={showPublishSettings}>
    {children}
  </Header>
}

function Header({
  children, onSave, onBuild, showActions,
  onDocs, showPlaySettings, showPublishSettings, selectedPluginType }) {

  return <div className='d-flex align-items-center justify-content-between bg-light'
    style={{ position: 'fixed', height: 42, zIndex: 10, width: 'calc(100vw - 250px)' }}>
    {children}

    <div className='d-flex align-items-center'>
      {showActions && <>
        <Save onSave={onSave} />
        <Build onBuild={onBuild} />
        {selectedPluginType !== 'go' && <Publish showPublishSettings={showPublishSettings} />}
      </>}
      <Play showPlaySettings={showPlaySettings} />
      <Docs onDocs={onDocs} />
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

function Publish({ showPublishSettings }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={showPublishSettings}>
    <i className='fas fa-upload' />
  </button>
}

function Docs({ onDocs }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-3"
    onClick={onDocs}
  >
    <i className='fas fa-book' />
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