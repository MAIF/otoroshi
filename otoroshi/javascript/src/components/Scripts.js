import React, { Component } from 'react';
import ReactDOM from 'react-dom';
import { ArrayInput, Form } from './inputs';

import showdown from 'showdown';
import hljs from 'highlight.js';
import 'highlight.js/styles/monokai.css';
import { Link } from 'react-router-dom';

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
  state = { scripts: [], display: false };

  componentDidMount() {
    fetch(
      this.props.type
        ? `/bo/api/proxy/api/scripts/_list?type=${this.props.type}`
        : `/bo/api/proxy/api/scripts/_list`,
      {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      }
    )
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
      return n.innerText === ref.name || n.innerText.indexOf(ref.name) > -1;
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
      console.log(this.props.config, this.props, script.defaultConfig);
      this.props.onChangeConfig({ ...this.props.config, ...script.defaultConfig });
    }
  };

  displayForm = (script) => {
    return script.configRoot && script.configFlow && script.configSchema; // && !!this.props.config[script.configRoot];
  };

  toogle = (script) => {
    this.setState(
      { display: { ...this.state.display, [script.id]: !this.state.display[script.id] } },
      () => {
        this.update();
      }
    );
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
              <div className="row mb-3">
                <label className="col-form-label" />
                {!this.state.display[script.id] && (
                  <div className="d-flex justify-content-end input-group-btn">
                    {script.id.indexOf('cp:otoroshi.') === 0 && (
                      <a
                        className="btn btn-sm btn-primary"
                        target="_blank"
                        href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html"
                        _style={{
                          position: 'absolute',
                          right: 20,
                          top: 20,
                        }}
                      >
                        <i className="fas fa-share" /> documentation
                      </a>
                    )}
                    <button
                      type="button"
                      className="btn btn-sm btn-primary"
                      onClick={(e) => this.toogle(script)}
                      _style={{
                        position: 'absolute',
                        right: 20,
                        top: 20,
                      }}
                    >
                      <i className="fas fa-eye" /> show config. panel
                    </button>
                  </div>
                )}
                {false && !this.state.display[script.id] && (
                  <div className="col-sm-12">
                    <div
                      className="plugin-doc"
                      style={{
                        flexDirection: 'row',
                        justifyContent: 'flex-end',
                        height: 40,
                      }}
                    >
                      <button
                        type="button"
                        className="btn btn-sm btn-primary"
                        onClick={(e) => this.toogle(script)}
                        style={{
                          position: 'absolute',
                          right: 20,
                          top: 20,
                        }}
                      >
                        <i className="fas fa-eye" /> show config. panel
                      </button>
                    </div>
                  </div>
                )}
                {this.state.display[script.id] && (
                  <div className="col-sm-12">
                    <div
                      className="plugin-doc"
                      style={{
                        flexDirection: 'column',
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                        <div className="btn-group">
                          {!!script.defaultConfig && (
                            <button
                              type="button"
                              className="btn btn-sm btn-primary"
                              onClick={(e) => this.inject(script)}
                              _style={{
                                position: 'absolute',
                                right: 20,
                                top: 20,
                              }}
                            >
                              Inject default config.
                            </button>
                          )}
                        </div>
                        {script.id.indexOf('cp:otoroshi.') === 0 && (
                          <a
                            className="btn btn-sm btn-primary"
                            target="_blank"
                            href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html"
                            _style={{
                              position: 'absolute',
                              right: 20,
                              top: 20,
                            }}
                          >
                            documentation
                          </a>
                        )}
                        <button
                          type="button"
                          className="btn btn-sm btn-primary"
                          onClick={(e) => this.toogle(script)}
                          _style={{
                            position: 'absolute',
                            right: 20,
                            top: 20,
                          }}
                        >
                          <i className="fas fa-eye-slash" /> hide config. panel
                        </button>
                      </div>
                      <div
                        style={{
                          width: '100%',
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                        }}
                      >
                        <div style={{ fontSize: 24, color: 'var(--color-primary)' }}>
                          {script.name}
                        </div>
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
                          styleName="sub-container sub-container__bg-color"
                        />
                      )}
                    </div>
                  </div>
                )}
              </div>
            )
          )}
      </>
    );
  }
}

export class Scripts extends Component {
  extractName = (script, displayKind) => {
    if (displayKind) {
      return `[${script.pluginType}] ${script.name}`;
    } else {
      return script.name;
    }
  };

  render() {
    const url = this.props.type
      ? this.props.excludedTypes
        ? `/bo/api/proxy/api/scripts/_list?type=${
            this.props.type
          }&excluded_types=${this.props.excludedTypes.join(',')}`
        : `/bo/api/proxy/api/scripts/_list?type=${this.props.type}`
      : this.props.excludedTypes
        ? `/bo/api/proxy/api/scripts/_list?excluded_types=${this.props.excludedTypes.join(',')}`
        : '/bo/api/proxy/api/scripts/_list';
    const displayKind = !this.props.type;
    return (
      <div data-scripts={this.props.label}>
        <ArrayInput
          label={this.props.label}
          value={this.props.refs}
          onChange={this.props.onChange}
          valuesFrom={url}
          transformer={(a) => ({
            value: a.id,
            label: this.extractName(a, displayKind),
            desc: a.description,
          })}
          help="..."
        />
        <PluginsDescription
          label={this.props.label}
          refs={this.props.refs}
          type={this.props.type}
          config={this.props.config}
          onChangeConfig={this.props.onChangeConfig}
        />
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label" />
          <div className="col-sm-10 input-group-btn">
            {this.props.refs && this.props.refs.length === 0 && (
              <Link to="/plugins/add" className="btn btn-sm btn-primary">
                <i className="fas fa-plus" /> Create a new plugin.
              </Link>
            )}
            <Link to="/plugins" className="btn btn-sm btn-primary">
              <i className="fas fa-link" /> all plugins.
            </Link>
          </div>
        </div>
      </div>
    );
  }
}
