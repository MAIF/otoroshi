package otoroshi.plugins.useragent

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.Materializer
import com.blueconic.browscap.{UserAgentParser, UserAgentService}
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.libs.typedmap.TypedKey
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
      Logger.info("Initializing User-Agent parser ...")
      Future {
        parserRef.set(new UserAgentService().loadParser()) // blocking for a looooooong time !
        parserInitializationDone.set(true)
      }(ec).andThen {
        case Success(_) => Logger.info("User-Agent parser initialized")
        case Failure(e) => Logger.error("User-Agent parser initialization failed", e)
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

object UserAgentInfo {
  val UserAgentInfoKey = TypedKey[JsValue]("UserAgentInfo")
}

class UserAgentInfo extends RequestTransformer {

  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val headerName = (ctx.config \ "UserAgentInfo" \ "headerName").asOpt[String].getOrElse("X-User-Agent-Info")
    ctx.request.headers.get("User-Agent") match {
      case None => Right(ctx.otoroshiRequest).future
      case Some(ua) =>
        UserAgentHelper.userAgentDetails(ua) match {
        case None => Right(ctx.otoroshiRequest).future
        case Some(info) => {
          println("UA " + ua + ", " + Json.prettyPrint(info))

          ctx.attrs.putIfAbsent(UserAgentInfo.UserAgentInfoKey -> info)
          Right(ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map(
              headerName -> Json.stringify(info)
            )
          )).future
        }
      }
    }
  }
}