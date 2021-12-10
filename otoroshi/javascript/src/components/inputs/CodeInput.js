import React, { Component, useState } from 'react';
import { Help } from './Help';
import AceEditor from 'react-ace';
import _ from 'lodash';
import 'brace/mode/html';
import 'brace/mode/scala';
import 'brace/mode/javascript';
import 'brace/theme/monokai';
import 'brace/ext/language_tools';
import 'brace/ext/searchbox';

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

export default class CodeInput extends Component {
  static Toggle = (props) => {
    const [display, setDisplay] = useState(true);
    if (display) return <CodeInput {...props} />;
    return (
      <div className="form-group">
        <label htmlFor={`input-${props.label}`} className="col-sm-2 control-label">
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
  };

  onChange = (e) => {
    // if (e && e.preventDefault) e.preventDefault();
    let clean_source = e;
    if (this.props.mode === 'json') {
      clean_source = e.replace('}{}', '}');
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

  render() {
    let code = this.state.value || this.props.value;
    if (this.props.mode === 'json' && typeof code !== 'string') {
      code = JSON.stringify(code, null, 2);
    }

    const editor = (
      <AceEditor
        mode={
          this.props.mode
            ? this.props.mode === 'json'
              ? 'javascript'
              : this.props.mode
            : 'javascript'
        }
        theme="monokai"
        onChange={this.onChange}
        value={code}
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
            ? _.isFunction(this.props.annotations)
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

    return (
      <div className="form-group">
        {!this.props.hideLabel && (
          <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">
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
                    backgroundColor: '#f9b000',
                    color: '#fff',
                    fontStyle: 'italic',
                  }}>
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
