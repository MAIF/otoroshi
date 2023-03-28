import React, { useState } from 'react';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Ts } from './assets/ts.svg';
import { ReactComponent as Go } from './assets/go.svg';
import { ReactComponent as Github } from './assets/github.svg';
import { ReactComponent as OPA } from './assets/opa.svg';
import { createGithubRepo } from './services';
import { LOGOS } from './FilesLogo';

class PluginManager extends React.Component {
  render() {
    const { plugins, onNewPlugin, selectedPlugin, ...props } = this.props;

    return (
      <div className='d-flex flex-column' style={{ minWidth: 250, flex: selectedPlugin ? 0 : 1 }}>
        <Header onNewPlugin={onNewPlugin} reloadPlugins={props.reloadPlugins} />
        {selectedPlugin && <div className='d-flex justify-content-between align-items-center sidebar-header'
          style={{
            cursor: 'pointer'
          }} onClick={() => props.setSelectedPlugin(undefined)}>
          <div className='d-flex align-items-center'>
            <i className='fas fa-shuffle me-1' />
            <span className='fw-bold'>Change current plugin</span>
          </div>
        </div>}
        <div className='d-flex flex-column scroll-container'>
          {!selectedPlugin &&
            [...plugins]
              .sort((a, b) => a.type.localeCompare(b.type))
              .map(plugin => {
                return <Plugin
                  key={plugin.pluginId || 'new'}
                  {...plugin}
                  {...props}
                />
              })}
        </div>
      </div>
    );
  }
}

function NewPluginModal({ onNewPlugin, setProjectSelector, reloadPlugins }) {
  const [showGithubModal, setGithubModal] = useState(false);

  const [repo, setRepo] = useState("");
  const [owner, setOwner] = useState("");
  const [branch, setBranch] = useState("main");
  const [error, setError] = useState();
  const [isPrivate, setStatus] = useState(false);

  if (showGithubModal) {
    return <div style={{
      position: 'absolute',
      left: 252,
      top: 0,
      zIndex: 100,
      width: 300,
      maxWidth: 500,
      background: '#ddd',
      borderTopLeftRadius: 0,
      borderBottomLeftRadius: 0,
      borderTopRightRadius: 4,
      borderBottomRightRadius: 4
    }} className="justify-content-center p-3"
      onClick={e => e.stopPropagation()}>
      <div className='d-flex flex-column' style={{ position: 'relative' }}>
        {error && <pre className="alert alert-warning" role="alert">
          {JSON.stringify(error, null, 4)}
        </pre>}

        <i className='fa fa-lg fa-times' style={{
          color: '#000',
          position: 'absolute',
          top: 4,
          right: 6,
          cursor: 'pointer',
          left: 252
        }} onClick={e => {
          e.stopPropagation();
          setGithubModal(false)
        }} />

        <div className='mb-2'>
          <label htmlFor="owner" className='form-label'>Owner</label>
          <input type="text" className="form-control form-control-sm" placeholder='octocat' value={owner} id="owner" onChange={e => {
            setError(undefined);
            setOwner(e.target.value)
          }} />
        </div>

        <div className='mb-2'>
          <label htmlFor="repository" className='form-label'>Repository</label>
          <input type="text" value={repo} className="form-control form-control-sm" placeholder='my-wasm-epo' id="repository" onChange={e => {
            setError(undefined);
            setRepo(e.target.value)
          }} />
        </div>

        <div className='mb-3'>
          <label htmlFor="branch" className='form-label'>Branch</label>
          <input type="text" value={branch} className="form-control form-control-sm" id="branch" onChange={e => {
            setError(undefined);
            setBranch(e.target.value)
          }} />
        </div>

        <div className="form-check">
          <input className="form-check-input" type="checkbox" checked={isPrivate} id="flexCheckChecked" onChange={() => setStatus(!isPrivate)} />
          <label className="form-check-label" htmlFor="flexCheckChecked">
            Private repository
          </label>
        </div>

        <button type="button" className='btn btn-secondary mt-3'
          onClick={e => {
            e.stopPropagation();
            setError(undefined)
            createGithubRepo(owner, repo, branch, isPrivate)
              .then(r => {
                if (r.status > 300) {
                  setError(r);
                } else {
                  setGithubModal(false)
                  reloadPlugins()
                }
              })
          }}>Import sources</button>
      </div>
    </div>
  }

  return <div style={{
    position: 'absolute',
    left: 252,
    zIndex: 100,
    background: '#ccc'
  }} className="d-flex justify-content-center project-confirm-box rounded">
    {[
      { icon: <Rust style={{ height: 30, width: 32, marginLeft: -4, transform: 'scale(1.5)' }} />, onClick: () => onNewPlugin('rust') },
      { icon: <Js style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('js') },
      { icon: <Ts style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('ts') },
      { icon: <Go style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('go') },
      { icon: <OPA style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('opa') },
      {
        icon: <Github style={{ height: 32, width: 32 }} />, onClick: e => {
          e.stopPropagation()
          setGithubModal(true)
        }
      },
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

function Header({ onNewPlugin, reloadPlugins }) {
  const [showProjectSelector, setProjectSelector] = useState(false)

  return <div className='d-flex justify-content-between align-items-center sidebar-header'
    onClick={e => {
      e.stopPropagation();
      setProjectSelector(!showProjectSelector)
    }}
    style={{
      position: 'relative',
      cursor: 'pointer'
    }}>
    <div className='d-flex align-items-center'>
      <i className='fas fa-chess-rook fa-sm me-1' />
      <span className='fw-bold'>Plugins</span>
    </div>

    <div style={{
      background: '#eee',
      borderRadius: 4
    }}>
      <i className='fas fa-plus p-1' />
    </div>

    {showProjectSelector && <NewPluginModal
      onNewPlugin={onNewPlugin}
      reloadPlugins={reloadPlugins}
      setProjectSelector={setProjectSelector} />}
  </div>
}

class Plugin extends React.Component {

  render() {
    const { onPluginClick, filename, pluginId, newFilename, ...props } = this.props;

    return <button type="button"
      style={{ border: 'none' }}
      className="d-flex align-items-center justify-content-between py-1"
      onClick={() => {
        if (!props.new)
          onPluginClick(pluginId)
      }}
    // onDoubleClick={e => {
    //   e.stopPropagation()
    //   props.enablePluginRenaming(pluginId)
    // }}
    >

      {props.new && <>
        <div style={{ minWidth: 18, marginLeft: -4, marginRight: 4 }}>
          {LOGOS[props.type]}
        </div>
        <input type='text'
          autoFocus
          className="form-control"
          value={newFilename}
          onChange={e => {
            e.stopPropagation()
            props.setFilename(e.target.value)
          }} />
      </>}

      {!props.new && <>
        <div className='d-flex align-items-center'>
          <div style={{ minWidth: 18, marginLeft: 2, marginRight: 8 }}>
            {LOGOS[props.type]}
          </div>
          <span style={{
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            maxWidth: '90%'
          }}>{filename}</span>
        </div>
      </>}
    </button>
  }
}
export default PluginManager;
