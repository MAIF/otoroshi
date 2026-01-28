import MonacoEditor from '@monaco-editor/react';
import React, { Component } from 'react';
import isEqual from 'lodash/isEqual';

window.__support_monaco_input = window.__support_monaco_input || true;

export class MonacoInput extends Component {
  state = {
    value: null,
    mounted: true,
  };

  componentDidMount() {
    this.setState({
      mounted: true,
    });
  }

  componentDidUpdate(prevProps) {
    if (!isEqual(prevProps.value, this.props.value)) {
      this.setState({ value: this.props.value });
    }
  }

  componentWillUnmount() {
    this.setState({
      mounted: false,
    });
  }

  onChange = (value) => {
    // if (e && e.preventDefault) e.preventDefault();
    this.setState({ value });
    this.props.onChange(value);
  };

  render() {
    let code = this.state.value || this.props.value;
    console.log(typeof code);
    console.log(code);

    const options = {
      automaticLayout: true,
      selectOnLineNumbers: true,
      minimap: { enabled: false },
      lineNumbers: 'on',
      glyphMargin: false,
      folding: true,
      lineDecorationsWidth: 0,
      lineNumbersMinChars: 0,
      ...(this.props.monaco_options || {}),
      ...(this.props.monaco_config || {}),
    };

    const isFullscreen = false;

    const editor = (
      <MonacoEditor
        height={isFullscreen ? '100%' : this.props.height}
        width="100%"
        theme="vs-dark"
        defaultLanguage={this.props.language || 'javascript'}
        value={code}
        options={options}
        onChange={(newValue) => {
          this.props.onChange(newValue);
        }}
      />
    );

    if (this.props.editorOnly) {
      return editor;
    }

    return (
      <div className="row mb-3">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 col-form-label">
          {this.props.label}
        </label>
        <div className="col-sm-10">{editor}</div>
      </div>
    );
  }
}
