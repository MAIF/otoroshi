import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';

class CompilationTools extends Component {
  state = {
    compiling: false,
    error: null,
  };

  componentDidMount() {
    this.props.setSaveAndCompile(this.compile);
  }

  compile = () => {
    this.setState({ compiling: true });
    BackOfficeServices.compileScript(this.props.rawValue).then(res => {
      if (res.error) {
        this.setState({ error: res.error, compiling: false });
        this.props.setAnnotations([
          {
            row: res.error.line === 0 ? 0 : res.error.line - 1,
            column: res.error.column,
            type: 'error',
            text: res.error.message,
          },
        ]);
      } else {
        this.setState({ error: null, compiling: false });
        this.props.setAnnotations([]);
      }
    });
  };

  render() {
    return (
      <div className="form-group">
        <label className="col-xs-12 col-sm-2 control-label" />
        <div className="col-sm-10">
          <button
            type="button"
            className={`btn btn-${this.state.error ? 'danger' : 'success'}`}
            onClick={this.compile}>
            <i className="fas fa-cogs" /> {this.state.compiling ? 'Compiling ...' : 'Compile'}
          </button>
          {!!this.state.error && (
            <div className="alert alert-danger" role="alert">
              {this.state.error.message}
            </div>
          )}
        </div>
      </div>
    );
  }
}

export class Warning extends Component {
  render() {
    return (
      <div className="form-group">
        <label className="col-xs-12 col-sm-2 control-label" />
        <div className="col-sm-10">
          <div
            style={{
              padding: 10,
              borderRadius: 5,
              backgroundColor: '#494948',
              width: '100%',
            }}>
            <div
              style={{
                width: '100%',
                display: 'flex',
                justifyContent: 'center',
                marginBottom: 20,
              }}>
              <i className="fa fa-skull-crossbones" style={{ color: '#c9302c', fontSize: 35 }} />
            </div>
            <p style={{ textAlign: 'justify' }}>
              Request transformers let you chose exactly how Otoroshi request are handled and
              forwarded from client to target and back. You can change the headers / body of the
              request and the headers / body of the response to make it behave exactly as you
              expect. It's a good way to support things that are not supported out of the box by
              Otoroshi. However, using a request transformer can be really inefficient (depending on
              what you're trying to do) and costly for your service. It can impact performances
              severely and mess with Otoroshi stability in general.
            </p>
            <p style={{ width: '100%', textAlign: 'right', fontSize: 12 }}>
              <span style={{ fontStyle: 'italic' }}>
                "Where there is great power there is great responsibility"
              </span>{' '}
              - Winston Churchill
            </p>
          </div>
        </div>
      </div>
    );
  }
}

export class ScriptsPage extends Component {
  state = {
    annotations: [],
  };

  formSchema = {
    warning: {
      type: Warning,
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Script name', placeholder: 'My Awesome Script' },
    },
    desc: {
      type: 'string',
      props: { label: 'Script description', placeholder: 'Description of the Script' },
    },
    code: {
      type: 'code',
      props: {
        label: 'Script code',
        placeholder: 'Code the Script',
        mode: 'scala',
        annotations: () => this.state.annotations,
        saveAndCompile: () => {
          if (this.saveAndCompile) {
            this.saveAndCompile();
          }
          if (this.table) {
            this.table.updateItemAndStay();
          }
        },
        height: '500px',
      },
    },
    compilation: {
      type: CompilationTools,
      props: {
        setSaveAndCompile: f => {
          this.saveAndCompile = f;
        },
        setAnnotations: annotations => {
          this.setState({ annotations });
        },
      },
    },
  };

  columns = [
    { title: 'Name', content: item => item.name },
    { title: 'Description', noMobile: true, content: item => item.desc },
  ];

  formFlow = ['warning', 'id', 'name', 'desc', 'compilation', 'code'];

  componentDidMount() {
    this.props.setTitle(`All Scripts (experimental)`);
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="scripts"
        defaultTitle="All Scripts"
        injectTable={t => (this.table = t)}
        defaultValue={() => ({
          id: faker.random.alphaNumeric(64),
          name: 'My Script',
          desc: 'A script',
          code: `import akka.stream.Materializer
import env.Env
import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script._
import play.api.Logger
import play.api.mvc.{Result, Results}
import scala.util._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Your own request transformer
 */
class MyTransformer extends RequestTransformer {

  val logger = Logger("my-transformer")

  override def transformRequestSync(
    snowflake: String,
    rawRequest: HttpRequest,
    otoroshiRequest: HttpRequest,
    desc: ServiceDescriptor,
    apiKey: Option[ApiKey],
    user: Option[PrivateAppsUser]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, HttpRequest] = {
    logger.info(s"Request incoming with id: $snowflake")
    // Here add a new header to the request between otoroshi and the target
    Right(otoroshiRequest.copy(
      headers = otoroshiRequest.headers + ("Hello" -> "World")
    ))
  }
}

// don't forget to return an instance of the transformer to make it work
new MyTransformer()
`,
        })}
        itemName="script"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={BackOfficeServices.findAllScripts}
        updateItem={BackOfficeServices.updateScript}
        deleteItem={BackOfficeServices.deleteScript}
        createItem={BackOfficeServices.createScript}
        navigateTo={item => {
          window.location = `/bo/dashboard/scripts/edit/${item.id}`;
        }}
        itemUrl={i => `/bo/dashboard/scripts/edit/${item.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={item => item.id}
      />
    );
  }
}
