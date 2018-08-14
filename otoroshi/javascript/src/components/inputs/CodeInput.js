import React, { Component } from 'react';
import { Help } from './Help';
import AceEditor from 'react-ace';
import 'brace/mode/html';
import 'brace/theme/monokai';

export class CodeInput extends Component {
  state = {
    value: null,
  };

  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    try {
      const parsed = JSON.parse(e);
      this.setState({ value: null }, () => {
        this.props.onChange(e);
      });
    } catch (ex) {
      this.setState({ value: e });
    }
  };

  render() {
    let code = this.state.value || this.props.value;
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          <AceEditor
            mode="javascript"
            theme="monokai"
            onChange={this.onChange}
            value={code}
            name="scriptParam"
            editorProps={{ $blockScrolling: true }}
            height="300px"
            width="100%"
          />
        </div>
      </div>
    );
  }
}
