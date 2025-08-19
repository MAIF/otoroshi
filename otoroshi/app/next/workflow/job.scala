package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class WorkflowJobConfig(
   enabled: Boolean = false,
   kind: JobKind = JobKind.ScheduledEvery,
   instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
   initialDelay: Option[FiniteDuration] = None,
   interval: Option[FiniteDuration] = None,
   cronExpression: Option[String] = None,
   rawConfig: JsObject = Json.obj()
) {
  def json: JsValue = Json.obj(
    "enabled"         -> enabled,
    "kind"            -> kind.name,
    "instantiation"   -> instantiation.name,
    "initial_delay"   -> initialDelay.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "interval"        -> interval.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "cron_expression" -> cronExpression.map(JsString.apply).getOrElse(JsNull).asValue,
    "config"      -> rawConfig
  )
}

object WorkflowJobConfig {
  val default = WorkflowJobConfig()
  val format = new Format[WorkflowJobConfig] {
    override def reads(json: JsValue): JsResult[WorkflowJobConfig] = Try {
      WorkflowJobConfig(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        kind = JobKind(json.select("kind").asString),
        instantiation = JobInstantiation(json.select("instantiation").asString),
        initialDelay = json.select("initial_delay").asOpt[Long].map(_.millis),
        interval = json.select("interval").asOpt[Long].map(_.millis),
        cronExpression = json.select("cron_expression").asOpt[String],
        rawConfig = json.select("config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(cfg) => JsSuccess(cfg)
    }

    override def writes(o: WorkflowJobConfig): JsValue = o.json
  }
}

class WorkflowJob(ref: String, config: WorkflowJobConfig) extends Job {

  private val logger = Logger("otoroshi-workflow-job")

  override def core: Boolean                     = true
  override def name: String                      = "Workflow Job"
  override def description: Option[String]       = "this job execute any given Workflow plugin".some
  override def defaultConfig: Option[JsObject]   = WorkflowJobConfig.default.json.asObject.some
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Workflow"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep]                = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility      = JobVisibility.UserLand
  override def starting: JobStarting             = JobStarting.Automatically

  override def uniqueId: JobId                                                 = JobId(s"io.otoroshi.next.workflow.WorkflowJob#${ref}")
  override def kind: JobKind                                                   = config.kind
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      = config.instantiation
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = config.initialDelay
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = config.interval
  override def cronExpression(ctx: JobContext, env: Env): Option[String]       = config.cronExpression
  override def predicate(ctx: JobContext, env: Env): Option[Boolean]           = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]   = Try {
    env.adminExtensions.extension[WorkflowAdminExtension].map { ext =>
      ext.workflow(ref) match {
        case None =>
          logger.error(s"No workflow context found for ${ref}")
          Future.successful(())
        case Some(workflow) =>
          ext.engine.run(Node.from(workflow.config), config.rawConfig, ctx.attrs, workflow.functions).map {
            case WorkflowResult(returned, error, run) if error.isDefined =>
              logger.error(s"Errors during workflow context for ${ref}: ${error.get.json.prettify}")
            case WorkflowResult(returned, error, run) => ()
          }
      }
    }.getOrElse(Future.successful(()))
  } match {
    case Failure(e) =>
      logger.error("error during workflow job run", e)
      funit
    case Success(s) => s
  }
}