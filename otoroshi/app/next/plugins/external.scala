package otoroshi.next.plugins

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._

case class NgExternalValidatorConfig(
    cacheExpression: Option[String] = None,
    ttl: FiniteDuration = 60.seconds,
    timeout: FiniteDuration = 30.seconds,
    url: Option[String] = None,
    headers: Map[String, String] = Map.empty,
    errorMessage: String = "forbidden",
    errorStatus: Int = 403
) extends NgPluginConfig {
  def json: JsValue = NgExternalValidatorConfig.format.writes(this)
}

object NgExternalValidatorConfig {
  val format = new Format[NgExternalValidatorConfig] {
    override def writes(o: NgExternalValidatorConfig): JsValue             = Json.obj(
      "cache_expression" -> o.cacheExpression.map(_.json).getOrElse(JsNull).asValue,
      "url"              -> o.url.map(_.json).getOrElse(JsNull).asValue,
      "ttl"              -> o.ttl.toMillis,
      "timeout"          -> o.timeout.toMillis,
      "headers"          -> o.headers,
      "error_message"    -> o.errorMessage,
      "error_status"     -> o.errorStatus
    )
    override def reads(json: JsValue): JsResult[NgExternalValidatorConfig] = Try {
      NgExternalValidatorConfig(
        cacheExpression = json.select("cache_expression").asOpt[String].filterNot(_.isBlank),
        url = json.select("url").asOpt[String].filterNot(_.isBlank),
        ttl = json.select("ttl").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(60.seconds),
        timeout =
          json.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds),
        errorStatus = json.select("error_status").asOpt[Int].getOrElse(403),
        errorMessage = json.select("error_message").asOpt[String].getOrElse("forbidden"),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
  }
}

class NgExternalValidator extends NgAccessValidator {

  override def name: String                                = "External request validator"
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = NgExternalValidatorConfig().some
  override def description: Option[String]                 =
    "This plugin checks let requests pass based on an external validation service".some

  private val logger                                                   = Logger("otoroshi-plugins-external-validator")
  private val cache: Cache[String, (FiniteDuration, Promise[Boolean])] = Scaffeine()
    .expireAfter[String, (FiniteDuration, Promise[Boolean])](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(10000)
    .build()

  private def externalValidation(
      ctx: NgAccessContext,
      rawUrl: String,
      config: NgExternalValidatorConfig,
      cacheKey: Option[String]
  )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = cache.synchronized {
    val promise = Promise[Boolean]()
    cacheKey.foreach(key => cache.put(key, (config.ttl, promise)))
    val url     = GlobalExpressionLanguage.apply(
      value = rawUrl,
      req = ctx.request.some,
      service = None,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
    val headers = config.headers.mapValues(v =>
      GlobalExpressionLanguage.apply(
        value = v,
        req = ctx.request.some,
        service = None,
        route = ctx.route.some,
        apiKey = ctx.apikey,
        user = ctx.user,
        context = Map.empty,
        attrs = ctx.attrs,
        env = env
      )
    )
    env.Ws
      .url(url)
      .withRequestTimeout(config.timeout)
      .withFollowRedirects(true)
      .withHttpHeaders(headers.toSeq: _*)
      .post(ctx.wasmJson)
      .flatMap { resp =>
        if (resp.status == 200) {
          if (resp.json.select("pass").asOpt[Boolean].getOrElse(false)) {
            cacheKey.foreach(key => cache.getIfPresent(key).foreach(t => t._2.trySuccess(true)))
            NgAccess.NgAllowed.vfuture
          } else {
            cacheKey.foreach(key => cache.getIfPresent(key).foreach(t => t._2.trySuccess(false)))
            Errors
              .craftResponseResult(
                config.errorMessage,
                Results.Status(config.errorStatus),
                ctx.request,
                None,
                Some("errors.failed.external.validation.pass"),
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        } else {
          cacheKey.foreach(key => cache.getIfPresent(key).foreach(t => t._2.trySuccess(false)))
          Errors
            .craftResponseResult(
              config.errorMessage,
              Results.Status(config.errorStatus),
              ctx.request,
              None,
              Some("errors.failed.external.validation.status"),
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => NgAccess.NgDenied(r))
        }
      }
      .recoverWith {
        case ex => {
          logger.error(s"error while validating request with external service at '${url}'", ex)
          cacheKey.foreach { key =>
            cache.getIfPresent(key).foreach(t => t._2.trySuccess(false))
            cache.invalidate(key)
          }
          Errors
            .craftResponseResult(
              config.errorMessage,
              Results.Status(config.errorStatus),
              ctx.request,
              None,
              Some("errors.failed.external.validation.error"),
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => NgAccess.NgDenied(r))
        }
      }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(NgExternalValidatorConfig.format)
      .getOrElse(NgExternalValidatorConfig())
    config.url match {
      case None         => NgAccess.NgAllowed.vfuture
      case Some(rawUrl) => {
        config.cacheExpression match {
          case None                     => externalValidation(ctx, rawUrl, config, None)
          case Some(cacheExpressionRaw) => {
            val cacheKey = GlobalExpressionLanguage.apply(
              value = cacheExpressionRaw,
              req = ctx.request.some,
              service = None,
              route = ctx.route.some,
              apiKey = ctx.apikey,
              user = ctx.user,
              context = Map.empty,
              attrs = ctx.attrs,
              env = env
            )
            cache.getIfPresent(cacheKey) match {
              case None        => externalValidation(ctx, rawUrl, config, cacheKey.some)
              case Some(tuple) =>
                tuple._2.future.flatMap {
                  case true  => NgAccess.NgAllowed.vfuture
                  case false => {
                    Errors
                      .craftResponseResult(
                        config.errorMessage,
                        Results.Status(config.errorStatus),
                        ctx.request,
                        None,
                        Some("errors.failed.external.validation"),
                        duration = ctx.report.getDurationNow(),
                        overhead = ctx.report.getOverheadInNow(),
                        attrs = ctx.attrs,
                        maybeRoute = ctx.route.some
                      )
                      .map(r => NgAccess.NgDenied(r))
                  }
                }
            }
          }
        }
      }
    }
  }
}
