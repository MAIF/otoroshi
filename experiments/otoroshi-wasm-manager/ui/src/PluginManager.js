import React, { useState } from 'react';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Ts } from './assets/ts.svg';
import { ReactComponent as Go } from './assets/go.svg';
import { ReactComponent as Github } from './assets/github.svg';
import { createGithubRepo } from './services';

class PluginManager extends React.Component {
  render() {
    const { plugins, onNewPlugin, ...props } = this.props;

    return (
      <div className='d-flex flex-column' style={{ minWidth: 250, background: '#ddd' }}>
        <Header onNewPlugin={onNewPlugin} reloadPlugins={props.reloadPlugins} />
        {plugins.map(plugin => {
          return <Plugin
            key={plugin.pluginId || 'new'}
            {...plugin}
            {...props}
          />
        })}
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

  if (showGithubModal) {
    return <div style={{
      position: 'absolute',
      left: 252,
      top: 0,
      zIndex: 100,
      width: 300,
      maxWidth: 500,
      background: '#ddd'
    }} className="d-flex flex-column justify-content-center p-3 rounded"
      onClick={e => e.stopPropagation()}>
      {error && <pre class="alert alert-warning" role="alert">
        {JSON.stringify(error, null, 4)}
      </pre>}

      <label for="owner" className='form-label'>Owner</label>
      <input type="text" class="form-control" placeholder='octocat' value={owner} id="owner" onChange={e => {
        setError(undefined);
        setOwner(e.target.value)
      }} />

      <label for="repository" className='form-label'>Repository</label>
      <input type="text" value={repo} class="form-control" placeholder='my-wasm-epo' id="repository" onChange={e => {
        setError(undefined);
        setRepo(e.target.value)
      }} />

      <label for="branch" className='form-label'>Branch</label>
      <input type="text" value={branch} class="form-control" id="branch" onChange={e => {
        setError(undefined);
        setBranch(e.target.value)
      }} />

      <button type="button" className='btn btn-secondary mt-3'
        onClick={e => {
          e.stopPropagation();
          setError(undefined)
          createGithubRepo(owner, repo, branch)
            .then(r => {
              if (r.status > 300) {
                setError(r);
              } else {
                showGithubModal(false)
                reloadPlugins()
              }
            })
        }}>Import sources</button>
    </div>
  }

  return <div style={{
    position: 'absolute',
    left: 252,
    zIndex: 100,
    background: '#ddd'
  }} className="d-flex justify-content-center project-confirm-box rounded">
    {[
      { icon: <Rust style={{ height: 30, width: 32, marginLeft: -4, transform: 'scale(1.5)' }} />, onClick: () => onNewPlugin('rust') },
      { icon: <Js style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('js') },
      { icon: <Ts style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('ts') },
      { icon: <Go style={{ height: 32, width: 32 }} />, onClick: () => onNewPlugin('go') },
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
      onDoubleClick={e => {
        e.stopPropagation()
        props.enablePluginRenaming(pluginId)
      }}
    >

      {props.new && <>
        <i className='fas fa-file ' style={{ minWidth: 18, marginLeft: -4, marginRight: 4 }} />
        <input type='text'
          autoFocus
          class="form-control"
          value={newFilename}
          onChange={e => {
            e.stopPropagation()
            props.setFilename(e.target.value)
          }} />
      </>}

      {!props.new && <>
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
      </>}
    </button>
  }
}

export default PluginManager;
