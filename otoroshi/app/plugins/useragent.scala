package otoroshi.plugins.useragent

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.Materializer
import com.blueconic.browscap.{UserAgentParser, UserAgentService}
import env.Env
import otoroshi.plugins.Keys
import otoroshi.script._
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.Result
import utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object UserAgentHelper {

  import collection.JavaConverters._

  private val logger = Logger("UserAgentHelper")

  private val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
  private val parserInitializing = new AtomicBoolean(false)
  private val parserInitializationDone = new AtomicBoolean(false)
  private val parserRef = new AtomicReference[UserAgentParser]()
  private val cache = new TrieMap[String, Option[JsObject]]()

  def userAgentDetails(ua: String): Option[JsObject] = {
    if (parserInitializing.compareAndSet(false, true)) {
      logger.info("Initializing User-Agent parser ...")
      Future {
        parserRef.set(new UserAgentService().loadParser()) // blocking for a looooooong time !
        parserInitializationDone.set(true)
      }(ec).andThen {
        case Success(_) => logger.info("User-Agent parser initialized")
        case Failure(e) => logger.error("User-Agent parser initialization failed", e)
      }(ec)
    }
    cache.get(ua) match {
      case details @ Some(_) => details.flatten
      case None if parserInitializationDone.get() => {
        Try(parserRef.get().parse(ua)) match {
          case Failure(e) =>
            cache.putIfAbsent(ua, None)
          case Success(capabilities) => {
            val details = Some(JsObject(capabilities.getValues.asScala.map {
              case (field, value) => (field.name().toLowerCase(), JsString(value))
            }.toMap))
            cache.putIfAbsent(ua, details)
          }
        }
        cache.get(ua).flatten
      }
      case _ => None // initialization in progress
    }
  }
}

class UserAgentInfo extends PreRouting {

  private val logger = Logger("UserAgentInfo")

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val log = (ctx.config \ "UserAgentInfo" \ "log").asOpt[Boolean].getOrElse(false)
    ctx.request.headers.get("User-Agent") match {
      case None => funit
      case Some(ua) =>
        UserAgentHelper.userAgentDetails(ua) match {
        case None => funit
        case Some(info) => {
          if (log) logger.info(s"User-Agent: $ua, ${Json.prettyPrint(info)}")
          ctx.attrs.putIfAbsent(Keys.UserAgentInfoKey -> info)
          funit
        }
      }
    }
  }
}

class UserAgentInfoHeader extends RequestTransformer {
  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val headerName = (ctx.config \ "UserAgentInfoHeader" \ "headerName").asOpt[String].getOrElse("X-User-Agent-Info")
    ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey) match {
      case None => Right(ctx.otoroshiRequest).future
      case Some(info) => {
        Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers ++ Map(
            headerName -> Json.stringify(info)
          )
        )).future
      }
    }
  }
}