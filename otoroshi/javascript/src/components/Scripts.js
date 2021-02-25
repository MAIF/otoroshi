import React, { Component } from 'react';
import ReactDOM from 'react-dom';
import { ArrayInput, Form } from './inputs';

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
  state = { scripts: [] };

  componentDidMount() {
    fetch(`/bo/api/proxy/api/scripts/_list?type=${this.props.type}`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((scripts) => {
        this.setState({ scripts: scripts.filter((e) => !!e.description) });
        setTimeout(() => {
          this.update();
        }, 100);
      });
  }

  componentDidUpdate(prevProps) {
    if (prevProps.refs !== this.props.refs) {
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
  };

  find = (ref) => {
    const refs = this.state.scripts.filter((s) => s.id === ref);
    return refs[0];
  };

  findNode = (ref, tree, findOnly) => {
    const nodes = Array.from(document.querySelectorAll('.Select-value-label'));
    const node = nodes.filter((n) => {
      return n.innerText === ref.name;
    })[0];
    if (!node) {
      return null;
    }
    if (findOnly) {
      return true;
    }
    const parentNode =
      node.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode; //.parentNode;
    return ReactDOM.createPortal(tree, parentNode);
  };

  inject = (script) => {
    if (script.defaultConfig) {
      console.log(this.props.config, this.props);
      this.props.onChangeConfig({ ...this.props.config, ...script.defaultConfig });
      // this.props.onChangeConfig(_.merge({}, this.props.config, script.defaultConfig));
    }
  };

  displayForm = (script) => {
    return script.configRoot && script.configFlow && script.configSchema; // && !!this.props.config[script.configRoot];
  };

  render() {
    if (!this.props.refs) {
      return null;
    }
    return (
      <>
        {this.props.refs
          .map(this.find)
          .filter((e) => !!e)
          .map((script) =>
            this.findNode(
              script,
              <div
                className="form__group mb-20">
                  <div
                    className="plugin-doc"
                    style={{
                      marginTop: 10,
                      padding: 10,
                      borderRadius: 5,
                      backgroundColor: '#494948',
                      width: '100%',
                    }}>
                  <div className="display--flex justify-content--between">
                      <h3 className="display--inline-block">{script.name}</h3>
                      {!!script.defaultConfig && (
                        <button
                          type="button"
                          className="btn-xs btn-info ml-5"
                          onClick={(e) => this.inject(script)}>
                          Inject default config.
                        </button>
                      )}
                    </div>
                    <p
                      style={{ textAlign: 'justify', marginBottom: 10 }}
                      dangerouslySetInnerHTML={{ __html: converter.makeHtml(script.description) }}
                    />
                    {this.displayForm(script) && (
                      <h4 style={{ marginTop: 20 }}>Plugin configuration</h4>
                    )}
                    {this.displayForm(script) && (
                      <Form
                        value={
                          this.props.config[script.configRoot] || {
                            ...script.defaultConfig[script.configRoot],
                          }
                        }
                        onChange={(e) => {
                          const newConfig = { ...this.props.config, [script.configRoot]: e };
                          this.props.onChangeConfig(newConfig);
                        }}
                        flow={script.configFlow}
                        schema={script.configSchema}
                        style={{
                          marginTop: 20,
                          backgroundColor: '#373735',
                          padding: 10,
                          borderRadius: 5,
                        }}
                      />
                    )}
                  </div>
              </div>
            )
          )}
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
          transformer={(a) => ({ value: a.id, label: a.name, desc: a.description })}
          help="..."
        />
        <PluginsDescription
          refs={this.props.refs}
          type={this.props.type}
          config={this.props.config}
          onChangeConfig={this.props.onChangeConfig}
        />
        <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
          <label />
          <div>
            {this.props.refs && this.props.refs.length === 0 && (
              <a href={`/bo/dashboard/plugins/add`} className="button btn-sm btn-info">
                <i className="fas fa-plus" /> Create a new plugin
              </a>
            )}
            <a href={`/bo/dashboard/plugins`} className="button btn-sm btn-info ml-5">
              <i className="fas fa-link" /> all plugins
            </a>
          </div>
        </div>
      </>
    );
  }
}
