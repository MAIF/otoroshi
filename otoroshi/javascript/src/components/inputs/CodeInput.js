import React, { Component, useState } from 'react';
import { Help } from './Help';
import isFunction from 'lodash/isFunction';

import AceEditor from 'react-ace';

import 'ace-builds/webpack-resolver';

import 'ace-builds/src-noconflict/mode-scala';
import 'ace-builds/src-noconflict/mode-javascript';
import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/mode-graphqlschema';
import 'ace-builds/src-noconflict/mode-html';
import 'ace-builds/src-noconflict/mode-xml';

import 'ace-builds/src-noconflict/ext-language_tools';
import 'ace-builds/src-noconflict/ext-searchbox';

import 'ace-builds/src-noconflict/theme-monokai';
import 'ace-builds/src-noconflict/theme-xcode';
import isEqual from 'lodash/isEqual';

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
          } catch (ex) { }
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
    theme: document.body.classList.contains('white-mode') ? 'xcode' : 'monokai',
  };

  componentDidMount() {
    this.listenWhiteMode.bind(this);

    const observer = new MutationObserver(this.listenWhiteMode);
    observer.observe(document.body, {
      attributes: true,
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

  listenWhiteMode = (mutationList) => {
    mutationList.forEach((mutation) => {
      if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
        if (this.state.mounted)
          this.setState({
            theme: mutation.target.classList.contains('white-mode') ? 'xcode' : 'monokai',
          });
      }
    });
  };

  onChange = (e) => {
    // if (e && e.preventDefault) e.preventDefault();
    let clean_source = e;
    if (this.props.mode === 'json') {
      clean_source = e.replace('}{}', '}');

      if (clean_source.length === 0) {
        this.props.onChange("{}");
        return
      }

      try {
        const parsed = JSON.parse(clean_source);
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

  getMode = (mode) => {
    if (mode) {
      return mode;
    } else {
      return 'javascript';
    }
  };

  render() {
    let code = this.state.value || this.props.value;

    if (this.props.mode === 'json' && typeof code !== 'string') {
      code = JSON.stringify(code, null, 2);
    }

    const mode = this.getMode(this.props.mode);

    const editor = (
      <AceEditor
        {...(this.props.ace_config || {})}
        mode={mode}
        theme={this.state.theme}
        onChange={this.onChange}
        defaultValue={code || ''}
        name="scriptParam"
        editorProps={{ $blockScrolling: true }}
        height={this.props.height || '300px'}
        width="100%"
        showGutter={true}
        highlightActiveLine={true}
        tabSize={2}
        enableBasicAutocompletion={true}
        enableLiveAutocompletion={true}
        annotations={
          this.props.annotations
            ? isFunction(this.props.annotations)
              ? this.props.annotations()
              : this.props.annotations
            : []
        }
        commands={[
          {
            name: 'saveAndCompile',
            bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
            exec: () => {
              if (this.props.saveAndCompile) {
                this.props.saveAndCompile();
              }
            },
          },
        ]}
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
                <AceEditor
                  mode="json"
                  theme="monokai"
                  readOnly={true}
                  showGutter={false}
                  value={JSON.stringify(this.props.example, null, 2)}
                  name="example"
                  height={this.props.height || '270px'}
                  width="100%"
                  tabSize={2}
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
