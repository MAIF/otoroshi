package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.jsoup.Jsoup
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RobotConfig(
  robotEnabled: Boolean = true,
  robotTxtContent: String = """User-agent: *
                              |Disallow: /
                              |""".stripMargin,
  metaEnabled: Boolean = true,
  metaContent: String = "noindex,nofollow,noarchive",
  headerEnabled: Boolean = true,
  headerContent: String = "noindex, nofollow, noarchive",
) {
  def json: JsValue = RobotConfig.format.writes(this)
}

object RobotConfig {
  val format = new Format[RobotConfig] {
    override def reads(json: JsValue): JsResult[RobotConfig] = Try {
      RobotConfig(
        robotEnabled = json.select("robot_txt_enabled").asOpt[Boolean].getOrElse(true),
        robotTxtContent = json.select("robot_txt_content").asOpt[String].getOrElse("""User-agent: *
                                                                                     |Disallow: /
                                                                                     |""".stripMargin),
        metaEnabled = json.select("meta_enabled").asOpt[Boolean].getOrElse(true),
        metaContent = json.select("meta_content").asOpt[String].getOrElse("noindex,nofollow,noarchive"),
        headerEnabled = json.select("header_enabled").asOpt[Boolean].getOrElse(true),
        headerContent = json.select("header_content").asOpt[String].getOrElse("noindex, nofollow, noarchive"),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: RobotConfig): JsValue = Json.obj(
      "robot_txt_enabled" -> o.robotEnabled,
      "robot_txt_content" -> o.robotTxtContent,
      "meta_enabled" -> o.metaEnabled,
      "meta_content" -> o.metaContent,
      "header_enabled" -> o.headerEnabled,
      "header_content" -> o.headerContent,
    )
  }
}

class Robots extends NgRequestTransformer {

  private val configReads: Reads[RobotConfig] = RobotConfig.format

  override def core: Boolean                   = true
  override def name: String                    = "Robots"
  override def description: Option[String]     = "This plugin provides all the necessary tool to handle search engine robots".some
  override def defaultConfig: Option[JsObject] = RobotConfig().json.asObject.some

  override def isTransformRequestAsync: Boolean = false
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true

  override def transformRequestSync(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(RobotConfig())
    if (config.robotEnabled && ctx.request.thePath == "/robots.txt") {
      Results.Ok(config.robotTxtContent).left
    } else {
      ctx.otoroshiRequest.right
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(RobotConfig())
    if (config.metaEnabled && ctx.otoroshiResponse.contentType.exists(v => v.contains("text/html"))) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val body = bodyRaw.utf8String
        val doc  = Jsoup.parse(body)
        val meta = Jsoup.parseBodyFragment(s"""<meta name="robots" content="${config.metaContent}">""").body()
        val elementHead = if (meta.childrenSize() > 0) meta.children().first() else meta
        doc.head().insertChildren(-1, elementHead)
        ctx.otoroshiResponse.copy(
          headers = ctx.otoroshiResponse.headers.-("Content-Length").-("content-length") ++ Map(
            "Transfer-Encoding" -> "chunked",
          ).applyOnIf(config.headerEnabled) { m =>
            m + ("X-Robots-Tag" -> config.headerContent)
          },
          body = Source.single(ByteString(doc.toString))
        ).right
      }
    } else {
      if (config.headerEnabled) {
        ctx.otoroshiResponse.copy(
          headers = ctx.otoroshiResponse.headers ++ Map(
            "X-Robots-Tag" -> config.headerContent
          )
        ).right.vfuture
      } else {
        ctx.otoroshiResponse.right.vfuture
      }
    }
  }
}
