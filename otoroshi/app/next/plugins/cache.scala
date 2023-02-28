package otoroshi.next.plugins

import akka.stream.Materializer
import org.joda.time.{DateTime, DateTimeZone}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.Codecs
import play.api.libs.json._
import play.api.mvc.Result

import java.util.Locale
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgHttpClientCacheConfig(maxAgeSeconds: Long, methods: Seq[String], status: Seq[Int], mimeTypes: Seq[String])
    extends NgPluginConfig {
  def json: JsValue = NgHttpClientCacheConfig.format.writes(this)
}

object NgHttpClientCacheConfig {
  val default = NgHttpClientCacheConfig(
    maxAgeSeconds = 86400,
    methods = Seq("GET"),
    status = Seq(200),
    mimeTypes = Seq("text/html")
  )
  val format  = new Format[NgHttpClientCacheConfig] {
    override def reads(json: JsValue): JsResult[NgHttpClientCacheConfig] = Try {
      NgHttpClientCacheConfig(
        maxAgeSeconds = json.select("max_age_seconds").asOpt[Long].getOrElse(default.maxAgeSeconds),
        methods = json.select("methods").asOpt[Seq[String]].getOrElse(default.methods),
        status = json
          .select("status")
          .asOpt[Seq[Int]]
          .orElse(json.select("status").asOpt[Seq[String]].map(_.map(_.toInt)))
          .getOrElse(default.status),
        mimeTypes = json.select("mime_types").asOpt[Seq[String]].getOrElse(default.mimeTypes)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: NgHttpClientCacheConfig): JsValue = Json.obj(
      "max_age_seconds" -> o.maxAgeSeconds,
      "methods"         -> o.methods,
      "status"          -> o.status,
      "mime_types"      -> o.mimeTypes
    )
  }
}

class NgHttpClientCache extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true

  override def name: String                                = "HTTP Client Cache"
  override def description: Option[String]                 = "This plugin add cache headers to responses".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHttpClientCacheConfig.default.some

  private def methodMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    config.methods.map(_.toLowerCase().trim).contains(ctx.request.method.toLowerCase())
  }

  private def statusMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    config.status.contains(ctx.otoroshiResponse.status)
  }

  private def contentMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    ctx.otoroshiResponse.contentType match {
      case None if config.mimeTypes.contains("*") => true
      case None                                   => false
      case Some(contentType)                      =>
        config.mimeTypes.exists(mt => contentType.startsWith(mt) || RegexPool.apply(mt).matches(contentType))
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config =
      ctx.cachedConfig(internalName)(NgHttpClientCacheConfig.format).getOrElse(NgHttpClientCacheConfig.default)
    if (methodMatch(ctx, config) && statusMatch(ctx, config) && contentMatch(ctx, config)) {
      val now     = DateTime.now(DateTimeZone.UTC)
      val date    = now.toString("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
      val expires =
        now.plusSeconds(config.maxAgeSeconds.toInt).toString("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
      ctx.otoroshiResponse
        .copy(headers =
          ctx.otoroshiResponse.headers ++ Map(
            "Cache-Control" -> s"max-age=${config.maxAgeSeconds}",
            "Date"          -> date,
            "Expires"       -> expires,
            "ETag"          -> Codecs.sha1(s"$date -> ${ctx.request.domain}${ctx.request.uri}"),
            "Last-Modified" -> date,
            "Vary"          -> "Accept-Encoding"
          )
        )
        .rightf
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}
