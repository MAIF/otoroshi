package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ForceHttpsTraffic extends NgPreRouting {

  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Force HTTPS traffic"
  override def description: Option[String]                 = "This plugin verifies the current request uses HTTPS".some
  override def isPreRouteAsync: Boolean                    = false
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRouteSync(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Either[NgPreRoutingError, Done] = {
    if (!ctx.request.theSecured) {
      NgPreRoutingErrorWithResult(
        Results.Redirect(s"https://${ctx.request.theDomain}${env.exposedHttpsPort}${ctx.request.relativeUri}")
      ).left
    } else {
      NgPreRouting.done
    }
  }
}

case class BlockHttpTrafficConfig(revokeApikeys: Boolean, revokeUserSession: Boolean, message: Option[String])
    extends NgPluginConfig {
  def json: JsValue = BlockHttpTrafficConfig.format.writes(this)
}

object BlockHttpTrafficConfig {
  val default = BlockHttpTrafficConfig(revokeApikeys = true, revokeUserSession = false, None)
  val format  = new Format[BlockHttpTrafficConfig] {

    override def reads(json: JsValue): JsResult[BlockHttpTrafficConfig] = Try {
      BlockHttpTrafficConfig(
        revokeApikeys = json.select("revoke_apikeys").asOpt[Boolean].getOrElse(true),
        revokeUserSession = json.select("revoke_user_session").asOpt[Boolean].getOrElse(false),
        message = json.select("message").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: BlockHttpTrafficConfig): JsValue = Json.obj(
      "revoke_apikeys"      -> o.revokeApikeys,
      "revoke_user_session" -> o.revokeUserSession,
      "message"             -> o.message.map(_.json).getOrElse(JsNull).asValue
    )
  }
  val configFlow: Seq[String]        = Seq("revoke_apikeys", "revoke_user_session", "message")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "revoke_apikeys"      -> Json.obj(
        "type"  -> "bool",
        "label" -> "Revoke apikeys"
      ),
      "revoke_user_session" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Revoke user sessions"
      ),
      "message"             -> Json.obj(
        "type"  -> "string",
        "label" -> "Message"
      )
    )
  )
}

// https://jviide.iki.fi/http-redirects
class BlockHttpTraffic extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def core: Boolean                               = true
  override def name: String                                = "Block non HTTPS traffic"
  override def description: Option[String]                 =
    """This plugin blocks any incoming non HTTPS traffic and returns a nice error message because nowadays HTTPS should be deployed everywhere.
      |Also, this plugin will revoke any apikey or user session passed in clear text if there is one.
      |In that case, make sure this plugins comes after Apikey and Authentication plugins.""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = BlockHttpTrafficConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BlockHttpTrafficConfig.configFlow
  override def configSchema: Option[JsObject]              = BlockHttpTrafficConfig.configSchema

  private val defaultMessage = "All connections to this resource MUST use HTTPS and TLS"

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (!ctx.request.theSecured) {
      val config =
        ctx.cachedConfig(internalName)(BlockHttpTrafficConfig.format).getOrElse(BlockHttpTrafficConfig.default)
      ctx.user.filter(_ => config.revokeUserSession).foreach { user =>
        user.delete()
        if (env.clusterConfig.mode.isWorker) {
          env.clusterAgent.invalidateSession(user.randomId)
        }
      }
      ctx.apikey.filter(_ => config.revokeApikeys).foreach { apikey =>
        apikey.copy(enabled = false).save()
        if (env.clusterConfig.mode.isWorker) {
          env.clusterAgent.disableApikey(apikey.clientId)
        }
      }
      NgAccess
        .NgDenied(Results.Status(426)(Json.obj("message" -> config.message.getOrElse(defaultMessage).json)))
        .vfuture
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
