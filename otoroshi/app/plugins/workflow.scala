package otoroshi.plugins.workflow

import akka.stream.scaladsl.{Sink, Source}
import env.Env
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.workflow.{WorkFlow, WorkFlowRequest, WorkFlowSpec}
import play.api.libs.json.{JsObject, Json}

class WorkflowJob extends Job {

  private val logger = Logger("otoroshi-apikeys-workflow-job")

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.WorkflowJob")

  override def name: String = "Run a workflow periodically"

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

    val input = ctx.config.select("input").asOpt[JsObject].getOrElse(Json.obj())
    val spec = ctx.config.select("workflow").asOpt[JsObject].getOrElse(Json.obj())
    val workflow = WorkFlow(WorkFlowSpec.inline(spec))
    workflow.run(WorkFlowRequest.inline(input)).map(_ => ())
  }
}
