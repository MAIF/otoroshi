import React, { useState } from 'react'
import FileManager from './FileManager';
import Tab from './Tab'
import PluginManager from './PluginManager'
import Terminal from './Terminal';

function TabsManager({ plugins, ...props }) {
  const [tabs, setTabs] = useState([])
  const [currentTab, setCurrentTab] = useState()
  const [sizeTerminal, changeTerminalSize] = useState(.3)
  const [resizingTerminal, toggleResizingTerminal] = useState(false)

  return <div className='d-flex' style={{ flex: 1 }}
    onMouseLeave={e => {
      if (resizingTerminal) {
        e.stopPropagation()
        toggleResizingTerminal(false)
      }
    }}
    onMouseUp={e => {
      if (resizingTerminal) {
        e.stopPropagation()
        toggleResizingTerminal(false)
      }
    }}
    onMouseMove={e => {
      if (resizingTerminal) {
        e.stopPropagation()
        const r = 1 - (e.clientY / window.innerHeight)
        changeTerminalSize(r < .2 ? .2 : r > .75 ? .75 : r)
      }
    }}
  >
    <div className='d-flex flex-column' style={{ background: 'rgb(228,229,230)' }}>
      <h1 style={{ fontWeight: 'bold', textTransform: 'uppercase', fontSize: 18 }} className="p-2 m-0">Wasm Manager</h1>
      <Explorer>
        <PluginManager
          plugins={plugins}
          onPluginClick={props.onPluginClick}
          onNewPlugin={props.onNewPlugin}
          setFilename={props.onPluginNameChange}
          removePlugin={props.removePlugin}
          enablePluginRenaming={props.enablePluginRenaming}
        />
      </Explorer>
      {props.selectedPlugin && <FileManager
        selectedPlugin={props.selectedPlugin}
        files={props.selectedPlugin.files}
        configFiles={props.configFiles}
        onNewFile={props.onNewFile}
        onFileChange={props.onFileChange}
        onFileClick={file => {
          if (!tabs.find(f => f === file.filename))
            setTabs([...tabs, file.filename]);

          setCurrentTab(file.filename)
        }} />}
    </div>

    <div style={{ flex: 1, height: '100vh' }} className="d-flex flex-column">
      <div className='d-flex flex-column' style={{ flex: 1 - sizeTerminal, overflow: 'scroll' }}>
        <Header onSave={props.onSave} onBuild={props.onBuild} showActions={!!props.selectedPlugin}>
          <Tabs
            tabs={tabs}
            setCurrentTab={setCurrentTab}
            setTabs={setTabs}
            currentTab={currentTab} />
        </Header>
        {props.selectedPlugin && <Contents
          tabs={tabs}
          configFiles={props.configFiles}
          selectedPlugin={props.selectedPlugin}
          handleContent={newContent => props.handleContent(currentTab, newContent)}
          setCurrentTab={setCurrentTab}
          currentTab={currentTab} />}
      </div>
      <Terminal
        selectedPlugin={props.selectedPlugin}
        sizeTerminal={sizeTerminal}
        changeTerminalSize={changeTerminalSize}
        toggleResizingTerminal={toggleResizingTerminal} />
    </div>
  </div>
}

function Explorer({ children }) {
  const [show, setShow] = useState(true)
  return <>
    <div className='px-2 py-1 d-flex justify-content-between align-items-center'
      style={{
        background: 'rgb(228, 229, 230)'
      }} onClick={() => setShow(!show)}>
      <div className='d-flex align-items-center'>
        <i className={`fas fa-chevron-${show ? 'down' : 'right'} fa-sm me-1`} />
        <span className='fw-bold'>Explorer</span>
      </div>
    </div>
    {show && children}
  </>
}

function Tabs({ tabs, setCurrentTab, setTabs, currentTab }) {
  return <div style={{ height: 42 }}>
    {tabs.map(tab => {
      return <TabButton
        filename={tab}
        onClick={() => setCurrentTab(tab)}
        closeTab={filename => {
          setTabs(tabs.filter(t => t !== filename))
          if (currentTab === filename && tabs.find(f => f === filename))
            setCurrentTab(tabs[0])
        }}
        selected={currentTab ? tab === currentTab : false} />
    })}
  </div>
}

function Contents({ tabs, setCurrentTab, currentTab, handleContent, selectedPlugin, configFiles }) {
  return <div style={{ flex: 1, marginTop: 42 }}>
    {tabs.map(tab => {
      const plugin = [...selectedPlugin.files, ...configFiles].find(f => f.filename === tab)
      return <Tab
        {...plugin}
        handleContent={handleContent}
        key={tab}
        selected={currentTab ? tab === currentTab : false}
        setCurrentTab={filename => {
          setCurrentTab(filename)
        }}
      />
    })}
  </div>
}

function TabButton({ filename, onClick, selected, closeTab }) {
  return <button type="button"
    className={`p-2 px-3 ${selected ? 'bg-light' : ''}`}
    style={{
      border: 'none',
      borderTop: selected ? '1px solid #f9b000' : 'none'
    }}
    onClick={onClick}>
    {filename}
    <i className='fa fa-times fa-sm ms-1' onClick={e => {
      e.stopPropagation();
      closeTab(filename)
    }} />
  </button>
}

function Header({ children, onSave, onBuild, showActions }) {

  return <div className='d-flex align-items-center justify-content-between bg-light'
    style={{ position: 'fixed', height: 42, zIndex: 10, width: 'calc(100vw - 250px)' }}>
    {children}

    {showActions && <div className='d-flex align-items-center'>
      <Save onSave={onSave} />
      <Build onBuild={onBuild} />
    </div>}
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
    className="pe-3"
    onClick={onBuild}>
    <i className='fas fa-hammer' />
  </button>
}


export default TabsManager