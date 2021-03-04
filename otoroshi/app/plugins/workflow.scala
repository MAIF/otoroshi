package otoroshi.plugins.workflow

import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.workflow.{WorkFlow, WorkFlowRequest, WorkFlowSpec}
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class WorkflowJob extends Job {

  private val logger = Logger("otoroshi-apikeys-workflow-job")

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.WorkflowJob")

  override def name: String = "Workflow job"

  override def defaultConfig: Option[JsObject] = Json.obj("WorkflowJob" -> Json.obj(
    "input" -> Json.obj(
      "namespace" -> "otoroshi",
      "service" -> "otoroshi-dns",
    ),
    "intervalMillis" -> "60000",
    "workflow" -> Json.obj(
      "name" -> "some-workflow",
      "description" -> "a nice workflow",
      "tasks" -> Json.arr(
        Json.obj(
          "name" -> "call-dns",
          "type" -> "http",
          "request" -> Json.obj(
            "method" -> "PATCH",
            "url" -> "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/v1/namespaces/${input.namespace}/services/${input.service}",
            "headers" -> Json.obj(
              "accept" -> "application/json",
              "content-type" -> "application/json",
              "authorization" -> "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}",
            ),
            "tls" -> Json.obj(
              "mtls" -> true,
              "trustAll" -> true
            ),
            "body" -> Json.arr(
              Json.obj(
                "op" -> "replace",
                "path" -> "/spec/selector",
                "value" -> Json.obj(
                  "app" -> "otoroshi",
                  "component" -> "dns"
                )
              )
            )
          ),
          "success" -> Json.obj(
            "statuses" -> Json.arr(200)
          )
        )
      )
    )
  )).some

  override def description: Option[String] = "Periodically run a custom workflow".some

  override def visibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = ctx.config.select("intervalMillis").asOpt[Long].getOrElse(60000L).millis.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val input = ctx.configFor("WorkflowJob").select("input").asOpt[JsObject].getOrElse(Json.obj())
    val specJson = ctx.configFor("WorkflowJob").select("workflow").asOpt[JsObject].getOrElse(Json.obj())
    val spec = WorkFlowSpec.inline(specJson)
    val workflow = WorkFlow(spec)
    workflow.run(WorkFlowRequest.inline(input)).map(_ => ())
  }
}

class WorkflowEndpoint extends RequestTransformer {

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def beforeRequest(ctx: BeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(ctx: AfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }

  override def name: String = "Workflow endpoint"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        "WorkflowEndpoint" -> Json.obj(
          "workflow" -> Json.obj()
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin runs a workflow and return the response
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val specJson = ctx.configFor("WorkflowEndpoint").select("workflow").as[JsValue]
    val spec = WorkFlowSpec.inline(specJson)
    val workflow = WorkFlow(spec)
    awaitingRequests.get(ctx.snowflake).map { promise =>
      val consumed = new AtomicBoolean(false)
      val bodySource: Source[ByteString, _] = Source
        .future(promise.future)
        .flatMapConcat(s => s)
        .alsoTo(Sink.onComplete {
          case _ => consumed.set(true)
        })

      bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val body = if (ctx.request.contentType.getOrElse("--").contains("application/json")) Json.parse(bodyRaw.utf8String) else JsString(bodyRaw.utf8String)
        val input = Json.obj(
          "request" -> ctx.rawRequest.json,
          "otoroshiRequest" -> ctx.otoroshiRequest.json,
          "snowflake" -> ctx.snowflake,
          "descriptor" -> ctx.descriptor.json,
          "config" -> ctx.config,
          "globalConfig" -> ctx.globalConfig,
          "body" -> body
        )
        workflow.run(WorkFlowRequest.inline(input)).map { resp =>
          val response = resp.ctx.response.get
          val ctype = response.select("headers").select("Content-Type").asOpt[String].orElse(response.select("headers").select("content-type").asOpt[String]).getOrElse("text/plain")
          val body = response.select("body").as[JsValue] match {
            case JsString(value) => value
            case value => value.stringify
          }
          val success = resp.success
          if (success) {
            Left(Results.Status(response.select("status").asInt)(body).as(ctype))
          } else {
            Left(Results.InternalServerError(Json.obj("error" -> "workflow failed")))
          }
        }
      } andThen {
        case _ =>
          if (!consumed.get()) bodySource.runWith(Sink.ignore)
      }
    } getOrElse {
      Results.InternalServerError(Json.obj("error" -> "body_error")).leftf
    }
  }
}