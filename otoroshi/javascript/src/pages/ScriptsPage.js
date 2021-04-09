import React, { Component, useState } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { SelectInput, Table } from '../components/inputs';
import faker from 'faker';
import bcrypt from 'bcryptjs';

const basicTransformer = `import akka.stream.Materializer
import env.Env
import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script._
import play.api.Logger
import play.api.mvc.{Result, Results}
import scala.util._
import scala.concurrent.{ExecutionContext, Future}
import otoroshi.utils.syntax.implicits._

/**
 * Your own request transformer
 */
class MyTransformer extends RequestTransformer {

  val logger = Logger("my-transformer")

  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    logger.info(s"Request incoming with id: \${ctx.snowflake}")
    // Here add a new header to the request between otoroshi and the target
    Right(ctx.otoroshiRequest.copy(
      headers = ctx.otoroshiRequest.headers + ("Hello" -> "World")
    )).future
  }
}

// don't forget to return an instance of the transformer to make it work
new MyTransformer()
`;

const basicNanoApp = `import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.ByteString
import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

class MyApp extends NanoApp {

  import kaleidoscope._

  def sayHello()(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    Ok("Hello World!").future
  }

  def sayHelloTo(name: String)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    Ok(s"Hello $name!").future
  }

  def displayBody(body: Source[ByteString, _])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    body.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).map { bodyStr =>
      val json = Json.parse(bodyStr)
      Ok(Json.obj("body" -> json))
    }
  }

  override def route(
    request: HttpRequest,
    body: Source[ByteString, _]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    (request.method, request.path) match {
      case ("GET",  "/hello")              => sayHello()
      case ("GET", r"/hello/\${name}@(.*)") => sayHelloTo(name)
      case ("POST", "/body")               => displayBody(body)
      case (_, _)                          => NotFound("Not Found !").future
    }
  }
}

new MyApp()
`;

const basicValidator = `
import akka.http.scaladsl.util._
import akka.stream.scaladsl._
import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

class CustomValidator extends AccessValidator {
  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(_) => FastFuture.successful(true)
      case _ => FastFuture.successful(false)
    }
  }
}

new CustomValidator()
`;

const basicPreRoute = `
import akka.http.scaladsl.util._
import akka.stream.scaladsl._
import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

class CustomPreRouting extends PreRouting {
  override def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    FastFuture.successful(())
  }
}

new CustomPreRouting()
`;

const basicSink = `
import akka.stream.scaladsl._
import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._

class CustomRequestSink extends RequestSink {
  override def matches(context: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = true
  override def handle(context: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    Results.Ok(Json.obj("message" -> "hello world!")).future
  }
}

new CustomRequestSink()
`;

const basicListener = `
import env.Env
import events.{AlertEvent, OtoroshiEvent}
import otoroshi.script.OtoroshiEventListener

class CustomListener extends OtoroshiEventListener {
  override def onEvent(evt: OtoroshiEvent)(implicit env: Env): Unit = {
    case alert: AlertEvent if alert.\`@serviceId\` == "admin-api" =>
      println("Alert ! Alert !")
    case _ => ()
  }
}

new CustomListener()
`;

const basicJob = `
import env.Env
import otoroshi.script._
import play.api._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class CustomJob extends Job {

  val logger = Logger("foo.bar.JobId")

  def uniqueId: JobId = JobId("foo.bar.JobId")
  
  override def kind: JobKind = JobKind.ScheduledOnce
  override def starting: JobStarting = JobStarting.FromConfiguration
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = Some(0.millisecond)
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = Some(10.minutes)

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Hello from job")
    Job.funit
  }
}

new CustomJob()
`;

const basicExporter = `

import env.Env
import otoroshi.script._
import play.api._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import events._

class MyDataExporter extends CustomDataExporter  {

  def accept(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): Boolean = true

  def project(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): JsValue = event

  def send(events: Seq[JsValue], ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[ExportResult] = {
    events.foreach(e => println(Json.stringify(e)))
    Future.successful(ExportResult.ExportResultSuccess)
  }

  def startExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit] = Future.successful(())

  def stopExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit] = Future.successful(())
}

new MyDataExporter()
`;

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
    BackOfficeServices.compileScript(this.props.rawValue).then((res) => {
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

class ScriptTypeSelector extends Component {
  state = { type: this.props.rawValue.code.indexOf('NanoApp') > -1 ? 'app' : 'transformer' };
  render() {
    return (
      <SelectInput
        label="Type"
        value={this.state.type}
        help="..."
        onChange={(t) => {
          if (t === 'app') {
            this.setState({ type: 'app' });
            this.props.rawOnChange({ ...this.props.rawValue, type: 'app', code: basicNanoApp });
          }
          if (t === 'transformer') {
            this.setState({ type: 'transformer' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'transformer',
              code: basicTransformer,
            });
          }
          if (t === 'validator') {
            this.setState({ type: 'validator' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'validator',
              code: basicValidator,
            });
          }
          if (t === 'preroute') {
            this.setState({ type: 'preroute' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'preroute',
              code: basicPreRoute,
            });
          }
          if (t === 'sink') {
            this.setState({ type: 'sink' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'sink',
              code: basicSink,
            });
          }
          if (t === 'listener') {
            this.setState({ type: 'listener' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'listener',
              code: basicListener,
            });
          }
          if (t === 'job') {
            this.setState({ type: 'job' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'job',
              code: basicJob,
            });
          }
          if (t === 'exporter') {
            this.setState({ type: 'exporter' });
            this.props.rawOnChange({
              ...this.props.rawValue,
              type: 'exporter',
              code: basicExporter,
            });
          }
        }}
        possibleValues={[
          { label: 'Request sink', value: 'sink' },
          { label: 'Pre routing', value: 'preroute' },
          { label: 'Access Validator', value: 'validator' },
          { label: 'Request transformer', value: 'transformer' },
          { label: 'Event listener', value: 'listener' },
          { label: 'Job', value: 'job' },
          { label: 'Exporter', value: 'exporter' },
          { label: 'Nano app', value: 'app' },
        ]}
      />
    );
  }
}

export class ScriptsPage extends Component {
  state = {
    annotations: [],
  };

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    warning: {
      type: Warning,
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Plugin name', placeholder: 'My Awesome Plugin' },
    },
    desc: {
      type: 'string',
      props: { label: 'Plugin description', placeholder: 'Description of the plugin' },
    },
    type: {
      type: ScriptTypeSelector,
    },
    metadata: {
      type: 'object',
      props: { label: 'Script metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Script tags' },
    },
    code: {
      type: 'code',
      props: {
        label: 'Plugin code',
        placeholder: 'Code the plugin',
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
        setSaveAndCompile: (f) => {
          this.saveAndCompile = f;
        },
        setAnnotations: (annotations) => {
          this.setState({ annotations });
        },
      },
    },
  };

  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.desc },
  ];

  formFlow = ['_loc', 'id', 'name', 'desc', 'type', 'compilation', 'code', 'tags', 'metadata'];

  componentDidMount() {
    this.props.setTitle(`All Plugins`);
  }

  render() {
    if (!this.props.globalEnv) {
      return null;
    }
    if (!this.props.globalEnv.scriptingEnabled) {
      return (
        <p>
          Scripting is not enabled on your otoroshi cluster
        </p>
      );
    }
    return (
      <Table
        parentProps={this.props}
        selfUrl="plugins"
        defaultTitle="All Plugins"
        injectTable={(t) => (this.table = t)}
        defaultValue={BackOfficeServices.createNewScript}
        _defaultValue={() => ({
          id: faker.random.alphaNumeric(64),
          name: 'My plugin',
          desc: 'A plugin',
          code: basicTransformer,
        })}
        itemName="plugin"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={BackOfficeServices.findAllScripts}
        updateItem={BackOfficeServices.updateScript}
        deleteItem={BackOfficeServices.deleteScript}
        createItem={BackOfficeServices.createScript}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/plugins/edit/${item.id}`;
        }}
        itemUrl={(i) => `/bo/dashboard/plugins/edit/${i.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="Script"
      />
    );
  }
}
