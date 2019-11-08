import React, { Component } from 'react';
import { Help } from './Help';
import AceEditor from 'react-ace';
import _ from 'lodash';
import 'brace/mode/html';
import 'brace/mode/scala';
import 'brace/mode/javascript';
import 'brace/theme/monokai';
import 'brace/ext/language_tools';
import 'brace/ext/searchbox';

export default class CodeInput extends Component {
  state = {
    value: null,
  };

  onChange = e => {
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
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
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
