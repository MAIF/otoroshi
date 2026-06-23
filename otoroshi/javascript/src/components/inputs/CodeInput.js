import React, { Component, useState } from 'react';
import { Help } from './Help';
import isFunction from 'lodash/isFunction';
import isEqual from 'lodash/isEqual';

import MonacoEditor from '@monaco-editor/react';

const MODE_TO_LANGUAGE = {
  json: 'json',
  javascript: 'javascript',
  js: 'javascript',
  scala: 'scala',
  graphqlschema: 'graphql',
  graphql: 'graphql',
  html: 'html',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml',
  markdown: 'markdown',
  // not supported natively by monaco, fall back to plain text
  prolog: 'plaintext',
  text: 'plaintext',
};

function toMonacoLanguage(mode) {
  if (!mode) return 'javascript';
  return MODE_TO_LANGUAGE[mode] || mode;
}

function toMarkerSeverity(monaco, type) {
  switch (type) {
    case 'error':
      return monaco.MarkerSeverity.Error;
    case 'warning':
      return monaco.MarkerSeverity.Warning;
    case 'info':
      return monaco.MarkerSeverity.Info;
    default:
      return monaco.MarkerSeverity.Hint;
  }
}

export class JsonObjectAsCodeInput extends Component {
  render() {
    return (
      <CodeInput
        {...this.props}
        mode="json"
        value={JSON.stringify(this.props.value, null, 2)}
        onChange={(e) => {
          try {
            this.props.onChange(JSON.parse(e));
          } catch (ex) {}
        }}
      />
    );
  }
}

export class JsonObjectAsCodeInputUpdatable extends Component {
  state = {
    value: this.props.value,
  };

  componentDidUpdate(prevProps, prevState, snapshot) {
    if (prevProps.value !== this.props.value) {
      this.setState({ value: this.props.value });
    }
  }

  render() {
    return (
      <CodeInput
        {...this.props}
        mode="json"
        value={JSON.stringify(this.state.value, null, 2)}
        onChange={(e) => {
          try {
            const value = JSON.parse(e);
            this.setState({ value }, () => {
              this.props.onChange(value);
            });
          } catch (ex) {}
        }}
      />
    );
  }
}

export default class CodeInput extends Component {
  static Toggle = (props) => {
    const [display, setDisplay] = useState(true);
    if (display) return <CodeInput {...props} />;
    return (
      <div className="row mb-3">
        <label htmlFor={`input-${props.label}`} className="col-sm-2 col-form-label">
          {props.label} <Help text={props.help} />
        </label>
        <div className="col-sm-10">
          <button type="button" className="btn btn-default" onClick={(e) => setDisplay(!display)}>
            Display
          </button>
        </div>
      </div>
    );
  };

  state = {
    value: null,
    mounted: true,
    theme: document.body.classList.contains('white-mode') ? 'vs' : 'vs-dark',
    contentHeight: null,
  };

  componentDidMount() {
    this.observer = new MutationObserver(this.listenWhiteMode);
    this.observer.observe(document.body, {
      attributes: true,
    });
  }

  componentDidUpdate(prevProps) {
    const externalChanged = !this.sameLogicalValue(prevProps.value, this.props.value);
    if (externalChanged && !this.sameLogicalValue(this.props.value, this.currentEditorValue())) {
      this.setState({ value: this.props.value });
    }
    this.applyAnnotations();
  }

  currentEditorValue = () => (this.state.value != null ? this.state.value : this.props.value);

  sameLogicalValue = (a, b) => {
    if (a === b) return true;
    if (this.props.mode === 'json' && typeof a === 'string' && typeof b === 'string') {
      try {
        return isEqual(JSON.parse(a), JSON.parse(b));
      } catch (ex) {
        return false;
      }
    }
    return false;
  };

  componentWillUnmount() {
    if (this.observer) this.observer.disconnect();
    this.setState({
      mounted: false,
    });
  }

  listenWhiteMode = (mutationList) => {
    mutationList.forEach((mutation) => {
      if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
        if (this.state.mounted)
          this.setState({
            theme: mutation.target.classList.contains('white-mode') ? 'vs' : 'vs-dark',
          });
      }
    });
  };

  onChange = (e) => {
    let clean_source = e === undefined || e === null ? '' : e;
    if (this.props.mode === 'json') {
      clean_source = clean_source.replace('}{}', '}');

      if (clean_source.length === 0) {
        this.props.onChange('{}');
        return;
      }

      try {
        JSON.parse(clean_source);
        this.setState({ value: clean_source }, () => {
          this.props.onChange(clean_source);
        });
      } catch (ex) {
        if (clean_source.trim() === '') {
          this.setState({ value: '{}' });
        } else if (clean_source.indexOf('}{}') > -1) {
          this.setState({ value: clean_source.replace('}{}', '}') });
        } else {
          this.setState({ value: clean_source });
        }
      }
    } else {
      this.setState({ value: clean_source });
      this.props.onChange(clean_source);
    }
  };

  isAutoHeight = () => {
    const aceConfig = this.props.ace_config || {};
    return aceConfig.maxLines !== undefined;
  };

  maxHeightPx = () => {
    const aceConfig = this.props.ace_config || {};
    if (aceConfig.maxLines === undefined || aceConfig.maxLines === Infinity) {
      return Infinity;
    }
    const fontSize = aceConfig.fontSize || 14;
    return aceConfig.maxLines * Math.round(fontSize * 1.5);
  };

  applyAnnotations = () => {
    if (!this.editor || !this.monaco) return;
    const model = this.editor.getModel();
    if (!model) return;
    const ann = this.props.annotations;
    const list = ann ? (isFunction(ann) ? ann() : ann) : [];
    const markers = (list || []).map((a) => ({
      startLineNumber: (a.row || 0) + 1,
      startColumn: (a.column || 0) + 1,
      endLineNumber: (a.row || 0) + 1,
      endColumn: (a.column || 0) + 2,
      message: a.text || '',
      severity: toMarkerSeverity(this.monaco, a.type),
    }));
    this.monaco.editor.setModelMarkers(model, 'codeinput', markers);
  };

  handleEditorMount = (editor, monaco) => {
    this.editor = editor;
    this.monaco = monaco;

    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      if (this.props.saveAndCompile) {
        this.props.saveAndCompile();
      }
    });

    this.applyAnnotations();

    if (this.isAutoHeight()) {
      const updateHeight = () => {
        if (!this.state.mounted) return;
        const height = Math.min(this.maxHeightPx(), editor.getContentHeight());
        this.setState({ contentHeight: height });
      };
      editor.onDidContentSizeChange(updateHeight);
      updateHeight();
    }
  };

  getOptions = () => {
    const aceConfig = this.props.ace_config || {};
    const showGutter = this.props.showGutter !== undefined ? this.props.showGutter : true;
    return {
      automaticLayout: true,
      selectOnLineNumbers: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      lineNumbers: showGutter ? 'on' : 'off',
      glyphMargin: false,
      folding: true,
      lineDecorationsWidth: 0,
      lineNumbersMinChars: showGutter ? 3 : 0,
      tabSize: 2,
      fontSize: aceConfig.fontSize || 14,
      readOnly: this.props.readOnly || aceConfig.readOnly || false,
      ...(aceConfig.onLoad ? { padding: { top: 10, bottom: 10 } } : {}),
      ...(this.props.monaco_options || {}),
    };
  };

  render() {
    let code = this.state.value || this.props.value;

    if (this.props.mode === 'json' && typeof code !== 'string') {
      code = JSON.stringify(code, null, 2);
    }
    // avoid to send a json object to the editor
    if (typeof code === 'object' && code !== null) {
      code = JSON.stringify(code, null, 2);
    }

    if (code !== undefined && code !== null && !isNaN(code)) code = code + '';
    if (code === undefined || code === null) code = '';

    const language = toMonacoLanguage(this.props.mode);

    let height = this.props.height || '300px';
    if (this.isAutoHeight() && this.state.contentHeight) {
      height = `${this.state.contentHeight}px`;
    }

    const editor = (
      <MonacoEditor
        language={language}
        theme={this.state.theme}
        value={code}
        onChange={this.onChange}
        onMount={this.handleEditorMount}
        className={this.props.className || ''}
        height={height}
        width={this.props.width || '100%'}
        options={this.getOptions()}
      />
    );

    if (this.props.editorOnly) {
      return editor;
    }

    return (
      <div className="row mb-3">
        {!this.props.hideLabel && (
          <label htmlFor={`input-${this.props.label}`} className="col-sm-2 col-form-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
        )}

        <div className={this.props.hideLabel ? 'col-sm-12' : 'col-sm-10'}>
          {this.props.example ? (
            <div className="row">
              <div className="col-sm-8">{editor}</div>
              <div className="col-sm-4" style={{ paddingLeft: 0 }}>
                <div
                  style={{
                    height: '30px',
                    textAlign: 'center',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    backgroundColor: 'var(--color-primary)',
                    color: '#fff',
                    fontStyle: 'italic',
                  }}
                >
                  Example
                </div>
                <MonacoEditor
                  language="json"
                  theme={this.state.theme}
                  value={JSON.stringify(this.props.example, null, 2)}
                  height={this.props.height || '270px'}
                  width="100%"
                  options={{
                    ...this.getOptions(),
                    readOnly: true,
                    lineNumbers: 'off',
                    lineNumbersMinChars: 0,
                  }}
                />
              </div>
            </div>
          ) : (
            editor
          )}
        </div>
      </div>
    );
  }
}
