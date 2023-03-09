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
    this.reloadPlugins()
  }

  reloadPlugins = () => {
    Service.getPlugins()
      .then(res => {
        if (Array.isArray(res)) {
          this.setState({ plugins: res })
        } else if (res && res.error) {
          toast.error("You're not authorized to access to manager", {
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
      selectedPlugin: undefined,
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

  downloadPluginTemplate = (res, plugin) => {
    const jsZip = new JsZip()
    return new Promise(resolve => {
      jsZip.loadAsync(res)
        .then(data => {
          this.setState({
            selectedPlugin: {
              ...plugin,
              files: Object.values(data.files)
                .filter(f => !f.dir && f.name.split('/').length <= 2)
                .map(r => {
                  const parts = r.name.split('/');
                  const name = parts.length > 1 ? parts[parts.length - 1] : parts[0];
                  try {
                    return {
                      filename: name,
                      content: Pako.inflateRaw(r._data.compressedContent, { to: 'string' }),
                      ext: name.split('.')[1]
                    }
                  } catch (err) {
                    console.log(err)
                    console.log(`Can't read ${name} file`)
                    return undefined
                  }
                })
                .filter(_ => _)
            }
          }, resolve)
        })
    })
  }

  fetchedTypeToState = (filename, file) => {
    return new Promise(resolve => {
      new File([file], "")
        .text()
        .then(content => {
          this.setState({
            selectedPlugin: {
              ...this.state.selectedPlugin,
              files: [
                ...this.state.selectedPlugin.files,
                { filename, content, ext: filename.split('.')[1] }
              ]
            }
          }, resolve);
        });
    });
  }

  onPluginClick = newSelectedPlugin => {
    this.setState({
      configFiles: [],
      selectedPlugin: undefined
    }, () => {
      const plugin = this.state.plugins.find(f => f.pluginId === newSelectedPlugin)

      if (plugin.type === "github") {
        const { filename, owner, ref } = plugin;
        Service.getGithubSources(filename, owner, ref, plugin.private)
          .then(res => res.blob())
          .then(r => this.downloadPluginTemplate(r, plugin))
          .then(() => {
            Service.getPluginConfig(newSelectedPlugin)
              .then(async configFiles => {
                this.setState({
                  configFiles: (await Promise.all(configFiles.flatMap(this.unzip)))
                    .filter(f => f)
                    .flat()
                })
              });
          })
      } else {
        Service.getPlugin(newSelectedPlugin)
          .then(res => {
            if (res.status === 404)
              return res.json()
            else
              return res.blob()
          })
          .then(res => {
            // first case match the creation of a new plugin
            if (res.error && res.status === 404) {
              const fetchTypesNeeded = ['ts', 'rust'].includes(plugin.type);
              const fetchHostFunctionNeeded = ['go'].includes(plugin.type);
              Promise.all([
                Service.getPluginTemplate(plugin.type),
                fetchTypesNeeded ? Service.getPluginTypes(plugin.type) : Promise.resolve({ status: 200 }),
                fetchHostFunctionNeeded ? Service.getPluginHostFunctions(plugin.type) : Promise.resolve({ status: 200 })
              ])
                .then(([template, types, hostFunctions]) => {
                  if (template.status !== 200) {
                    template.json().then(window.alert)
                  } else if (types.status !== 200) {
                    types.json().then(window.alert)
                  } else if (hostFunctions.status !== 200) {
                    hostFunctions.json().then(window.alert)
                  } else {
                    Promise.all([
                      template.blob(),
                      fetchTypesNeeded ? types.blob() : Promise.resolve(),
                      fetchHostFunctionNeeded ? hostFunctions.blob() : Promise.resolve()
                    ])
                      .then(([templatesFiles, typesFile, hostFunctionsFile]) => {
                        this.downloadPluginTemplate(templatesFiles, plugin)
                          .then(() => {
                            const type = plugin.type === "rust" ? 'rs' : plugin.type;
                            if (fetchHostFunctionNeeded && fetchTypesNeeded) {
                              const filename = `types.${type}`;
                              const hostFunctionsFilename = `host-functions.${type}`;
                              this.fetchedTypeToState(hostFunctionsFilename, hostFunctionsFile)
                                .then(() => this.fetchedTypeToState(filename, typesFile));
                            } else if (fetchTypesNeeded) {
                              this.fetchedTypeToState(`types.${type}`, typesFile);
                            } else if (fetchHostFunctionNeeded) {
                              this.fetchedTypeToState(`host-functions.${type}`, hostFunctionsFile);
                            }
                          });
                      });
                  }
                })
            } else {
              return this.downloadPluginTemplate(res, plugin)
                .then(() => {
                  Service.getPluginConfig(newSelectedPlugin)
                    .then(configFiles => Promise.all(configFiles.flatMap(this.unzip)))
                    .then(configFiles => {
                      this.setState({
                        configFiles: configFiles.filter(f => f).flat()
                      })
                    });
                });
            }
          });
      }
    });
  }

  onLoadConfigurationFile = () => {
    Service.getPluginConfig(this.state.selectedPlugin.pluginId)
      .then(async configFiles => {
        this.setState({
          configFiles: (await Promise.all(configFiles.flatMap(this.unzip)))
            .filter(f => f)
            .flat()
        })
      });
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

  getPluginType = () => {
    if (this.state.selectedPlugin.type === 'github') {
      const isRustPlugin = this.state.selectedPlugin.files
        .find(f => f.filename === "Cargo.toml");
      const isGoPlugin = this.state.selectedPlugin.files
        .find(f => f.filename === "go.mod");
      const isTsPlugin = this.state.selectedPlugin.files
        .find(f => f.filename.endsWith('.ts'));

      return isRustPlugin ? 'rust' : isGoPlugin ? 'go' : isTsPlugin ? 'ts' : 'js';
    } else
      return this.state.selectedPlugin.type;
  }

  onBuild = () => {
    Service.savePlugin(this.state.selectedPlugin)
      .then(() => {
        Service.buildPlugin(this.state.selectedPlugin, this.getPluginType())
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

  onEditorStateReset = () => {
    this.setState({
      editorState: undefined
    })
  }

  showPlaySettings = () => {
    this.setState({
      editorState: 'play',
    })
  }

  showPublishSettings = () => {
    this.setState({
      editorState: 'publish',
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

  removeFile = filename => {
    if (window.confirm(`Delete the ${filename} file ?`)) {
      this.setState({
        selectedPlugin: {
          ...this.state.selectedPlugin,
          files: this.state.selectedPlugin.files.filter(file => file.filename !== filename)
        }
      }, this.onSave)
    }
  }

  setSelectedPlugin = selectedPlugin => {
    this.setState({ selectedPlugin });
  }

  createManifest = () => {
    if (!this.state.selectedPlugin.files
      .find(f => f.filename === "wapm.toml")) {
      Service.getWapmManifest()
        .then(r => r.blob())
        .then(file => new File([file], "").text())
        .then(content => {
          this.setState({
            selectedPlugin: {
              ...this.state.selectedPlugin,
              files: [
                ...this.state.selectedPlugin.files,
                {
                  filename: 'wapm.toml',
                  content,
                  ext: 'toml'
                }
              ]
            }
          })
        })
    }
  }

  createReadme = () => {
    if (!this.state.selectedPlugin.files
      .find(f => f.filename === "README.md")) {
      this.setState({
        selectedPlugin: {
          ...this.state.selectedPlugin,
          files: [
            ...this.state.selectedPlugin.files,
            {
              filename: 'README.md',
              content: `# Your package description`,
              ext: 'md'
            }
          ]
        }
      })
    }
  }

  publish = () => {
    const { selectedPlugin } = this.state;
    Service.savePlugin(selectedPlugin)
      .then(() => Service.publishPlugin(selectedPlugin))
      .then(res => {
        if (res.error) {
          toast.error(res.error)
        } else if (res.alreadyExists) {
          toast.warn("Your plugin is already publishing or already in the queue.");
        } else {
          toast.info(res.message);
        }
      })
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
        reloadPlugins={this.reloadPlugins}
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
        onEditorStateReset={this.onEditorStateReset}
        showPlaySettings={this.showPlaySettings}
        showPublishSettings={this.showPublishSettings}
        removeFile={this.removeFile}
        onLoadConfigurationFile={this.onLoadConfigurationFile}
        setSelectedPlugin={this.setSelectedPlugin}
        createManifest={this.createManifest}
        createReadme={this.createReadme}
        publish={this.publish}
      />
    </div>
  }
}

export default App;
