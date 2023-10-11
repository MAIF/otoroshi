import React, { useEffect, useState } from 'react'
import FileManager from './FileManager';
import Tab from './Tab'
import PluginManager from './PluginManager'
import Terminal from './Terminal';
import { Run } from './Run';
import { PublishView } from './PublishView';
import { TabsHeader } from './TabsHeader';
import { Sidebar, SidebarContext } from './Sidebar';
import { LOGOS } from './FilesLogo';

// close the publisher when selecteing other plugins

function TabsManager({ plugins, ...props }) {
  const [tabs, setTabs] = useState([])
  const [currentTab, setCurrentTab] = useState()
  const [sizeTerminal, changeTerminalSize] = useState(!props.selectedPlugin ? 0 : .3)
  const [resizingTerminal, toggleResizingTerminal] = useState(false)
  const [sidebarContext, setSidebarContext] = useState({
    open: false,
    ButtonWhenHidden: () => null
  })

  const [currentPlugin, setCurrentPlugin] = useState()

  useEffect(() => {
    if (props.selectedPlugin && props.selectedPlugin.filename !== currentPlugin) {
      setCurrentPlugin(props.selectedPlugin.filename)
      setTabs(tabs.filter(t => t === 'Runner' || t === 'Publish'))
    }
  }, [props.selectedPlugin])

  const setTab = (tab) => {
    if (!tabs.includes(tab)) {
      setTabs([...tabs, tab]);
    }
    setCurrentTab(tab);
  }

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
        changeTerminalSize(r > .75 ? .75 : r)
      }
    }}
  >
    <SidebarContext.Provider value={sidebarContext}>
      <>
        <Sidebar setContext={setSidebarContext} context={sidebarContext}>
          <SidebarHeader version={props.version} />
          <SidebarContext.Consumer>
            {({ open, ButtonWhenHidden }) => !open ? ButtonWhenHidden() : <>
              <PluginManager
                plugins={plugins}
                setSelectedPlugin={props.setSelectedPlugin}
                selectedPlugin={props.selectedPlugin}
                reloadPlugins={props.reloadPlugins}
                onPluginClick={props.onPluginClick}
                onNewPlugin={props.onNewPlugin}
                setFilename={props.onPluginNameChange}
                removePlugin={props.removePlugin}
                enablePluginRenaming={props.enablePluginRenaming}
              />
              {props.selectedPlugin && <FileManager
                removeFile={props.removeFile}
                currentTab={currentTab}
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

              {open && <button type="button" className='d-flex align-items-center justify-content-center py-3' style={{
                background: '#ddd',
                border: 'none'
              }} onClick={() => setSidebarContext({ ...sidebarContext, open: false })}>
                <i className="fa fa-chevron-left" />
              </button>}

              {props.selectedPlugin && <button type="button" className='btn btn-outline-danger m-3 my-2' style={{ fontSize: '.8rem' }}
                onClick={props.removePlugin}>
                Remove {props.selectedPlugin.filename}
              </button>}
            </>}
          </SidebarContext.Consumer>
        </Sidebar>

        <SidebarContext.Consumer>
          {({ open, sidebarSize }) => (
            <div
              style={{ flex: 1, height: '100vh', position: 'relative', marginLeft: open ? `${sidebarSize}px` : '52px' }}
              className="d-flex flex-column">
              <div className='d-flex flex-column scroll-container' style={{ flex: 1 - sizeTerminal, overflow: 'scroll' }}>
                <TabsHeader
                  {...props}
                  showPlaySettings={() => {
                    setTab('Runner');
                    props.showPlaySettings();
                  }}
                  showPublishSettings={() => {
                    setTab('Publish');
                    props.showPublishSettings();
                  }}>
                  <Tabs
                    tabs={tabs}
                    configFiles={props.configFiles}
                    selectedPlugin={props.selectedPlugin}
                    setCurrentTab={setCurrentTab}
                    setTabs={setTabs}
                    currentTab={currentTab} />
                </TabsHeader>
                {currentTab === 'Runner' &&
                  <Run
                    onClose={props.onEditorStateReset}
                    plugins={plugins}
                    selectedPlugin={props.selectedPlugin} />}
                {currentTab === 'Publish' &&
                  <PublishView
                    onClose={props.onEditorStateReset}
                    plugins={plugins}
                    selectedPlugin={props.selectedPlugin}
                    createManifest={props.createManifest}
                    createReadme={props.createReadme}
                    openTab={setTab}
                    publish={props.publish}
                  />}
                {props.selectedPlugin ? <Contents
                  tabs={tabs}
                  configFiles={props.configFiles}
                  selectedPlugin={props.selectedPlugin}
                  handleContent={newContent => props.handleContent(currentTab, newContent)}
                  setCurrentTab={setCurrentTab}
                  currentTab={currentTab} /> : null}
              </div>
              {props.selectedPlugin && <Terminal
                selectedPlugin={props.selectedPlugin}
                sizeTerminal={sizeTerminal}
                changeTerminalSize={changeTerminalSize}
                toggleResizingTerminal={toggleResizingTerminal}
                onLoadConfigurationFile={props.onLoadConfigurationFile}
                configFiles={props.configFiles}
              />}
            </div>
          )}
        </SidebarContext.Consumer>
      </>
    </SidebarContext.Provider>
  </div>
}

function SidebarHeader({ version }) {
  return <SidebarContext.Consumer>
    {({ open }) => open ? <h1 style={{
      userSelect: 'none',
      fontWeight: 'bold',
      textTransform: 'uppercase',
      background: '#f9b000',
      color: 'white',
      height: 42
    }} className="p-2 m-0 d-flex align-items-center">Wasmo <span style={{ fontSize: '1rem', marginTop: 'auto' }} className="ms-auto">{version}</span></h1> : <div className='d-flex justify-content-center p-1'>
      {LOGOS.logo}
    </div>
    }
  </SidebarContext.Consumer>
}

function Tabs({ tabs, setCurrentTab, setTabs, currentTab, selectedPlugin, configFiles }) {
  return <div style={{ height: 42 }}>
    {tabs
      .filter(tab => {
        if (tab === 'Runner') {
          return true;
        } else if (tab === 'Publish') {
          return true;
        } else {
          return [...(selectedPlugin ? selectedPlugin.files : []), ...configFiles].find(f => f.filename === tab);
        }
      })
      .map(tab => {
        return <TabButton
          key={tab}
          filename={tab}
          onClick={() => setCurrentTab(tab)}
          closeTab={filename => {
            const newTabs = tabs.filter(t => t !== filename)
            setTabs(newTabs)
            if (currentTab === filename && newTabs.length > 0) {
              setCurrentTab(tabs[0])
            } else if (newTabs.length === 0) {
              setCurrentTab(undefined)
            }
          }}
          selected={currentTab ? tab === currentTab : false} />
      })}
  </div>
}

function Contents({ tabs, setCurrentTab, currentTab, handleContent, selectedPlugin, configFiles }) {
  return <div style={{ flex: 1, marginTop: 42, display: 'flex', flexDirection: 'column', position: 'relative' }}>
    {tabs
      .filter(tab => [...selectedPlugin.files, ...configFiles].find(f => f.filename === tab))
      .map(tab => {
        const plugin = [...selectedPlugin.files, ...configFiles].find(f => f.filename === tab)
        if (plugin)
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
    className={`p-2 px-3`}
    style={{
      border: 'none',
      userSelect: 'none',
      background: selected ? '#fff' : 'rgba(var(--bs-light-rgb),var(--bs-bg-opacity))',
      borderTop: selected ? '3px solid #f9b000' : 'none'
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