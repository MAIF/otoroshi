package otoroshi.plugins.useragent

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.Materializer
import com.blueconic.browscap.{UserAgentParser, UserAgentService}
import otoroshi.env.Env
import otoroshi.plugins.Keys
import otoroshi.script._
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object UserAgentHelper {

  import collection.JavaConverters._

  private val logger = Logger("otoroshi-plugins-user-agent-helper")

  private val ec                       = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
  private val parserInitializing       = new AtomicBoolean(false)
  private val parserInitializationDone = new AtomicBoolean(false)
  private val parserRef                = new AtomicReference[UserAgentParser]()
  private val cache                    = new TrieMap[String, Option[JsObject]]()

  def userAgentDetails(ua: String)(implicit env: Env): Option[JsObject] = {
    env.metrics.withTimer("otoroshi.plugins.useragent.details") {
      if (parserInitializing.compareAndSet(false, true)) {
        val start = System.currentTimeMillis()
        logger.info("Initializing User-Agent parser ...")
        Future {
          parserRef.set(new UserAgentService().loadParser()) // blocking for a looooooong time !
          parserInitializationDone.set(true)
        }(ec).andThen {
          case Success(_) => logger.info(s"User-Agent parser initialized in ${System.currentTimeMillis() - start} ms")
          case Failure(e) => logger.error("User-Agent parser initialization failed", e)
        }(ec)
      }
      cache.get(ua) match {
        case details @ Some(_)                      => details.flatten
        case None if parserInitializationDone.get() => {
          Try(parserRef.get().parse(ua)) match {
            case Failure(e)            =>
              cache.putIfAbsent(ua, None)
            case Success(capabilities) => {
              val details = Some(JsObject(capabilities.getValues.asScala.map { case (field, value) =>
                (field.name().toLowerCase(), JsString(value))
              }.toMap))
              cache.putIfAbsent(ua, details)
            }
          }
          cache.get(ua).flatten
        }
        case _                                      => None // initialization in progress
      }
    }
  }
}

class UserAgentExtractor extends PreRouting {

  private val logger = Logger("otoroshi-plugins-user-agent-extractor")

  override def name: String = "User-Agent details extractor"

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
        UserAgentHelper.userAgentDetails(ua) match {
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

class UserAgentInfoEndpoint extends RequestTransformer {

  override def name: String = "User-Agent endpoint"

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

class UserAgentInfoHeader extends RequestTransformer {

  override def name: String = "User-Agent header"

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
