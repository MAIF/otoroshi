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

function NewPluginModal({ onNewPlugin, setProjectSelector, reloadPlugins, active }) {
  const [showGithubModal, setGithubModal] = useState(false)

  const [repo, setRepo] = useState("");
  const [owner, setOwner] = useState("");
  const [branch, setBranch] = useState("main");
  const [error, setError] = useState();
  const [isPrivate, setStatus] = useState(false);

  if (showGithubModal) {
    return <div style={{
      position: 'absolute',
      top: 0,
      bottom: 0,
      left: 250,
      zIndex: 100,
      width: true ? 325 : 0, // active 
      background: '#eee',
      gap: '.5rem',
      transition: 'width .25s'
    }}
      onClick={e => e.stopPropagation()}>
      {active && <div className='d-flex flex-column h-100'>
        <h3 style={{ fontSize: '1.25rem', textAlign: 'center', fontWeight: 'bold', background: '#000', color: '#fff', height: 42, margin: 0 }}
          className='d-flex align-items-center justify-content-center'>Github repository</h3>

        <div className='d-flex flex-column' style={{ flex: 1, padding: '.5rem 1rem' }}>
          {error && <pre className="alert alert-warning" role="alert">
            {JSON.stringify(error, null, 4)}
          </pre>}
          <div className='mt-2'>
            <label htmlFor="owner" className='form-label' style={{ fontWeight: 'bold' }}>Owner</label>
            <input type="text" className="form-control form-control-md" placeholder='octocat' value={owner} id="owner" onChange={e => {
              setError(undefined);
              setOwner(e.target.value)
            }} />
          </div>

          <div className='mt-2'>
            <label htmlFor="repository" className='form-label' style={{ fontWeight: 'bold' }}>Repository</label>
            <input type="text" value={repo} className="form-control form-control-md" placeholder='my-wasm-epo' id="repository" onChange={e => {
              setError(undefined);
              setRepo(e.target.value)
            }} />
          </div>

          <div className='mt-2'>
            <label htmlFor="branch" className='form-label' style={{ fontWeight: 'bold' }}>Branch</label>
            <input type="text" value={branch} className="form-control form-control-md" id="branch" onChange={e => {
              setError(undefined);
              setBranch(e.target.value)
            }} />
          </div>

          <div className="mt-3 d-flex flex-column align-items-center justify-content-center p-3"
            style={{
              backgroundColor: isPrivate ? '#f9b0002e' : '#fff',
              border: isPrivate ? '2px solid #f9b000' : 'transparent',
              borderRadius: 6
            }}
            onClick={() => setStatus(!isPrivate)} >
            <i className='fas fa-shield-alt mb-2' style={{ color: "#f9b000", fontSize: 32 }} />
            <span style={{
              fontWeight: 'bold', color: '#f9b000', fontSize: 12, letterSpacing: 3, background: '#f9b0005e',
              padding: '.1rem .75rem', borderRadius: 12
            }} className='mb-2'>PRIVATE</span>
            Is private repository ?
          </div>

          <div className='d-flex align-items-center mt-auto' style={{ gap: '.5rem' }}>
            <button type="button" className='btn btn-secondary'
              style={{ flex: .5, border: 'none', borderRadius: 6, padding: '.5rem 1rem', background: '#000' }}
              onClick={e => {
                e.stopPropagation();
                setGithubModal(false)
              }} >Cancel </button>
            <button type="button"
              className='btn btn-secondary'
              style={{ flex: 1, background: '#f9b000', border: 'none', borderRadius: 6, padding: '.5rem 1rem' }}
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
      </div>}
    </div>
  }

  return <div style={{
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 250,
    zIndex: 100,
    width: active ? 225 : 0,
    background: '#eee',
    gap: '.5rem',
    transition: 'width .25s'
  }}>
    {active && <div className='d-flex flex-column h-100'>
      <h3 style={{ fontSize: '1.25rem', textAlign: 'center', fontWeight: 'bold', background: '#000', color: '#fff', height: 42, margin: 0 }}
        className='d-flex align-items-center justify-content-center'>Languages</h3>
      <div className='d-flex flex-column' style={{ flex: 1, padding: '.5rem 1rem' }}>
        {[
          {
            icon: <Rust style={{ height: 30, width: 32, marginLeft: -4, transform: 'scale(1.5)' }} />,
            title: 'Rust',
            onClick: () => onNewPlugin('rust')
          },
          {
            icon: <Js style={{ height: 32, width: 32 }} />,
            title: 'Javascript',
            onClick: () => onNewPlugin('js')
          },
          {
            icon: <Ts style={{ height: 32, width: 32 }} />,
            title: 'Typescript',
            onClick: () => onNewPlugin('ts')
          },
          {
            icon: <Go style={{ height: 32, width: 32 }} />,
            title: 'Golang',
            onClick: () => onNewPlugin('go')
          },
          {
            icon: <OPA style={{ height: 32, width: 32 }} />,
            title: 'Open Policy Agent',
            onClick: () => onNewPlugin('opa')
          },
          {
            icon: <Github style={{ height: 32, width: 32 }} />,
            title: 'Github',
            onClick: e => {
              e.stopPropagation()
              setGithubModal(true)
            }
          }
        ].map(({ icon, onClick, title }, i) => {
          return <button
            type="button"
            key={`action-${i}`}
            className='btn btn-sm btn-light d-flex align-items-center mb-2'
            onClick={onClick}
            style={{ border: 'none', gap: '.5rem', padding: '.5rem 1rem', borderRadius: 0, minHeight: 46 }}>
            {icon}
            {title}
          </button>
        })}
      </div>

      <div className='mt-auto d-flex' style={{ margin: '.5rem 1rem' }}>
        <button type="button" className='btn btn-secondary'
          style={{ border: 'none', borderRadius: 6, padding: '.5rem 1rem', background: '#000', flex: 1 }}
          onClick={e => {
            e.stopPropagation();
            setProjectSelector(false)
          }} >Cancel </button>
      </div>
    </div>}
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



    <NewPluginModal
      active={showProjectSelector}
      onNewPlugin={onNewPlugin}
      reloadPlugins={reloadPlugins}
      setProjectSelector={setProjectSelector} />
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
