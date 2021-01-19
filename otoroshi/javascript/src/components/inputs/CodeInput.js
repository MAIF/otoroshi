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
      <div className="form__group mb-20 grid-template-bp1--fifth">
        <label htmlFor={`input-${props.label}`}>
          {props.label} <Help text={props.help} />
        </label>
        <div>
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
    if (e && e.preventDefault) e.preventDefault();
    if (this.props.mode === 'json') {
      try {
        const parsed = JSON.parse(e);
        this.setState({ value: e }, () => {
          this.props.onChange(e);
        });
      } catch (ex) {
        if (e.trim() === '') {
          this.setState({ value: '{}' });
        } else if (e.indexOf('}{}') > -1) {
          this.setState({ value: e.replace('}{}', '}') });
        } else {
          this.setState({ value: e });
        }
      }
    } else {
      this.setState({ value: e });
      this.props.onChange(e);
    }
  };

  render() {
    let code = this.state.value || this.props.value;
    if (this.props.mode === 'json' && typeof code !== 'string') {
      code = JSON.stringify(code, null, 2);
    }
    return (
      <div className="form__group mb-20 grid-template-bp1--fifth">
        <label htmlFor={`input-${this.props.label}`}>
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div>
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
        </div>
      </div>
    );
  }
}
