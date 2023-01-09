import React from 'react';
import { createPlugin, getPlugins } from './services';
import TabsManager from './TabsManager';

class App extends React.Component {
  state = {
    editorState: undefined,
    files: [
      //   {
      //   filename: 'Cargo.toml',
      //   content: `
      //   [package]
      //   name = "http-plugin"
      //   version = "0.1.0"
      //   edition = "2021"

      //   # See more keys and their definitions at https://doc.rust-lang.org/cargo/reference/manifest.html

      //   [dependencies]
      //   extism-pdk = "0.1.1"
      //   serde = "1.0.152"
      //   serde_json = "1.0.91"

      //   [lib]
      //   crate_type = ["cdylib"]
      //   `.split('\n').map(r => r.trimStart()).join('\n'),
      //   ext: '.toml'
      // },
      // {
      //   filename: 'lib.rs',
      //   ext: '.rs'
      // }
    ],
    plugins: []
  }

  componentDidMount() {
    getPlugins()
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
        createPlugin(newPlugin.newFilename)
          .then(res => {
            if (!res.error) {
              this.setState({
                plugins: plugins
                  .map(f => {
                    if (f.new)
                      return {
                        ...f,
                        filename: f.newFilename,
                        new: false
                      }
                    return f
                  })
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
        ...this.state.files,
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
      files: this.state.files.map(f => {
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

  render() {
    const { files, plugins } = this.state;

    return <div className='d-flex flex-column'
      style={{ flex: 1 }}
      onKeyDown={this.onKeyDown}
      onClick={this.onClick}>
      <TabsManager
        files={files}
        plugins={plugins}
        onFileChange={this.onFileChange}
        onPluginClick={e => console.log(e)}
        onPluginNameChange={this.onPluginNameChange}
        onNewFile={this.onNewFile}
        onNewPlugin={this.onNewPlugin}
      />
    </div>
  }
}

export default App;
