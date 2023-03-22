import React from 'react';
import ReactCodeMirror from '@uiw/react-codemirror';
import Select from 'react-select'
import { json } from '@codemirror/lang-json';
import { launchPlugin } from './services'
import { toast } from 'react-toastify'
import Types from './types/index.json'

const rustTypesToJson = name => {
  return Object.fromEntries(Object.entries(Types[name]).map(([k, v]) => {
    if (v.startsWith("Option")) {
      return [k, undefined]
    } else if (v === 'String') {
      return [k, ""]
    } else if (v.startsWith('Vec')) {
      return [k, []]
    } else if (v === 'Value' || v.startsWith('HashMap')) {
      return [k, {}]
    } else if (v === 'bool') {
      return [k, false]
    } else if (v === 'u32') {
      return [k, 0]
    } else if (v === 'bool') {
      return [k, false]
    } else if (Types[v]) {
      return [k, rustTypesToJson(v)]
    } else {
      return [k, v]
    }
  }))
}

export class Run extends React.Component {

  state = {
    selectedPlugin: this.props.selectedPlugin ? { value: this.props.selectedPlugin.pluginId, label: this.props.selectedPlugin.filename } : undefined,
    input: JSON.stringify(rustTypesToJson('EmptyContext'), null, 4),
    functionName: 'execute',
    context: { value: "EmptyContext", label: "EmptyContext" },
    output: ""
  }

  componentDidMount() {
    this.readStateFromLocalStorage();
  }

  componentWillUnmount() {
    this.writeStateInLocalStorage();
  }

  readStateFromLocalStorage = () => {
    const rawState = localStorage.getItem(`${window.location.hostname}-runner`);

    try {
      const jsonState = JSON.parse(rawState);
      this.setState({
        selectedPlugin: jsonState.selectedPlugin || this.state.selectedPlugin,
        input: jsonState.input || JSON.stringify(rustTypesToJson('EmptyContext'), null, 4),
        functionName: jsonState.functionName || 'execute',
        context: jsonState.context || { value: "EmptyContext", label: "EmptyContext" },
        output: jsonState.output || ""
      })
    } catch (_) { }
  }

  writeStateInLocalStorage = () => {
    try {
      localStorage.setItem(`${window.location.hostname}-runner`, JSON.stringify(this.state, null, 4));
    } catch (_) { }
  }

  run = () => {
    const { selectedPlugin, input, functionName } = this.state;
    toast.info("Starting run ...")
    const plugin = this.props.plugins.find(p => p.pluginId === selectedPlugin.value);
    launchPlugin(selectedPlugin.value, input, functionName, plugin?.type)
      .then(res => {
        if (res.error) {
          toast.error(res.error);
          this.setState({
            output: res.error
          })
        } else {
          toast.success('Run done.')
          try {
            this.setState({
              output: JSON.stringify(JSON.parse(res.data), null, 4)
            })
          } catch (err) {
            this.setState({
              output: res.data
            })
          }
        }
      })
  }

  render() {
    const { selectedPlugin, input, functionName, context, output } = this.state;
    const { plugins } = this.props;

    return (
      <div style={{ flex: 1, marginTop: 75 }} className="p-3 bg-light mx-auto w-75"
        onKeyDown={e => e.stopPropagation()}>
        <div className='mb-3'>
          <label htmlFor="selectedPlugin" className='form-label'>Select a plugin</label>
          <Select
            id="selectedPlugin"
            value={selectedPlugin}
            options={plugins
              .filter(plugin => plugin.type !== 'opa')
              .map(plugin => ({ value: plugin.pluginId, label: plugin.filename }))}
            onChange={e => {
              this.setState({
                selectedPlugin: e
              })
            }}
          />
        </div>
        <div className='mb-3'>
          <div className='d-flex align-items-center justify-content-between'>
            <label htmlFor="input" className='form-label'>Select and fill the fake input context</label>
            <div className='w-50'>
              <Select
                value={context}
                options={Object.keys(Types)
                  .filter(t => t.endsWith('Context'))
                  .map(type => ({ value: type, label: type }))}
                onChange={context => this.setState({
                  context,
                  input: JSON.stringify(rustTypesToJson(context.value), null, 4)
                })}
              />
            </div>
          </div>
          <ReactCodeMirror
            id="input"
            value={input}
            extensions={[json()]}
            onChange={input => {
              this.setState({ input })
            }}
          />
        </div>
        <div className='mb-3'>
          <label htmlFor="function" className='form-label'>Select the function to launch</label>
          <input type="text" id="function" className="form-control"
            value={functionName}
            onChange={e => this.setState({ functionName: e.target.value })} />
        </div>
        <div className='mb-3'>
          <button type="button" className='btn btn-success btn-sm' onClick={this.run} disabled={!selectedPlugin}>
            <i className='fas fa-play me-1' />
            Run
          </button>
        </div>
        <div className='mb-3'>
          <label htmlFor="output" className='form-label'>Output</label>
          <ReactCodeMirror
            id="output"
            value={(typeof output === 'string' || output instanceof String) ? output : JSON.stringify(output, null, 4)}
            extensions={[]}
            readOnly={true}
            editable={false}
            basicSetup={{
              lineNumbers: false,
              dropCursor: false
            }}
          />
        </div>
      </div>
    )
  }
}
