
import React, { Component } from 'react';
import ReactDOM from 'react-dom';
import {
  ArrayInput,
} from './inputs';

import showdown from 'showdown';
import hljs from 'highlight.js';
import 'highlight.js/styles/monokai.css';

window.hljs = window.hljs || hljs;

const converter = new showdown.Converter({
  omitExtraWLInCodeBlocks: true,
  ghCompatibleHeaderId: true,
  parseImgDimensions: true,
  simplifiedAutoLink: true,
  tables: true,
  tasklists: true,
  requireSpaceBeforeHeadingText: true,
  ghMentions: true,
  emoji: true,
  ghMentionsLink: '/{u}', // TODO: link to teams ?
  extensions: [],
});


class PluginsDescription extends Component {

  state = { scripts: [] }

  componentDidMount() {
    fetch(`/bo/api/proxy/api/scripts/_list?type=transformer`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Accept': 'application/json'
      }
    }).then(r => r.json()).then(scripts => { 
      this.setState({ scripts: scripts.filter(e => !!e.description) });
      setTimeout(() => {
        this.update();
      }, 100);
    });
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.refs !== this.props.refs) {
      setTimeout(() => {
        this.forceUpdate();
        setTimeout(() => {
          this.update();
        }, 100);
      }, 100);
    }
  }

  update = () => {
    window.$('.plugin-doc pre code').each((i, block) => {
      window.hljs.highlightBlock(block);
    });
  }

  find = (ref) => {
    return this.state.scripts.filter(s => s.id === ref)[0];
  }

  findNode = (ref, tree, findOnly) => {
    const nodes = Array.from(document.querySelectorAll('.Select-value-label'));
    const node = nodes.filter(n => {
      return n.innerText === ref.name
    })[0];
    if (!node) {
      return null;
    };
    if (findOnly) {
      return true;
    }
    const parentNode = node.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode;
    return ReactDOM.createPortal(tree, parentNode);
  }

  render() {
    return (
      <>
        {this.props.refs.map(this.find).filter(e => !!e).map(script => (
          this.findNode(
            script, 
            <div className="form-group">
              <label className="col-xs-12 col-sm-2 control-label" />
              <div className="col-sm-10">
                <div
                  className="plugin-doc"
                  style={{
                    padding: 10,
                    borderRadius: 5,
                    backgroundColor: '#494948',
                    width: '100%',
                  }}>
                  <h3>{script.name}</h3>
                  <p style={{ textAlign: 'justify' }} dangerouslySetInnerHTML={{ __html: converter.makeHtml(script.description) }} />
                </div>
              </div>
            </div>
          )
        ))}
      </>
    );
  }
}

export class Scripts extends Component {
  render() {
    return (
      <>
        <ArrayInput
          label={this.props.label}
          value={this.props.refs}
          onChange={this.props.onChange}
          valuesFrom={`/bo/api/proxy/api/scripts/_list?type=${this.props.type}`}
          transformer={a => ({ value: a.id, label: a.name, desc: a.description })}
          help="..."
        />
        <PluginsDescription refs={this.props.refs} />
        <div className="form-group">
          <label className="col-xs-12 col-sm-2 control-label" />
          <div className="col-sm-10">
            {this.props.refs.length === 0 && (
              <a href={`/bo/dashboard/scripts/add`} className="btn btn-sm btn-primary">
                <i className="glyphicon glyphicon-plus" /> Create a new script.
              </a>
            )}
            <a href={`/bo/dashboard/scripts`} className="btn btn-sm btn-primary">
              <i className="glyphicon glyphicon-link" /> all scripts.
            </a>
          </div>
        </div>
      </>
    );
  }
}