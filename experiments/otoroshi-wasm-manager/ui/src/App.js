import React from 'react';
import JsZip from 'jszip';
import Pako from 'pako'

import * as Service from './services'
import TabsManager from './TabsManager';

class App extends React.Component {
  state = {
    editorState: undefined,
    plugins: [],
    selectedPlugin: undefined,
    configFiles: []
  }

  componentDidMount() {
    Service.getPlugins()
      .then(plugins => this.setState({ plugins }))
  }

  confirmNewEntity = () => {
    const { editorState, files, plugins } = this.state;

    if (editorState === 'onNewFile') {
      this.setState({
        files: files
          .filter(f => f.new ? f.newFilename?.length > 0 : true)
          .map(f => {
            if (f.new)
              return {
                ...f,
                filename: f.newFilename,
                ext: f.newFilename.split('.')[1],
                new: false
              }
            return f
          })
      })
    } else if (editorState === 'onNewPlugin') {
      const newPlugin = plugins.find(plugin => plugin.new && plugin.newFilename && plugin.newFilename.length > 0)
      if (newPlugin) {
        Service.createPlugin(newPlugin.newFilename)
          .then(res => {
            if (!res.error) {
              this.setState({
                plugins: res.plugins
              })
            }
          })
      }
    }
    this.setState({
      editorState: undefined
    })
  }

  onKeyDown = e => {
    if (e.key === 'Enter') {
      this.confirmNewEntity()
    }
  }

  onClick = e => {
    if ((['onNewFile', 'onNewPlugin'].includes(this.state.editorState)) && e.target.tagName.toUpperCase() !== 'INPUT') {
      this.confirmNewEntity()
    }
  }

  onNewFile = () => {
    this.setState({
      editorState: 'onNewFile',
      files: [
        ...this.state.selectedPlugin.files,
        {
          new: true,
          filename: '',
          ext: '.rs'
        }
      ]
    })
  }

  onNewPlugin = () => {
    this.setState({
      editorState: 'onNewPlugin',
      plugins: [
        ...this.state.plugins,
        {
          new: true,
          filename: ''
        }
      ]
    })
  }

  onFileChange = (file, newFilename) => {
    this.setState({
      files: this.state.selectedPlugin.files.map(f => {
        if (f.filename === file.filename)
          return { ...f, newFilename: newFilename }
        return f
      })
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
              console.log(err)
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
          Service.getPluginTemplate('rust') // TODO - handle Assembly script case
            .then(r => this.downloadPluginTemplate(r, plugin))
        } else {
          this.downloadPluginTemplate(res, plugin)
            .then(() => {
              Service.getPluginConfig(newSelectedPlugin)
                .then(configFiles => {
                  this.setState({
                    configFiles
                  })
                })
            })
        }

      })
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
      .then(res => {
        console.log('saved')
      })
  }

  onBuild = () => {
    Service.savePlugin(this.state.selectedPlugin)
      .then(() => {
        Service.buildPlugin(this.state.selectedPlugin)
          .then(res => {
            console.log('build')
          })
      })
  }

  removePlugin = pluginId => {
    const plugin = this.state.plugins.filter(f => f.pluginId !== pluginId)
    if (window.confirm(`Delete the ${plugin.filename} plugin ?`)) {
      Service.removePlugin(pluginId)
        .then(res => {
          if (res.status === 200)
            this.setState({
              plugins: this.state.plugins.filter(f => f.pluginId !== pluginId),
              selectedPlugin: undefined
            })
        })
    }
  }

  render() {
    const { selectedPlugin, plugins, configFiles } = this.state;

    console.log(selectedPlugin)

    return <div className='d-flex flex-column'
      style={{ flex: 1 }}
      onKeyDown={this.onKeyDown}
      onClick={this.onClick}>
      <TabsManager
        plugins={plugins}
        onFileChange={this.onFileChange}
        onPluginNameChange={this.onPluginNameChange}
        onNewFile={this.onNewFile}
        onNewPlugin={this.onNewPlugin}
        onPluginClick={this.onPluginClick}
        selectedPlugin={selectedPlugin}
        handleContent={this.handleContent}
        onSave={this.onSave}
        onBuild={this.onBuild}
        removePlugin={this.removePlugin}
        configFiles={configFiles}
      />
    </div>
  }
}

export default App;
