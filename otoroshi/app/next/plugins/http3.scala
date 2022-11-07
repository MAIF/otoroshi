package otoroshi.next.plugins

import akka.stream.Materializer
import io.netty.incubator.codec.http3.Http3
import otoroshi.env.Env
import otoroshi.netty.ReactorNettyServerConfig
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

case class Http3SwitchConfig(ma: Int = 3600, domain: Option[String] = None, protocols: Seq[String] = Seq.empty)
    extends NgPluginConfig {
  def json: JsValue = Http3SwitchConfig.format.writes(this)
}
object Http3SwitchConfig   {
  val format = new Format[Http3SwitchConfig] {
    override def writes(o: Http3SwitchConfig): JsValue             = Json.obj("ma" -> o.ma)
    override def reads(json: JsValue): JsResult[Http3SwitchConfig] = Try {
      Http3SwitchConfig(
        domain = json.select("domain").asOpt[String],
        ma = json.select("ma").asOpt[Int].getOrElse(3600),
        protocols = json.select("protocols").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

class Http3Switch extends NgRequestTransformer {

  private val configReads: Reads[Http3SwitchConfig] = Http3SwitchConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Http3 traffic switch"
  override def description: Option[String]                 =
    "This plugin injects additional alt-svc header to switch to the http3 server".some
  override def defaultConfigObject: Option[NgPluginConfig] = Http3SwitchConfig().some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config                       = ctx.cachedConfig(internalName)(configReads).getOrElse(Http3SwitchConfig())
    val protocols: Seq[String]       =
      if (config.protocols.isEmpty) Http3.supportedApplicationProtocols().toSeq else config.protocols
    val domain: String               = config.domain.getOrElse("")
    val port: Int                    = ReactorNettyServerConfig.parseFromWithCache(env).http3.exposedPort
    val headers: Map[String, String] = Map(
      "alt-svc" -> protocols.map(p => s"""$p=\"$domain:$port\"; ma=${config.ma}""").reverse.mkString(", ")
    )
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ headers))
  }
}
