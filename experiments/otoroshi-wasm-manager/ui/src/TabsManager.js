import React, { useState } from 'react'
import FileManager from './FileManager';
import Tab from './Tab'
import PluginManager from './PluginManager'
import Terminal from './Terminal';

function TabsManager({ files, plugins, ...props }) {
  const [tabs, setTabs] = useState([])
  const [currentTab, setCurrentTab] = useState()
  const [showTerminal, toggleTerminalVisibility] = useState(true)

  return <div className='d-flex' style={{ flex: 1 }}>

    <div className='d-flex flex-column' style={{ background: 'rgb(228,229,230)' }}>
      <h1 style={{ fontWeight: 'bold', textTransform: 'uppercase', fontSize: 18 }} className="p-2 m-0">Wasm Manager</h1>
      <PluginManager
        plugins={plugins}
        onPluginClick={props.onPluginClick}
        onNewPlugin={props.onNewPlugin}
        setFilename={props.onPluginNameChange}
      />
      <FileManager
        files={files}
        onNewFile={props.onNewFile}
        onFileChange={props.onFileChange}
        onFileClick={file => {
          if (!tabs.find(f => f.filename === file.filename))
            setTabs([...tabs, file]);

          setCurrentTab(file.filename)
        }} />
    </div>

    <div style={{ flex: 1, height: '100vh' }} className="d-flex flex-column">
      <div className='d-flex flex-column' style={{ flex: showTerminal ? .7 : 1, overflow: 'scroll' }}>
        <Tabs
          tabs={tabs}
          setCurrentTab={setCurrentTab}
          setTabs={setTabs}
          currentTab={currentTab} />
        <Contents
          tabs={tabs}
          setCurrentTab={setCurrentTab}
          currentTab={currentTab} />
      </div>
      <Terminal
        showTerminal={showTerminal}
        toggleTerminalVisibility={toggleTerminalVisibility} />
    </div>
  </div>
}

function Tabs({ tabs, setCurrentTab, setTabs, currentTab }) {
  return <div style={{ height: 42 }}>
    {tabs.map(tab => {
      return <TabButton filename={tab.filename}
        onClick={() => setCurrentTab(tab.filename)}
        closeTab={filename => {
          setTabs(tabs.filter(t => t.filename !== filename))
          if (currentTab === filename && tabs.find(f => f.filename === filename))
            setCurrentTab(tabs[0].filename)
        }}
        selected={currentTab ? tab.filename === currentTab : false} />
    })}
  </div>
}

function Contents({ tabs, setCurrentTab, currentTab }) {
  return <div style={{ flex: 1 }}>
    {tabs.map(tab => {
      return <Tab {...tab}
        key={tab.filename}
        selected={currentTab ? tab.filename === currentTab : false}
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

export default TabsManager