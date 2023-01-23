import React from 'react';
import JsZip from 'jszip';
import Pako from 'pako'

import * as Service from './services'
import TabsManager from './TabsManager';
import { toast } from 'react-toastify';

class App extends React.Component {
  state = {
    editorState: undefined,
    plugins: [],
    selectedPlugin: undefined,
    configFiles: []
  }

  componentDidMount() {
    Service.getPlugins()
      .then(res => {
        if (Array.isArray(res)) {
          this.setState({ plugins: res })
        } else if (res && res.error) {
          toast.error("Not authorized to access to manager", {
            autoClose: false
          })
        }
      })
  }

  confirmNewEntity = () => {
    const { editorState, selectedPlugin, plugins } = this.state;

    if (editorState === 'onNewFile') {
      this.setState({
        selectedPlugin: {
          ...selectedPlugin,
          files: selectedPlugin
            .files
            .filter(f => f.new ? (f.newFilename && f.newFilename.length > 0) : true)
            .map(f => {
              if (f.new)
                return {
                  ...f,
                  filename: f.newFilename,
                  ext: f.newFilename.split('.')[1],
                  content: " ",
                  new: false
                }
              return f
            })
        }
      })
    } else if (editorState === 'onNewPlugin') {
      const newPlugin = plugins.find(plugin => plugin.new && plugin.newFilename && plugin.newFilename.length > 0)
      if (newPlugin) {
        Service.createPlugin(newPlugin.newFilename, newPlugin.type)
          .then(res => {
            if (!res.error) {
              this.setState({
                plugins: res.plugins
              })
            }
          })
      } else {
        this.setState({
          plugins: plugins.filter(f => f.filename.length > 0)
        })
      }
    } else if (editorState === 'onRenamingPlugin') {
      const newPlugin = plugins.find(plugin => plugin.new && plugin.newFilename && plugin.newFilename !== plugin.filename)
      if (newPlugin) {
        Service.updatePlugin(newPlugin.pluginId, newPlugin.newFilename)
          .then(res => {
            if (!res.error) {
              this.setState({
                editorState: undefined,
                plugins: plugins.map(p => {
                  if (p.pluginId === newPlugin.pluginId) {
                    return {
                      ...p,
                      new: false,
                      filename: newPlugin.newFilename
                    }
                  } else {
                    return p
                  }
                })
              }, () => {
                if (selectedPlugin) {
                  this.setState({
                    selectedPlugin: {
                      ...selectedPlugin,
                      filename: newPlugin.newFilename
                    }
                  })
                }
              })
            }
          })
      } else {
        this.setState({
          editorState: undefined
        })
      }
    }
    this.setState({
      editorState: undefined
    })
  }

  onKeyDown = e => {
    const charCode = String.fromCharCode(e.which).toLowerCase();

    if ((e.ctrlKey || e.metaKey) && charCode === 's') {
      if (this.state.selectedPlugin) {
        e.preventDefault();
        this.onSave()
      }
    }
    else if (e.key === 'Enter') {
      e.preventDefault();
      this.confirmNewEntity()
    }
  }

  onClick = e => {
    if ((['onNewFile', 'onNewPlugin', 'onRenamingPlugin'].includes(this.state.editorState)) && e.target.tagName.toUpperCase() !== 'INPUT') {
      this.confirmNewEntity()
    }
  }

  onNewFile = () => {
    this.setState({
      editorState: 'onNewFile',
      selectedPlugin: {
        ...this.state.selectedPlugin,
        files: [
          ...this.state.selectedPlugin.files,
          {
            new: true,
            filename: '',
            ext: '.rs'
          }
        ]
      }
    })
  }

  onNewPlugin = type => {
    this.setState({
      editorState: 'onNewPlugin',
      plugins: [
        ...this.state.plugins,
        {
          new: true,
          filename: '',
          type
        }
      ]
    })
  }

  onFileChange = (idx, newFilename) => {
    this.setState({
      selectedPlugin: {
        ...this.state.selectedPlugin,
        files: this.state.selectedPlugin.files.map((f, i) => {
          if (i === idx)
            return { ...f, newFilename: newFilename }
          return f
        })
      }
    })
  }

  onPluginNameChange = newFilename => {
    this.setState({
      plugins: this.state.plugins.map(f => {
        if (f.new)
          return { ...f, newFilename: newFilename }
        return f
      })
    })
  }

  enablePluginRenaming = pluginId => {
    this.setState({
      editorState: 'onRenamingPlugin',
      plugins: this.state.plugins.map(p => {
        if (p.pluginId === pluginId) {
          return {
            ...p,
            new: true,
            newFilename: p.filename
          }
        } else {
          return p
        }
      })
    }, () => {
      const { selectedPlugin } = this.state;
      if (selectedPlugin && selectedPlugin.pluginId === pluginId) {
        this.setState({
          selectedPlugin: {
            ...selectedPlugin,
            new: true,
            newFilename: selectedPlugin.filename
          }
        })
      }
    })
  }

  downloadPluginTemplate = async (res, plugin) => {
    const jsZip = new JsZip()
    const data = await jsZip.loadAsync(res);
    this.setState({
      selectedPlugin: {
        ...plugin,
        files: Object.values(data.files)
          .filter(f => !f.dir)
          .map(r => {
            const parts = r.name.split('/')
            const name = parts.length > 1 ? parts[1] : parts[0];
            try {
              return {
                filename: name,
                content: Pako.inflateRaw(r._data.compressedContent, { to: 'string' }),
                ext: name.split('.')[1]
              }
            } catch (err) {
              alert(err)
            }
          })
      }
    })
  }

  onPluginClick = newSelectedPlugin => {
    const plugin = this.state.plugins.find(f => f.pluginId === newSelectedPlugin)
    Service.getPlugin(newSelectedPlugin)
      .then(res => {
        if (res.status === 404)
          return res.json()
        else
          return res.blob()
      })
      .then(res => {
        if (res.error && res.status === 404) {
          Service.getPluginTemplate(plugin.type)
            .then(r => this.downloadPluginTemplate(r, plugin))
        } else {
          return this.downloadPluginTemplate(res, plugin)
            .then(() => {
              Service.getPluginConfig(newSelectedPlugin)
                .then(async configFiles => {
                  this.setState({
                    configFiles: (await Promise.all(configFiles.flatMap(this.unzip)))
                      .filter(f => f)
                      .flat()
                  })
                })
            })
        }

      })
  }

  unzip = async file => {
    if (file.ext === 'zip') {
      const jsZip = new JsZip()
      const data = await jsZip.loadAsync(file.content.data);
      return Object.values(data.files)
        .filter(f => !f.dir)
        .map(r => {
          const parts = r.name.split('/') || []
          const name = parts.length > 1 ? parts[1] : parts[0];
          try {
            return {
              filename: name,
              content: r._data.compressedSize > 0 ? Pako.inflateRaw(r._data.compressedContent, { to: 'string' }) : '',
              ext: name.split('.')[1]
            }
          } catch (err) {
            return undefined
          }
        })
    } else {
      return file
    }
  }

  handleContent = (filename, newContent) => {
    const { selectedPlugin } = this.state;
    this.setState({
      selectedPlugin: {
        ...selectedPlugin,
        files: selectedPlugin.files.map(file => {
          if (file.filename === filename) {
            return {
              ...file,
              content: newContent
            }
          } else {
            return file
          }
        })
      }
    })
  }

  onSave = () => {
    Service.savePlugin(this.state.selectedPlugin)
      .then(() => toast.success("Saved!"))
  }

  onBuild = () => {
    Service.savePlugin(this.state.selectedPlugin)
      .then(() => {
        Service.buildPlugin(this.state.selectedPlugin)
          .then(res => {
            if (res.message) {
              toast.info(res.message)
            } else if (res.alreadyExists) {
              toast.warn("Your plugin is already building or already in the queue.");
            } else {
              toast.info("Added build to queue.");
            }
          })
      })
  }

  onDocs = () => {
    this.setState({
      editorState: 'docs'
    })
  }

  onCloseDocumentation = () => {
    this.setState({
      editorState: undefined
    })
  }

  removePlugin = pluginId => {
    const plugin = this.state.plugins.filter(f => f.pluginId !== pluginId)
    if (window.confirm(`Delete the ${plugin.filename} plugin ?`)) {
      Service.removePlugin(pluginId)
        .then(res => {
          if (res.status === 204)
            this.setState({
              plugins: this.state.plugins.filter(f => f.pluginId !== pluginId),
              selectedPlugin: undefined
            })
        })
    }
  }

  render() {
    const { selectedPlugin, plugins, configFiles, editorState } = this.state;

    return <div className='d-flex flex-column'
      style={{ flex: 1, outline: 'none' }}
      onKeyDown={this.onKeyDown}
      onClick={this.onClick}
      tabIndex="0">
      <TabsManager
        editorState={editorState}
        plugins={plugins}
        configFiles={configFiles}
        selectedPlugin={selectedPlugin}
        onFileChange={this.onFileChange}
        onNewFile={this.onNewFile}
        onPluginNameChange={this.onPluginNameChange}
        enablePluginRenaming={this.enablePluginRenaming}
        onNewPlugin={this.onNewPlugin}
        removePlugin={this.removePlugin}
        onPluginClick={this.onPluginClick}
        handleContent={this.handleContent}
        onSave={this.onSave}
        onBuild={this.onBuild}
        onDocs={this.onDocs}
        onCloseDocumentation={this.onCloseDocumentation}
      />
    </div>
  }
}

export default App;
