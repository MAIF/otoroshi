package otoroshi.plugins.useragent

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import akka.stream.Materializer
import com.blueconic.browscap.{UserAgentParser, UserAgentService}
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.plugins.Keys
import otoroshi.script._
import otoroshi.utils.cache.Caches
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object UserAgentHelper {

  import collection.JavaConverters._

  private val logger = Logger("otoroshi-plugins-user-agent-helper")

  private val ec           = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  private val parserFuture = new AtomicReference[Future[UserAgentParser]]()

  private val cache = Caches.expireAfterWrite[String, Option[JsObject]](10.minutes, 999)

  private def ensureInitialized(): Future[UserAgentParser] = {
    Option(parserFuture.get()) match {
      case Some(future) => future
      case None         =>
        val start  = System.currentTimeMillis()
        val future = Future {
          logger.info("Initializing User-Agent parser ...")
          val parser = new UserAgentService().loadParser()
          logger.info("end initializing ...")
          parser
        }(ec).andThen {
          case Success(_) => logger.info(s"User-Agent parser initialized in ${System.currentTimeMillis() - start} ms")
          case Failure(e) => logger.error("User-Agent parser initialization failed", e)
        }(ec)

        if (parserFuture.compareAndSet(null, future)) {
          future
        } else {
          parserFuture.get()
        }
    }
  }

  def userAgentDetails(ua: String)(implicit env: Env): Future[Option[JsObject]] = {
    env.metrics.withTimer("otoroshi.plugins.useragent.details") {
      cache.getIfPresent(ua) match {
        case details @ Some(_) => details.flatten.future
        case None              =>
          ensureInitialized().map { parser =>
            Try(parser.parse(ua)) match {
              case Failure(e)            => cache.put(ua, None)
              case Success(capabilities) =>
                val details = Some(JsObject(capabilities.getValues.asScala.map { case (field, value) =>
                  (field.name().toLowerCase(), JsString(value))
                }.toMap))
                cache.put(ua, details)
            }
            cache.getIfPresent(ua).flatten
          }(ec)
        case _                 => None.future
      }
    }
  }
}

// MIGRATED
class UserAgentExtractor extends PreRouting {

  private val logger = Logger("otoroshi-plugins-user-agent-extractor")

  override def name: String = "User-Agent details extractor"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "UserAgentInfo" -> Json.obj(
          "log" -> false
        )
      )
    )

  override def description: Option[String] =
    Some("""This plugin extract informations from User-Agent header such as browsser version, OS version, etc.
      |The informations are store in plugins attrs for other plugins to use
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "UserAgentInfo": {
      |    "log": false // will log user-agent details
      |  }
      |}
      |```
    """.stripMargin)

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = ctx.configFor("UserAgentInfo")
    val log    = (config \ "log").asOpt[Boolean].getOrElse(false)
    ctx.request.headers.get("User-Agent") match {
      case None     => funit
      case Some(ua) =>
        UserAgentHelper.userAgentDetails(ua).map {
          case None       => funit
          case Some(info) => {
            if (log) logger.info(s"User-Agent: $ua, ${Json.prettyPrint(info)}")
            ctx.attrs.putIfAbsent(Keys.UserAgentInfoKey -> info)
            funit
          }
        }
    }
  }
}

// MIGRATED
class UserAgentInfoEndpoint extends RequestTransformer {

  override def name: String = "User-Agent endpoint"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    Some(
      """This plugin will expose current user-agent informations on the following endpoint.
        |
        |`/.well-known/otoroshi/plugins/user-agent`
      """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", "/.well-known/otoroshi/plugins/user-agent") =>
        ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey) match {
          case None           => Right(ctx.otoroshiRequest).future
          case Some(location) => Left(Results.Ok(location)).future
        }
      case _                                                   => Right(ctx.otoroshiRequest).future
    }
  }
}

// MIGRATED
class UserAgentInfoHeader extends RequestTransformer {

  override def name: String = "User-Agent header"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "UserAgentInfoHeader" -> Json.obj(
          "headerName" -> "X-User-Agent-Info"
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin will sent informations extracted by the User-Agent details extractor to the target service in a header.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "UserAgentInfoHeader": {
      |    "headerName": "X-User-Agent-Info" // header in which info will be sent
      |  }
      |}
      |```
    """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config     = ctx.configFor("UserAgentInfoHeader")
    val headerName = (config \ "headerName").asOpt[String].getOrElse("X-User-Agent-Info")
    ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey) match {
      case None       => Right(ctx.otoroshiRequest).future
      case Some(info) => {
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map(
              headerName -> Json.stringify(info)
            )
          )
        ).future
      }
    }
  }
}
