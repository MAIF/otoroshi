package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future, Promise}

case class ImageReplacerConfig(
                        url: String = ImageReplacerConfig.defaultUrl,
) extends NgPluginConfig {
  def json: JsValue = ImageReplacerConfig.format.writes(this)
}

object ImageReplacerConfig {
  val defaultUrl = "https://raw.githubusercontent.com/MAIF/otoroshi/master/resources/otoroshi-logo.png"
  val format = new Format[ImageReplacerConfig] {
    override def writes(o: ImageReplacerConfig): JsValue = Json.obj(
      "url" -> o.url,
    )
    override def reads(json: JsValue): JsResult[ImageReplacerConfig] = JsSuccess(ImageReplacerConfig(
      url = json.select("url").asOpt[String].getOrElse(defaultUrl)
    ))
  }
}

case class Image(url: String, content: ByteString, size: String, contentType: String)

class ImageReplacer extends NgRequestTransformer {

  private val configReads: Reads[ImageReplacerConfig] = ImageReplacerConfig.format

  private val refs = new UnboundedTrieMap[String, Future[Image]]()

  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Image replacer"
  override def description: Option[String] = "Replace all response with content-type image/* as they are proxied".some
  override def defaultConfigObject: Option[NgPluginConfig] = ImageReplacerConfig().some
  override def isTransformRequestAsync: Boolean = false
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true

  private def reload(config: ImageReplacerConfig)(implicit env: Env, ec: ExecutionContext): Unit = {
    val promise = Promise[Image]()
    env.Ws.url(config.url).get().map { resp =>
      promise.trySuccess(Image(
        config.url,
        resp.bodyAsBytes,
        resp.header("Content-Length").getOrElse("143056"),
        resp.header("Content-Type").getOrElse("image/jpeg"),
      ))
    }
    refs.put(config.url, promise.future)
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    if (ctx.otoroshiResponse.contentType.exists(_.startsWith("image/"))) {
      val config = ctx.cachedConfig(internalName)(configReads).getOrElse(ImageReplacerConfig())
      refs.get(config.url) match {
        case None => reload(config)
        case Some(_) => ()
      }
      refs.get(config.url) match {
        case None => ctx.otoroshiResponse.rightf
        case Some(f) => f.map { image =>
          if (image.url != config.url) {
            reload(config)
          }
          val headers: Map[String, String] = ctx.otoroshiResponse.headers
            .-("Content-Length")
            .-("content-length")
            .++(Map("Content-Length" -> image.size))
            .-("Content-Type")
            .-("content-type")
            .++(Map("Content-Type" -> image.contentType))
          ctx.otoroshiResponse.copy(
            body = image.content.chunks(16 * 32),
            headers = headers,
          ).right
        }
      }
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}