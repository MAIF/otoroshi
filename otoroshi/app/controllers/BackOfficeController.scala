package otoroshi.controllers

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import ch.qos.logback.classic.{Level, LoggerContext}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import com.nimbusds.jose.jwk.KeyType
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import otoroshi.actions.{ApiActionContext, BackOfficeAction, BackOfficeActionAuth, BackOfficeActionContextAuth}
import otoroshi.auth._
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.events.impl.{ElasticReadsAnalytics, ElasticTemplates, ElasticUtils, ElasticVersion}
import otoroshi.jobs.AnonymousReportingJobConfig
import otoroshi.jobs.newengine.NewEngine
import otoroshi.jobs.updates.SoftwareUpdatesJobs
import otoroshi.models.RightsChecker.SuperAdminOnly
import otoroshi.models._
import otoroshi.next.models.{GraphQLFormats, NgRoute, NgRouteComposition}
import otoroshi.next.plugins.EurekaServerSink
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.proxy.{BackOfficeRequest, ProxyEngine}
import otoroshi.script.RequestHandler
import otoroshi.security._
import otoroshi.ssl._
import otoroshi.ssl.pki.models.{GenCertResponse, GenCsrQuery}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.Logger
import play.api.http.{HttpEntity, HttpRequestHandler}
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.SourceBody
import play.api.mvc._

import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ServiceLike(entity: EntityLocationSupport, groups: Seq[String]) extends EntityLocationSupport {
  def id: String                                = internalId
  override def internalId: String               = entity.internalId
  override def json: JsValue                    = entity.json
  override def theName: String                  = entity.theName
  override def theDescription: String           = entity.theDescription
  override def theTags: Seq[String]             = entity.theTags
  override def theMetadata: Map[String, String] = entity.theMetadata
  override def location: EntityLocation         = entity.location
}

object ServiceLike {
  def fromService(service: ServiceDescriptor): ServiceLike           = ServiceLike(service, service.groups)
  def fromRoute(service: NgRoute): ServiceLike                       = ServiceLike(service, service.groups)
  def fromRouteComposition(service: NgRouteComposition): ServiceLike = ServiceLike(service, service.groups)
}

case class BackofficeFlags(
    env: Env,
    _useAkkaHttpClient: Option[Boolean] = None,
    _logUrl: Option[Boolean] = None,
    _logStats: Option[Boolean] = None,
    _requestTimeout: Option[FiniteDuration] = None
) {
  lazy val useAkkaHttpClient: Boolean     = _useAkkaHttpClient
    .orElse(env.configuration.betterGetOptional[Boolean]("otoroshi.backoffice.flags.useAkkaHttpClient"))
    .getOrElse(false)
  lazy val logUrl: Boolean                =
    _logUrl.orElse(env.configuration.betterGetOptional[Boolean]("otoroshi.backoffice.flags.logUrl")).getOrElse(false)
  lazy val logStats: Boolean              =
    _logStats
      .orElse(env.configuration.betterGetOptional[Boolean]("otoroshi.backoffice.flags.logStats"))
      .getOrElse(false)
  lazy val requestTimeout: FiniteDuration = _requestTimeout
    .orElse(
      env.configuration
        .betterGetOptional[Long]("otoroshi.backoffice.flags.requestTimeout")
        .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
    )
    .getOrElse(1.minute)
  def rawJson: JsValue                    = Json
    .obj()
    .applyOnWithOpt(_useAkkaHttpClient) { case (obj, _useAkkaHttpClient) =>
      obj ++ Json.obj("useAkkaHttpClient" -> _useAkkaHttpClient)
    }
    .applyOnWithOpt(_logUrl) { case (obj, _logUrl) => obj ++ Json.obj("logUrl" -> _logUrl) }
    .applyOnWithOpt(_logStats) { case (obj, _logStats) => obj ++ Json.obj("logStats" -> _logStats) }
    .applyOnWithOpt(_requestTimeout) { case (obj, _requestTimeout) =>
      obj ++ Json.obj("requestTimeout" -> _requestTimeout.toMillis)
    }
}

object BackofficeFlags {
  private val ref                                                                         = new AtomicReference[(Long, BackofficeFlags)]()
  def fromJson(json: JsValue)(implicit env: Env): BackofficeFlags = {
    val useAkkaHttpClient = json.select("useAkkaHttpClient").asOpt[Boolean]
    val logUrl            = json.select("logUrl").asOpt[Boolean]
    val logStats          = json.select("logStats").asOpt[Boolean]
    val requestTimeout    = json.select("requestTimeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
    BackofficeFlags(env, useAkkaHttpClient, logUrl, logStats, requestTimeout)
  }
  def fill()(implicit ec: ExecutionContext, env: Env): Unit = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:backoffice:flags").map {
      case None          =>
        ref.set((System.currentTimeMillis(), BackofficeFlags(env)))
      case Some(bodyRaw) => {
        val flags = fromJson(bodyRaw.utf8String.parseJson)
        ref.set((System.currentTimeMillis(), flags))
      }
    }
  }
  def latest(implicit ec: ExecutionContext, env: Env): BackofficeFlags = {
    Option(ref.get()) match {
      case None                                                               =>
        fill()
        BackofficeFlags(env)
      case Some((time, flags)) if (time + 5000L) < System.currentTimeMillis() =>
        fill()
        flags
      case Some((_, flags))                                                   =>
        flags
    }
  }
  def writeJson(flags: JsValue)(implicit ec: ExecutionContext, env: Env): BackofficeFlags = write(fromJson(flags))
  def write(flags: BackofficeFlags)(implicit ec: ExecutionContext, env: Env): BackofficeFlags = {
    env.datastores.rawDataStore.set(s"${env.storageRoot}:backoffice:flags", flags.rawJson.stringify.byteString, None)
    flags
  }
}

class BackOfficeController(
    BackOfficeAction: BackOfficeAction,
    BackOfficeActionAuth: BackOfficeActionAuth,
    handlerRef: AtomicReference[HttpRequestHandler],
    cc: ControllerComponents
)(implicit
    env: Env
) extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val lat = env.otoroshiMaterializer

  lazy val handler       = handlerRef.get()
  lazy val logger        = Logger("otoroshi-backoffice-api")
  lazy val commitVersion = Option(System.getenv("COMMIT_ID")).getOrElse(env.otoroshiVersion)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Proxy
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val sourceBodyParser = BodyParser("BackOfficeApi BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def getFlags() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      Ok(BackofficeFlags.latest.rawJson).future
    }
  }

  def writeFlags() = BackOfficeActionAuth.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      val body  = ctx.request.body
      val flags = BackofficeFlags.writeJson(body)
      Ok(flags.rawJson).future
    }
  }

  def proxyAdminApi(path: String) = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    env.datastores.apiKeyDataStore.findById(env.backOfficeApiKey.clientId).flatMap {
      case None                                                                  =>
        FastFuture.successful(
          NotFound(
            Json.obj(
              "error" -> "admin apikey not found !"
            )
          )
        )
      case Some(apikey) if env.backofficeUsePlay                                 => passWithPlay(ctx, apikey)
      case Some(apikey) if !env.backofficeUsePlay && env.backofficeUseNewEngine  => passWithNewEngine(ctx, apikey)
      case Some(apikey) if !env.backofficeUsePlay && !env.backofficeUseNewEngine => passWithOldEngine(ctx, apikey, path)
    }
  }

  private def passWithPlay(
      ctx: BackOfficeActionContextAuth[Source[ByteString, _]],
      apikey: ApiKey
  ): Future[Result] = {
    logger.debug(s"using play for ${ctx.request.method} ${ctx.request.theUrl}")
    val host               = env.adminApiExposedHost
    val request            = new BackOfficeRequest(ctx.request, host, apikey, ctx.user, env)
    val (nreq, reqHandler) = handler.handlerForRequest(request)
    reqHandler match {
      case a: EssentialAction => a.apply(nreq).run(request.body)
      case _                  => Future.failed(new RuntimeException("websocket not supported here !"))
    }
  }

  private def passWithNewEngine(
      ctx: BackOfficeActionContextAuth[Source[ByteString, _]],
      apikey: ApiKey
  ): Future[Result] = {
    logger.debug(s"using new engine for ${ctx.request.method} ${ctx.request.theUrl}")
    env.scriptManager.getAnyScript[RequestHandler](NgPluginHelper.pluginId[ProxyEngine]) match {
      case Left(err)         =>
        Results.InternalServerError(Json.obj("error" -> "admin_api_error", "error_description" -> err)).vfuture
      case Right(raw_engine) => {
        val engine          = raw_engine.asInstanceOf[ProxyEngine]
        implicit val global = env.datastores.globalConfigDataStore.latest()
        val raw_request     = ctx.request
        val host            = env.adminApiExposedHost
        val request         = new BackOfficeRequest(raw_request, host, apikey, ctx.user, env)
        engine.handleRequest(request, engine.getConfig())
      }
    }
  }

  private def passWithOldEngine(
      ctx: BackOfficeActionContextAuth[Source[ByteString, _]],
      apikey: ApiKey,
      path: String
  ): Future[Result] = {
    logger.debug(s"using old engine ! ${ctx.request.method} ${ctx.request.theUrl}")
    val host                   = env.adminApiExposedHost
    val localUrl               =
      if (env.adminApiProxyHttps) s"https://127.0.0.1:${env.httpsPort}" else s"http://127.0.0.1:${env.port}"
    val url                    =
      if (env.adminApiProxyUseLocal) localUrl else s"https://${env.adminApiExposedHost}${env.exposedHttpsPort}"
    lazy val currentReqHasBody = ctx.request.theHasBody
    val flags                  = BackofficeFlags.latest
    if (flags.logUrl) {
      logger.info(s"[${ctx.request.id}] calling ${ctx.request.method} $url/$path with Host = $host")
    }
    if (logger.isDebugEnabled) logger.debug(s"Calling ${ctx.request.method} $url/$path with Host = $host")
    val headers                = Seq(
      "Host"                           -> host,
      "X-Forwarded-For"                -> ctx.request.theIpAddress,
      env.Headers.OtoroshiVizFromLabel -> "Otoroshi Admin UI",
      env.Headers.OtoroshiVizFrom      -> "otoroshi-admin-ui",
      env.Headers.OtoroshiClientId     -> apikey.clientId,
      env.Headers.OtoroshiClientSecret -> apikey.clientSecret,
      env.Headers.OtoroshiAdminProfile -> Base64.getUrlEncoder.encodeToString(
        Json.stringify(ctx.user.profile).getBytes(Charsets.UTF_8)
      ),
      "Otoroshi-Tenant"                -> ctx.request.headers.get("Otoroshi-Tenant").getOrElse("default"),
      "Otoroshi-BackOffice-User"       -> JWT
        .create()
        .withClaim("user", Json.stringify(ctx.user.toJson))
        .sign(Algorithm.HMAC512(apikey.clientSecret))
    ) ++ ctx.request.headers.get("Content-Type").filter(_ => currentReqHasBody).map { ctype =>
      "Content-Type" -> ctype
    } ++ ctx.request.headers.get("Accept").map { accept =>
      "Accept" -> accept
    } ++ ctx.request.headers.get("X-Content-Type").map(v => "X-Content-Type" -> v)

    if (flags.useAkkaHttpClient) {

      val builder = env.Ws // MTLS needed here ???
        .akkaUrl(s"$url/$path")
        .withHttpHeaders(headers: _*)
        .withFollowRedirects(false)
        .withMethod(ctx.request.method)
        .withRequestTimeout(flags.requestTimeout)
        .withQueryStringParameters(ctx.request.queryString.toSeq.map(t => (t._1, t._2.head)): _*)

      val builderWithBody = if (currentReqHasBody) {
        builder.withBody(SourceBody(ctx.request.body))
      } else {
        builder
      }

      val start = System.currentTimeMillis()
      if (flags.logStats)
        logger.info(
          s"[${ctx.request.id}] akka - starting admin-api call: ${ctx.request.method} ${ctx.request.thePath}"
        )
      if (logger.isDebugEnabled)
        logger.debug(
          s"[${ctx.request.id}] akka - starting admin-api call: ${ctx.request.method} ${ctx.request.thePath}"
        )
      builderWithBody
        .stream()
        .fast
        .map { res =>
          if (flags.logStats)
            logger.info(
              s"[${ctx.request.id}] akka - got result for admin-api call: ${ctx.request.method} ${ctx.request.thePath} in ${System
                .currentTimeMillis() - start}ms : ${res.status} - ${res.headers}"
            )
          if (logger.isDebugEnabled)
            logger.debug(
              s"[${ctx.request.id}] akka - got result for admin-api call: ${ctx.request.method} ${ctx.request.thePath} in ${System
                .currentTimeMillis() - start}ms : ${res.status} - ${res.headers}"
            )
          val ctype = res.headers.get("Content-Type").flatMap(_.headOption).getOrElse("application/json")
          Status(res.status)
            .sendEntity(
              HttpEntity.Streamed(
                res.bodyAsSource
                  .recover {
                    case t: java.util.concurrent.TimeoutException if path.contains("/live") =>
                      ByteString.empty
                  }
                  .alsoTo(Sink.onComplete { case e =>
                    if (flags.logStats)
                      logger.info(
                        s"[${ctx.request.id}] akka - for admin-api call: ${ctx.request.method} ${ctx.request.thePath} body has been consumed in ${System
                          .currentTimeMillis() - start}ms"
                      )
                    if (logger.isDebugEnabled)
                      logger.debug(
                        s"[${ctx.request.id}] akka - for admin-api call: ${ctx.request.method} ${ctx.request.thePath} body has been consumed in ${System
                          .currentTimeMillis() - start}ms"
                      )
                  }),
                res.headers.get("Content-Length").flatMap(_.lastOption).map(_.toInt),
                res.headers.get("Content-Type").flatMap(_.headOption)
              )
            )
            .withHeaders(
              res.headers
                .mapValues(_.head)
                .toSeq
                .filter(_._1 != "Content-Type")
                .filter(_._1 != "Content-Length")
                .filter(_._1 != "Transfer-Encoding"): _*
            )
            .as(ctype)
        }
    } else {
      val builder = env.Ws // MTLS needed here ???
        .url(s"$url/$path")
        .withHttpHeaders(headers: _*)
        .withFollowRedirects(false)
        .withMethod(ctx.request.method)
        .withRequestTimeout(flags.requestTimeout)
        .withQueryStringParameters(ctx.request.queryString.toSeq.map(t => (t._1, t._2.head)): _*)

      val builderWithBody = if (currentReqHasBody) {
        builder.withBody(SourceBody(ctx.request.body))
      } else {
        builder
      }

      val start = System.currentTimeMillis()
      if (flags.logStats)
        logger.info(s"[${ctx.request.id}] starting admin-api call: ${ctx.request.method} ${ctx.request.thePath}")
      if (logger.isDebugEnabled)
        logger.debug(s"[${ctx.request.id}] starting admin-api call: ${ctx.request.method} ${ctx.request.thePath}")
      builderWithBody
        .stream()
        .fast
        .map { res =>
          if (flags.logStats)
            logger.info(
              s"[${ctx.request.id}] got result for admin-api call: ${ctx.request.method} ${ctx.request.thePath} in ${System
                .currentTimeMillis() - start}ms : ${res.status} - ${res.headers}"
            )
          if (logger.isDebugEnabled)
            logger.debug(
              s"[${ctx.request.id}] got result for admin-api call: ${ctx.request.method} ${ctx.request.thePath} in ${System
                .currentTimeMillis() - start}ms : ${res.status} - ${res.headers}"
            )
          val ctype = res.headers.get("Content-Type").flatMap(_.headOption).getOrElse("application/json")
          Status(res.status)
            .sendEntity(
              HttpEntity.Streamed(
                Source
                  .lazySource(() => res.bodyAsSource)
                  .recover {
                    case t: java.util.concurrent.TimeoutException if path.contains("/live") =>
                      ByteString.empty
                  }
                  .alsoTo(Sink.onComplete { case e =>
                    if (flags.logStats)
                      logger.info(
                        s"[${ctx.request.id}] for admin-api call: ${ctx.request.method} ${ctx.request.thePath} body has been consumed in ${System
                          .currentTimeMillis() - start}ms"
                      )
                    if (logger.isDebugEnabled)
                      logger.debug(
                        s"[${ctx.request.id}] for admin-api call: ${ctx.request.method} ${ctx.request.thePath} body has been consumed in ${System
                          .currentTimeMillis() - start}ms"
                      )
                  }),
                res.headers.get("Content-Length").flatMap(_.lastOption).map(_.toInt),
                res.headers.get("Content-Type").flatMap(_.headOption)
              )
            )
            .withHeaders(
              res.headers
                .mapValues(_.head)
                .toSeq
                .filter(_._1 != "Content-Type")
                .filter(_._1 != "Content-Length")
                .filter(_._1 != "Transfer-Encoding"): _*
            )
            .as(ctype)
        }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Pure routing
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def robotTxt =
    Action { req =>
      if (logger.isDebugEnabled) logger.debug(s"Rendering robot.txt on ${req.theProtocol}://${req.theHost}/robot.txt")
      Ok("""User-agent: *
         |Disallow: /""".stripMargin)
    }

  def version =
    BackOfficeActionAuth {
      Ok(
        Json.obj(
          "version"        -> commitVersion,
          "currentVersion" -> env.otoroshiVersion,
          "nextVersion"    -> SoftwareUpdatesJobs.latestVersionHolder.get()
        )
      )
    }

  def getEnv() =
    BackOfficeActionAuth.async { ctx =>
      val hash = BCrypt.hashpw("password", BCrypt.gensalt())
      env.datastores.globalConfigDataStore.singleton().flatMap { config =>
        env.datastores.simpleAdminDataStore.findAll().flatMap { users =>
          env.datastores.rawDataStore.get(s"${env.storageRoot}:backoffice:anonymous-reporting-refused").map {
            refusedOpt =>
              val reporting                 = AnonymousReportingJobConfig.fromEnv(env)
              val refusedDate               = refusedOpt.map(_.utf8String).map(DateTime.parse)
              val refused: JsValue          = refusedDate.map(_.toString).map(JsString.apply).getOrElse(JsNull)
              val (shouldAsk, shouldEnable) = if (reporting.enabled) {
                if (!config.anonymousReporting) {
                  refusedDate match {
                    case None                                                      => (true, false)
                    case Some(date) if date.plusMonths(6).isBefore(DateTime.now()) => (true, false)
                    case _                                                         => (false, false)
                  }
                } else {
                  (false, false)
                }
              } else {
                (true, true)
              }
              val changePassword            = users.filter { user =>
                //(user \ "password").as[String] == hash &&
                user.username == "admin@otoroshi.io"
              }.nonEmpty
              Ok(
                Json.obj(
                  "newEngineEnabled"        -> NewEngine.enabledFromConfig(config, env),
                  "initWithNewEngine"       -> config.initWithNewEngine,
                  "scriptingEnabled"        -> env.scriptingEnabled,
                  "otoroshiLogo"            -> env.otoroshiLogo,
                  "clusterRole"             -> env.clusterConfig.mode.name,
                  "snowMonkeyRunning"       -> config.snowMonkeyConfig.enabled,
                  "changePassword"          -> changePassword,
                  "mailgun"                 -> config.mailerSettings.isDefined,
                  "clevercloud"             -> config.cleverSettings.isDefined,
                  "apiReadOnly"             -> config.apiReadOnly,
                  "u2fLoginOnly"            -> config.u2fLoginOnly,
                  "env"                     -> env.env,
                  "redirectToDev"           -> false,
                  "userAdmin"               -> ctx.user.rights.superAdmin,
                  "superAdmin"              -> ctx.user.rights.superAdmin,
                  "tenantAdmin"             -> ctx.user.rights.tenantAdmin(ctx.currentTenant),
                  "currentTenant"           -> ctx.currentTenant.value,
                  "bypassUserRightsCheck"   -> env.bypassUserRightsCheck,
                  "clientIdHeader"          -> env.Headers.OtoroshiClientId,
                  "clientSecretHeader"      -> env.Headers.OtoroshiClientSecret,
                  "version"                 -> SoftwareUpdatesJobs.latestVersionHolder.get(),
                  "currentVersion"          -> env.otoroshiVersion,
                  "commitVersion"           -> commitVersion,
                  "adminApiId"              -> env.backOfficeServiceId,
                  "adminGroupId"            -> env.backOfficeGroupId,
                  "adminApikeyId"           -> env.backOfficeApiKeyClientId,
                  "user"                    -> ctx.user.email,
                  "instanceId"              -> config.otoroshiId,
                  "staticExposedDomain"     -> env.staticExposedDomain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                  "providerDashboardUrl"    -> env.providerDashboardUrl.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                  "providerDashboardTitle"  -> env.providerDashboardTitle,
                  "providerDashboardSecret" -> env.providerDashboardSecret,
                  "instanceId"              -> config.otoroshiId,
                  "instanceName"            -> env.name,
                  "anonymousReporting"      -> Json.obj(
                    "static"        -> reporting.enabled,
                    "global"        -> config.anonymousReporting,
                    "refused"       -> refused,
                    "should_ask"    -> shouldAsk,
                    "should_enable" -> shouldEnable
                  )
                )
              )
          }
        }
      }
    }

  def index =
    BackOfficeAction.async { ctx =>
      env.datastores.globalConfigDataStore.singleton().map { config =>
        val thridPartyLoginEnabled = config.backOfficeAuthRef.nonEmpty
        ctx.user match {
          case Some(user)                      => Redirect("/bo/dashboard")
          case None if config.u2fLoginOnly     => Redirect(routes.U2FController.loginPage())
          case None if thridPartyLoginEnabled  =>
            Ok(otoroshi.views.html.backoffice.index(thridPartyLoginEnabled, ctx.user, ctx.request, env))
          case None if !thridPartyLoginEnabled => Redirect(routes.U2FController.loginPage())
        }
      }
    }

  def dashboard =
    BackOfficeActionAuth.async { ctx =>
      env.datastores.globalConfigDataStore.singleton().flatMap { config =>
        env.datastores.tenantDataStore.findAll().map { tenants =>
          val userTenants = tenants
            .filter(t => ctx.user.rights.rights.exists(r => r.tenant.canRead && r.tenant.matches(t.id)))
            .filterNot(_.id == TenantId.all)
            .map(_.id.value)
          Ok(
            otoroshi.views.html.backoffice.dashboard(
              ctx.user,
              config,
              env,
              env.otoroshiVersion,
              userTenants,
              ctx.request.session.get("ui-mode").getOrElse("dark")
            )
          )
        }
      }
    }

  def dashboardRoutes(ui: String) =
    BackOfficeActionAuth.async { ctx =>
      env.datastores.globalConfigDataStore.singleton().flatMap { config =>
        env.datastores.tenantDataStore.findAll().map { tenants =>
          val userTenants = tenants
            .filter(t => ctx.user.rights.rights.exists(r => r.tenant.canRead && r.tenant.matches(t.id)))
            .filterNot(_.id == TenantId.all)
            .map(_.id.value)
          Ok(
            otoroshi.views.html.backoffice.dashboard(
              ctx.user,
              config,
              env,
              env.otoroshiVersion,
              userTenants,
              ctx.request.session.get("ui-mode").getOrElse("dark")
            )
          )
        }
      }
    }

  def error(message: Option[String]) =
    BackOfficeAction { ctx =>
      Ok(otoroshi.views.html.oto.error(message.getOrElse("Error message"), env))
    }

  def documentationFrame(lineId: String, serviceId: String) =
    BackOfficeActionAuth.async { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
        case Some(descriptor) if !ctx.canUserRead(descriptor) => ApiActionContext.forbidden
        case Some(descriptor)                                 => Ok(otoroshi.views.html.backoffice.documentationframe(descriptor, env))
        case None                                             => NotFound(Json.obj("error" -> s"Service with id $serviceId not found"))
      }
    }

  def documentationFrameDescriptor(lineId: String, serviceId: String) =
    BackOfficeActionAuth.async { ctx =>
      import scala.concurrent.duration._
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case Some(descriptor) if !ctx.canUserRead(descriptor)            => ApiActionContext.fforbidden
        case Some(service) if service.api.openApiDescriptorUrl.isDefined => {
          val state = IdGenerator.extendedToken(128)
          val claim = OtoroshiClaim(
            iss = env.Headers.OtoroshiIssuer,
            sub = "Documentation",
            aud = service.name,
            exp = DateTime.now().plusSeconds(30).toDate.getTime,
            iat = DateTime.now().toDate.getTime,
            jti = IdGenerator.uuid
          ).serialize(service.algoInfoFromOtoToBack)(env)
          val url   = service.api.openApiDescriptorUrl.get match {
            case uri if uri.startsWith("/") => s"${service.target.scheme}://${service.target.host}${uri}"
            case url                        => url
          }
          env.Ws // no need for mtls here
            .url(url)
            .withRequestTimeout(10.seconds)
            .withHttpHeaders(
              env.Headers.OtoroshiRequestId -> env.snowflakeGenerator.nextIdStr(),
              env.Headers.OtoroshiState     -> state,
              env.Headers.OtoroshiClaim     -> claim
            )
            .get()
            .map { resp =>
              try {
                val swagger = (resp.json.as[JsObject] \ "swagger").as[String]
                swagger match {
                  case "2.0" => Ok(Json.prettyPrint(resp.json)).as("application/json")
                  case "3.0" => Ok(Json.prettyPrint(resp.json)).as("application/json")
                  case _     =>
                    InternalServerError(otoroshi.views.html.oto.error(s"Swagger version $swagger not supported", env))
                }
              } catch {
                case e: Throwable => InternalServerError(Json.obj("error" -> e.getMessage))
              }
            }
        }
        case _                                                           => FastFuture.successful(NotFound(otoroshi.views.html.oto.error("Service not found", env)))
      }
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // APIs that are only relevant here
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def cleverApps() =
    BackOfficeActionAuth.async { ctx =>
      val paginationPage: Int     = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition      = (paginationPage - 1) * paginationPageSize
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
          globalConfig.cleverClient match {
            case Some(client) => {
              client.apps(client.orgaId).map { cleverapps =>
                val apps = cleverapps.value
                  .map { app =>
                    val id                 = (app \ "id").as[String]
                    val name               = (app \ "name").as[String]
                    val hosts: Seq[String] =
                      (app \ "vhosts").as[JsArray].value.map(vhost => (vhost \ "fqdn").as[String])
                    val preferedHost       =
                      hosts.filterNot(h => h.contains("cleverapps.io")).headOption.getOrElse(hosts.head)
                    val service            =
                      services.filter(ctx.canUserRead).find(s => s.targets.exists(t => hosts.contains(t.host)))
                    Json.obj(
                      "name"    -> (app \ "name").as[String],
                      "id"      -> id,
                      "url"     -> s"https://${preferedHost}/",
                      "console" -> s"https://console.clever-cloud.com/organisations/${client.orgaId}/applications/$id",
                      "exists"  -> service.isDefined,
                      "host"    -> preferedHost,
                      "otoUrl"  -> s"/lines/${service.map(_.env).getOrElse("--")}/services/${service.map(_.id).getOrElse("--")}"
                    )
                  }
                  .drop(paginationPosition)
                  .take(paginationPageSize)
                Ok(JsArray(apps))
              }
            }
            case None         => FastFuture.successful(Ok(Json.arr()))
          }
        }
      }
    }

  def eurekaServers() =
    BackOfficeActionAuth.async { _ =>
      env.datastores.routeDataStore.findAll().map { routes =>
        Ok(
          JsArray(
            routes
              .filter(r => r.plugins.hasPlugin[EurekaServerSink])
              .map(_.json)
          )
        )
      }
    }

  def externalEurekaServers() =
    BackOfficeActionAuth.async { ctx =>
      ctx.request.getQueryString("url") match {
        case Some(url) =>
          env.Ws
            .url(s"$url/apps")
            .withMethod("GET")
            .withHttpHeaders(Seq("Accept" -> "application/json"): _*)
            .execute()
            .map { res =>
              if (res.status == 200) {
                Ok(res.bodyAsBytes.utf8String.parseJson)
              } else {
                BadRequest(Json.obj("error" -> s"bad server response: ${res.status} - ${res.headers} - ${res.body}"))
              }
            }
        case None      =>
          BadRequest(Json.obj("error" -> "missing url")).vfuture
      }
    }

  def eurekaServerApps(id: String) =
    BackOfficeActionAuth.async { _ =>
      env.datastores.rawDataStore
        .allMatching(s"${env.storageRoot}:plugins:eureka-server-$id:apps*")
        .map { rawApps =>
          Ok(
            JsArray(
              rawApps.map(app => Json.parse(app.utf8String))
            )
          )
        }
    }

  def panicMode() =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { c =>
          c.copy(u2fLoginOnly = true, apiReadOnly = true).save()
        } flatMap { _ =>
          env.datastores.backOfficeUserDataStore.discardAllSessions()
        } map { _ =>
          val event = BackOfficeEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user,
            "ACTIVATE_PANIC_MODE",
            s"Admin activated panic mode",
            ctx.from,
            ctx.ua,
            Json.obj()
          )
          Audit.send(event)
          Alerts.send(PanicModeAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
          Ok(Json.obj("done" -> true))
        } recover { case _ =>
          Ok(Json.obj("done" -> false))
        }
      }
    }

  case class SearchedService(
      name: String,
      id: String,
      groupId: String,
      env: String,
      typ: String,
      location: EntityLocation
  ) extends EntityLocationSupport {
    override def internalId: String      = id
    override def json: JsValue           = Json.obj()
    def theDescription: String           = name
    def theMetadata: Map[String, String] = Map.empty
    def theName: String                  = name
    def theTags: Seq[String]             = Seq.empty
  }

  def searchServicesApi() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      val query                            = (ctx.request.body \ "query").asOpt[String].getOrElse("--").toLowerCase()
      Audit.send(
        BackOfficeEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user,
          "SERVICESEARCH",
          "user searched for a service",
          ctx.from,
          ctx.ua,
          Json.obj(
            "query" -> query
          )
        )
      )
      val fu: Future[Seq[SearchedService]] =
        for {
          services   <- env.datastores.serviceDescriptorDataStore.findAll()
          tcServices <- env.datastores.tcpServiceDataStore.findAll()
          routes     <- env.datastores.routeDataStore.findAll()
        } yield {
          val finalServices =
            services.map(s =>
              SearchedService(s.name, s.id, s.groups.headOption.getOrElse("default"), s.env, "http", s.location)
            ) ++
            routes.map(s =>
              SearchedService(s.name, s.id, s.groups.headOption.getOrElse("default"), "route", "route", s.location)
            ) ++
            tcServices.map(s => SearchedService(s.name, s.id, "tcp", "prod", "tcp", s.location))
          finalServices
        }
      fu.map { services =>
        val filtered = services.filter(ctx.canUserRead).filter { service =>
          service.id.toLowerCase() == query || service.name.toLowerCase().contains(query) || service.env
            .toLowerCase()
            .contains(query)
        }
        Ok(
          JsArray(
            filtered.map(s =>
              Json.obj("groupId" -> s.groupId, "serviceId" -> s.id, "name" -> s.name, "env" -> s.env, "type" -> s.typ)
            )
          )
        )
      }
    }

  def changeLogLevel(name: String, newLevel: Option[String]) =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val _logger       = loggerContext.getLogger(name)
        val oldLevel      = Option(_logger.getLevel).map(_.levelStr).getOrElse(Level.OFF.levelStr)
        _logger.setLevel(newLevel.map(v => Level.valueOf(v)).getOrElse(Level.ERROR))
        Ok(Json.obj("name" -> name, "oldLevel" -> oldLevel, "newLevel" -> _logger.getLevel.levelStr)).future
      }
    }

  def getLogLevel(name: String) =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val _logger       = loggerContext.getLogger(name)
        Ok(Json.obj("name" -> name, "level" -> _logger.getLevel.levelStr)).future
      }
    }

  def getAllLoggers() =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        import collection.JavaConverters._

        val paginationPage: Int     = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
        val paginationPageSize: Int =
          ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
        val paginationPosition      = (paginationPage - 1) * paginationPageSize

        val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val rawLoggers    = loggerContext.getLoggerList.asScala.drop(paginationPosition).take(paginationPageSize)
        val loggers       = JsArray(rawLoggers.map(logger => {
          val level: String = Option(logger.getLevel).map(_.levelStr).getOrElse("OFF")
          Json.obj("name" -> logger.getName, "level" -> level)
        }))
        Ok(loggers).future
      }
    }

  case class ServiceRate(rate: Double, name: String, id: String)

  def mostCalledServices() =
    BackOfficeActionAuth.async { ctx =>
      val paginationPage: Int     = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(10)
      val paginationPosition      = (paginationPage - 1) * paginationPageSize

      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        Future.sequence(
          services.map(s =>
            env.datastores.serviceDescriptorDataStore.callsPerSec(s.id).map(rate => ServiceRate(rate, s.name, s.id))
          )
        )
      } map { items =>
        items.sortWith(_.rate > _.rate).drop(paginationPosition).take(paginationPageSize)
      } map { items =>
        items.map { i =>
          val value: Double = Option(i.rate).filterNot(_.isInfinity).getOrElse(0.0)
          Json.obj(
            "rate" -> value,
            "name" -> i.name,
            "id"   -> i.id
          )
        }
      } map { items =>
        Ok(JsArray(items))
      }
    }

  def servicesMap() =
    BackOfficeActionAuth.async { ctx =>
      env.datastores.serviceGroupDataStore.findAll().flatMap { groups =>
        Future.sequence(
          groups.map { group =>
            env.datastores.serviceDescriptorDataStore.findByGroup(group.id).flatMap { services =>
              Future.sequence(services.map { service =>
                env.datastores.serviceDescriptorDataStore.callsPerSec(service.id).map(cps => (service, cps))
              })
            } map {
              case services if services.isEmpty  => Json.obj()
              case services if services.nonEmpty =>
                Json.obj(
                  "name"     -> group.name,
                  "children" -> JsArray(services.map { case (service, cps) =>
                    val size: Int = ((1.0 + cps) * 1000.0).toInt
                    Json.obj(
                      "name" -> service.name,
                      "env"  -> service.env,
                      "id"   -> service.id,
                      "size" -> size
                    )
                  })
                )
            }
          }
        )
      } map { children =>
        Json.obj("name" -> "Otoroshi Services", "children" -> children.filterNot(_ == Json.obj()))
      } map { json =>
        Ok(json)
      }
    }

  def fetchOpenIdConfiguration() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      import otoroshi.utils.http.Implicits._

      import scala.concurrent.duration._

      val id                  = (ctx.request.body \ "id").asOpt[String].getOrElse(IdGenerator.token(64))
      val name                = (ctx.request.body \ "name").asOpt[String].getOrElse("new oauth config")
      val desc                = (ctx.request.body \ "desc").asOpt[String].getOrElse("new oauth config")
      val clientId            = (ctx.request.body \ "clientId").asOpt[String].getOrElse("client")
      val clientSecret        = (ctx.request.body \ "clientSecret").asOpt[String].getOrElse("secret")
      val sessionCookieValues =
        (ctx.request.body \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues())
      (ctx.request.body \ "url").asOpt[String] match {
        case None      =>
          FastFuture.successful(
            Ok(
              GenericOauth2ModuleConfig(
                id = id,
                name = name,
                desc = desc,
                clientId = clientId,
                clientSecret = clientSecret,
                oidConfig = None,
                tags = Seq.empty,
                metadata = Map.empty,
                sessionCookieValues = sessionCookieValues,
                clientSideSessionEnabled = true
              ).asJson
            )
          )
        case Some(url) => {
          env.Ws
            .url(url) // no need for mtls here
            .withRequestTimeout(10.seconds)
            .get()
            .map { resp =>
              if (resp.status == 200) {
                Try {
                  val config           = GenericOauth2ModuleConfig(
                    id = id,
                    name = name,
                    desc = desc,
                    oidConfig = Some(url),
                    tags = Seq.empty,
                    metadata = Map.empty,
                    sessionCookieValues = sessionCookieValues,
                    clientSideSessionEnabled = true
                  )
                  val body             = Json.parse(resp.body)
                  val issuer           = (body \ "issuer").asOpt[String].getOrElse("http://localhost:8082/")
                  val tokenUrl         = (body \ "token_endpoint").asOpt[String].getOrElse(config.tokenUrl)
                  val authorizeUrl     = (body \ "authorization_endpoint").asOpt[String].getOrElse(config.authorizeUrl)
                  val userInfoUrl      = (body \ "userinfo_endpoint").asOpt[String].getOrElse(config.userInfoUrl)
                  val introspectionUrl =
                    (body \ "introspection_endpoint").asOpt[String].getOrElse(config.introspectionUrl)
                  val loginUrl         = (body \ "authorization_endpoint").asOpt[String].getOrElse(authorizeUrl)
                  val logoutUrl        = (body \ "end_session_endpoint")
                    .asOpt[String]
                    .orElse((body \ "ping_end_session_endpoint").asOpt[String])
                    .getOrElse((issuer + "/logout").replace("//logout", "/logout"))
                  val jwksUri          = (body \ "jwks_uri").asOpt[String]
                  val scope            = (body \ "scopes_supported")
                    .asOpt[Seq[String]]
                    .map(_.mkString(" "))
                    .getOrElse("openid profile email name")
                  val claims           =
                    (body \ "claims_supported").asOpt[JsArray].map(Json.stringify).getOrElse("""["email","name"]""")
                  Ok(
                    config
                      .copy(
                        clientId = clientId,
                        clientSecret = clientSecret,
                        tokenUrl = tokenUrl,
                        authorizeUrl = authorizeUrl,
                        userInfoUrl = userInfoUrl,
                        introspectionUrl = introspectionUrl,
                        loginUrl = loginUrl,
                        logoutUrl = logoutUrl,
                        callbackUrl =
                          s"${env.rootScheme}${env.privateAppsHost}${env.privateAppsPort}/privateapps/generic/callback",
                        scope = scope,
                        claims = "",
                        accessTokenField = "access_token", // jwksUri.map(_ => "id_token").getOrElse("access_token"),
                        useJson = false,
                        useCookie = false,
                        readProfileFromToken = false,
                        nameField = (if (scope.contains(config.nameField)) config.nameField else config.emailField),
                        oidConfig = Some(url),
                        jwtVerifier = jwksUri.map(url =>
                          JWKSAlgoSettings(
                            url = url,
                            headers = Map.empty[String, String],
                            timeout = FiniteDuration(2000, TimeUnit.MILLISECONDS),
                            ttl = FiniteDuration(60 * 60 * 1000, TimeUnit.MILLISECONDS),
                            kty = KeyType.RSA,
                            None,
                            MtlsConfig.default
                          )
                        )
                      )
                      .asJson
                  )
                } getOrElse {
                  resp.ignore()
                  Ok(
                    GenericOauth2ModuleConfig(
                      id = id,
                      name = name,
                      desc = desc,
                      clientId = clientId,
                      clientSecret = clientSecret,
                      oidConfig = Some(url),
                      tags = Seq.empty,
                      metadata = Map.empty,
                      sessionCookieValues = sessionCookieValues,
                      clientSideSessionEnabled = true
                    ).asJson
                  )
                }
              } else {
                resp.ignore()
                Ok(
                  GenericOauth2ModuleConfig(
                    id = id,
                    name = name,
                    desc = desc,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    oidConfig = Some(url),
                    tags = Seq.empty,
                    metadata = Map.empty,
                    sessionCookieValues = sessionCookieValues,
                    clientSideSessionEnabled = true
                  ).asJson
                )
              }
            }
        }
      }
    }

  def fetchSAMLConfiguration() = BackOfficeActionAuth.async(parse.json) { ctx =>
    {
      import scala.xml.Elem
      import scala.xml.XML._

      Try {
        val xmlContent: Either[String, Elem] = (ctx.request.body \ "url").asOpt[String] match {
          case Some(url) => Right(load(url))
          case None      =>
            (ctx.request.body \ "xml").asOpt[String] match {
              case Some(content) => Right(loadString(content))
              case None          => Left("Missing body content")
            }
        }

        xmlContent match {
          case Left(err)         => FastFuture.successful(BadRequest(err))
          case Right(xmlContent) =>
            var metadata = (xmlContent \\ "EntitiesDescriptor").toString

            if (metadata.isEmpty)
              metadata = xmlContent.toString

            SamlAuthModuleConfig.fromDescriptor(metadata) match {
              case Left(err)     => FastFuture.successful(BadRequest(err))
              case Right(config) => FastFuture.successful(Ok(SamlAuthModuleConfig._fmt.writes(config)))
            }
        }
      } recover { case e: Throwable =>
        FastFuture.successful(
          BadRequest(
            Json.obj(
              "error" -> e.getMessage
            )
          )
        )
      } get
    }
  }

  def fetchBodiesFor(serviceId: String, requestId: String) =
    BackOfficeActionAuth.async { ctx =>
      for {
        req  <- env.datastores.rawDataStore.get(s"${env.storageRoot}:bodies:$serviceId:$requestId:request")
        resp <- env.datastores.rawDataStore.get(s"${env.storageRoot}:bodies:$serviceId:$requestId:response")
      } yield {
        if (req.isEmpty && resp.isEmpty) {
          NotFound(Json.obj("error" -> "Bodies not found"))
        } else {
          Ok(
            Json.obj(
              "response" -> resp.map(_.utf8String).map(Json.parse).getOrElse(JsNull).as[JsValue],
              "request"  -> req.map(_.utf8String).map(Json.parse).getOrElse(JsNull).as[JsValue]
            )
          )
        }
      }
    }

  def resetCircuitBreakers(id: String) =
    BackOfficeActionAuth { ctx =>
      env.circuitBeakersHolder.resetCircuitBreakersFor(id)
      Ok(Json.obj("done" -> true))
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // TODO: APIs already in admin API, remove it at some point ?
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  /*
  def sessions() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition = (paginationPage - 1) * paginationPageSize
      env.datastores.backOfficeUserDataStore.tsessions() map { sessions =>
        Ok(JsArray(sessions.filter(ctx.canUserRead).drop(paginationPosition).take(paginationPageSize).map(_.json)))
      }
    }
  }

  def discardSession(id: String) = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(SuperAdminOnly) {
      env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
        env.datastores.backOfficeUserDataStore.findById(id).flatMap {
          case None => Results.NotFound(Json.obj("error" -> "Session not found")).future
          case Some(session) if !ctx.canUserWrite(session) => ApiActionContext.fforbidden
          case Some(_) => {
            env.datastores.backOfficeUserDataStore.discardSession(id) map { _ =>
              val event = BackOfficeEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user,
                "DISCARD_SESSION",
                s"Admin discarded an Admin session",
                ctx.from,
                ctx.ua,
                Json.obj("sessionId" -> id)
              )
              Audit.send(event)
              Alerts
                .send(SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
              Ok(Json.obj("done" -> true))
            }
          }
        }
      } recover {
        case _ => Ok(Json.obj("done" -> false))
      }
    }
  }

  def discardAllSessions() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(SuperAdminOnly) {
      env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
        env.datastores.backOfficeUserDataStore.discardAllSessions() map { _ =>
          val event = BackOfficeEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user,
            "DISCARD_SESSIONS",
            s"Admin discarded Admin sessions",
            ctx.from,
            ctx.ua,
            Json.obj()
          )
          Audit.send(event)
          Alerts
            .send(SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
          Ok(Json.obj("done" -> true))
        }
      } recover {
        case _ => Ok(Json.obj("done" -> false))
      }
    }
  }

  def privateAppsSessions() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition = (paginationPage - 1) * paginationPageSize
      env.datastores.privateAppsUserDataStore.findAll() map { sessions =>
        Ok(JsArray(sessions.filter(ctx.canUserRead).drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
      }
    }
  }

  def discardPrivateAppsSession(id: String) = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
        env.datastores.privateAppsUserDataStore.findById(id).flatMap {
          case None => Results.NotFound(Json.obj("error" -> "Session not found")).future
          case Some(session) if !ctx.canUserWrite(session) => ApiActionContext.fforbidden
          case Some(_) => {
            env.datastores.privateAppsUserDataStore.delete(id) map { _ =>
              val event = BackOfficeEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user,
                "DISCARD_PRIVATE_APPS_SESSION",
                s"Admin discarded a private app session",
                ctx.from,
                ctx.ua,
                Json.obj("sessionId" -> id)
              )
              Audit.send(event)
              Alerts
                .send(SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
              Ok(Json.obj("done" -> true))
            }
          } recover {
            case _ => Ok(Json.obj("done" -> false))
          }
        }
      }
    }
  }

  def discardAllPrivateAppsSessions() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(SuperAdminOnly) {
      env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
        env.datastores.privateAppsUserDataStore.deleteAll() map { _ =>
          val event = BackOfficeEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user,
            "DISCARD_PRIVATE_APPS_SESSIONS",
            s"Admin discarded private apps sessions",
            ctx.from,
            ctx.ua,
            Json.obj()
          )
          Audit.send(event)
          Alerts
            .send(SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
          Ok(Json.obj("done" -> true))
        }
      } recover {
        case _ => Ok(Json.obj("done" -> false))
      }
    }
  }*/

  def auditEvents() =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        val paginationPage: Int     = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
        val paginationPageSize: Int =
          ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
        val paginationPosition      = (paginationPage - 1) * paginationPageSize
        env.datastores.auditDataStore.findAllRaw().map { elems =>
          val filtered = elems.drop(paginationPosition).take(paginationPageSize)
          Ok.chunked(
            Source
              .single(ByteString("["))
              .concat(
                Source
                  .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                  .intersperse(ByteString(","))
              )
              .concat(Source.single(ByteString("]")))
          ).as("application/json")
        }
      }
    }

  def alertEvents() =
    BackOfficeActionAuth.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        val paginationPage: Int     = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
        val paginationPageSize: Int =
          ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
        val paginationPosition      = (paginationPage - 1) * paginationPageSize
        env.datastores.alertDataStore.findAllRaw().map { elems =>
          val filtered = elems.drop(paginationPosition).take(paginationPageSize)
          Ok.chunked(
            Source
              .single(ByteString("["))
              .concat(
                Source
                  .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                  .intersperse(ByteString(","))
              )
              .concat(Source.single(ByteString("]")))
          ).as("application/json")
        }
      }
    }

  def selfSignedCert(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        Try {
          Json.parse(body.utf8String).\("host").asOpt[String] match {
            case Some(host) => {
              env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA).map {
                case None     => {
                  val cert =
                    FakeKeyStore.createSelfSignedCertificate(host, FiniteDuration(365, TimeUnit.DAYS), None, None)
                  val c    = Cert(cert.cert, cert.keyPair, None, false)
                  val cc   = c.enrich()
                  Ok(cc.toJson)
                }
                case Some(ca) => {
                  val cert = FakeKeyStore.createCertificateFromCA(
                    host,
                    FiniteDuration(365, TimeUnit.DAYS),
                    None,
                    None,
                    ca.certificate.get,
                    ca.certificates.tail,
                    ca.cryptoKeyPair
                  )
                  val c    = Cert(cert.cert, cert.keyPair, ca, false)
                  val cc   = c.enrich()
                  Ok(cc.toJson)
                }
              }
            }
            case None       => FastFuture.successful(BadRequest(Json.obj("error" -> s"No host provided")))
          }
        } recover { case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad certificate : $e")))
        } get
      }
    }

  def selfSignedClientCert(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        Try {
          Json.parse(body.utf8String).\("dn").asOpt[String] match {
            case Some(dn) => {
              env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA).map {
                case None     => {
                  val cert =
                    FakeKeyStore.createSelfSignedClientCertificate(dn, FiniteDuration(365, TimeUnit.DAYS), None, None)
                  val c    = Cert(cert.cert, cert.keyPair, None, true)
                  val cc   = c.enrich()
                  Ok(cc.toJson)
                }
                case Some(ca) => {
                  val cert = FakeKeyStore.createClientCertificateFromCA(
                    dn,
                    FiniteDuration(365, TimeUnit.DAYS),
                    None,
                    None,
                    ca.certificate.get,
                    ca.certificates.tail,
                    ca.cryptoKeyPair
                  )
                  val c    = Cert(cert.cert, cert.keyPair, ca, true)
                  val cc   = c.enrich()
                  Ok(cc.toJson)
                }
              }
            }
            case None     => FastFuture.successful(BadRequest(Json.obj("error" -> s"No cn provided")))
          }
        } recover { case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad certificate : $e")))
        } get
      }
    }

  def importP12File(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      val password = ctx.request.getQueryString("password").getOrElse("")
      val client   = ctx.request.getQueryString("client").contains("true")
      val many     = ctx.request.getQueryString("many").contains("true")
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        Try {
          val certs = P12Helper.extractCertificate(body, password, client)
          if (!many) {
            val cert = certs.head
            Ok(cert.enrich().copy(client = client).toJson).future
          } else {
            Source(certs.toList)
              .mapAsync(1) { cert =>
                val c = cert.enrich().copy(client = client)
                c.save().map(_ => c)
              }
              .runWith(Sink.seq)
              .map { seq =>
                Ok(JsArray(seq.map(_.json)))
              }
          }
        } recover { case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad p12 : $e")))
        } get
      }
    }

  import otoroshi.ssl.SSLImplicits._

  def caCert(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
        Try {
          Json.parse(body.utf8String).\("cn").asOpt[String] match {
            case Some(cn) => {
              // val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
              // keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
              // val keyPair = keyPairGenerator.generateKeyPair()
              val ca    = FakeKeyStore.createCA(s"CN=$cn", FiniteDuration(365, TimeUnit.DAYS), None, None)
              val _cert = Cert(
                id = IdGenerator.token(32),
                name = "none",
                description = "none",
                chain = ca.cert.asPem,
                privateKey = ca.key.asPem,
                caRef = None,
                autoRenew = false,
                client = false,
                exposed = false,
                revoked = false
              ).enrich()
              val cert  = _cert.copy(name = _cert.domain, description = s"Certificate for ${_cert.subject}")
              Ok(cert.toJson)
            }
            case None     => BadRequest(Json.obj("error" -> s"No host provided"))
          }
        } recover { case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
        } get
      }
    }

  def caSignedCert(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        Try {
          (
            Json.parse(body.utf8String).\("id").asOpt[String],
            Json.parse(body.utf8String).\("host").asOpt[String]
          ) match {
            case (Some(id), Some(host)) => {
              env.datastores.certificatesDataStore.findById(id).map {
                case None     => NotFound(Json.obj("error" -> s"No CA found"))
                case Some(ca) => {
                  // val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
                  // keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
                  // val keyPair = keyPairGenerator.generateKeyPair()
                  val cert = FakeKeyStore.createCertificateFromCA(
                    host,
                    FiniteDuration(365, TimeUnit.DAYS),
                    None,
                    None,
                    ca.certificate.get,
                    ca.certificates.tail,
                    ca.cryptoKeyPair
                  )
                  Ok(Cert(cert.cert, cert.keyPair, ca, false).enrich().toJson)
                }
              }
            }
            case _                      => FastFuture.successful(BadRequest(Json.obj("error" -> s"No host provided")))
          }
        } recover { case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad certificate : $e")))
        } get
      }
    }

  def caSignedClientCert(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        Try {
          (Json.parse(body.utf8String).\("id").asOpt[String], Json.parse(body.utf8String).\("dn").asOpt[String]) match {
            case (Some(id), Some(dn)) => {
              env.datastores.certificatesDataStore.findById(id).map {
                case None     => NotFound(Json.obj("error" -> s"No CA found"))
                case Some(ca) => {
                  val cert = FakeKeyStore.createClientCertificateFromCA(
                    dn,
                    FiniteDuration(365, TimeUnit.DAYS),
                    None,
                    None,
                    ca.certificate.get,
                    ca.certificates.tail,
                    ca.cryptoKeyPair
                  )
                  Ok(Cert(cert.cert, cert.keyPair, ca, true).enrich().toJson)
                }
              }
            }
            case _                    => FastFuture.successful(BadRequest(Json.obj("error" -> s"No host provided")))
          }
        } recover { case e =>
          e.printStackTrace()
          FastFuture.successful(BadRequest(Json.obj("error" -> s"Bad certificate : $e")))
        } get
      }
    }

  def renew(id: String) =
    BackOfficeActionAuth.async { ctx =>
      env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
        case None                                  => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
        case Some(cert) if !ctx.canUserWrite(cert) => ApiActionContext.fforbidden
        case Some(cert)                            => cert.renew().map(c => Ok(c.toJson))
      }
    }

  def createLetsEncryptCertificate() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      (ctx.request.body \ "host").asOpt[String] match {
        case None         => FastFuture.successful(BadRequest(Json.obj("error" -> "no domain found in request")))
        case Some(domain) =>
          otoroshi.utils.letsencrypt.LetsEncryptHelper.createCertificate(domain).map {
            case Left(err)   => InternalServerError(Json.obj("error" -> err))
            case Right(cert) => Ok(cert.toJson)
          }
      }
    }

  def createCsr =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      val issuerRef = (ctx.request.body \ "caRef").asOpt[String]

      GenCsrQuery.fromJson(ctx.request.body) match {
        case Left(err)    => BadRequest(Json.obj("error" -> err)).future
        case Right(query) => {
          env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
            issuerRef.flatMap(ref => certificates.find(_.id == ref)) match {
              case None         => BadRequest(Json.obj("error" -> "no issuer defined")).future
              case Some(issuer) => {
                env.pki.genCsr(query, issuer.certificate).map {
                  case Left(err)  => BadRequest(Json.obj("error" -> err))
                  case Right(res) => Ok(res.json)
                }
              }
            }
          }
        }
      }
    }

  def createCertificate =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      val issuerRef = (ctx.request.body \ "caRef").asOpt[String]
      val maybeHost = (ctx.request.body \ "host").asOpt[String]
      val client    = (ctx.request.body \ "client").asOpt[Boolean].getOrElse(false)

      def handle(r: Future[Either[String, GenCertResponse]]): Future[Result] = {
        r.map {
          case Left(err)  => BadRequest(Json.obj("error" -> err))
          case Right(res) => Ok(res.toCert.copy(client = client, autoRenew = true).toJson)
        }
      }

      env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
        val issuer = issuerRef.flatMap(ref => certificates.find(_.id == ref))
        (ctx.request.body \ "letsEncrypt").asOpt[Boolean] match {
          case Some(true) =>
            maybeHost match {
              case None         => BadRequest(Json.obj("error" -> "No domain found !")).future
              case Some(domain) =>
                otoroshi.utils.letsencrypt.LetsEncryptHelper.createCertificate(domain).map {
                  case Left(err)   => InternalServerError(Json.obj("error" -> err))
                  case Right(cert) => Ok(cert.toJson)
                }
            }
          case _          => {
            GenCsrQuery
              .fromJson(ctx.request.body)
              .map(v => v.copy(duration = v.duration * (24 * 60 * 60 * 1000))) match {
              case Left(err)                                        => BadRequest(Json.obj("error" -> err)).future
              case Right(query) if query.ca && issuer.isEmpty       => handle(env.pki.genSelfSignedCA(query))
              case Right(query) if query.ca && issuer.isDefined     =>
                handle(
                  env.pki.genSubCA(
                    query,
                    issuer.get.certificate.get,
                    issuer.get.certificates.tail,
                    issuer.get.cryptoKeyPair.getPrivate()
                  )
                )
              case Right(query) if query.client && issuer.isEmpty   => handle(env.pki.genSelfSignedCert(query))
              case Right(query) if query.client && issuer.isDefined =>
                handle(
                  env.pki.genCert(
                    query,
                    issuer.get.certificate.get,
                    issuer.get.certificates.tail,
                    issuer.get.cryptoKeyPair.getPrivate()
                  )
                )
              case Right(query) if issuer.isEmpty                   => handle(env.pki.genSelfSignedCert(query))
              case Right(query) if issuer.isDefined                 =>
                handle(
                  env.pki.genCert(
                    query,
                    issuer.get.certificate.get,
                    issuer.get.certificates.tail,
                    issuer.get.cryptoKeyPair.getPrivate()
                  )
                )
              case _                                                => BadRequest(Json.obj("error" -> "bad state")).future
            }
          }
        }
      }
    }

  def certificateData(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
        Try {
          val parts: Seq[String] = body.utf8String.split("-----BEGIN CERTIFICATE-----").toSeq
          parts.tail.headOption.map { cert =>
            val content: String = cert.replace("-----END CERTIFICATE-----", "")
            Ok(CertificateData(content))
          } getOrElse {
            Try {
              val content: String =
                body.utf8String.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
              Ok(CertificateData(content))
            } recover { case e =>
              // e.printStackTrace()
              Ok(Json.obj("error" -> s"Bad certificate : $e"))
            } get
          }
        } recover { case e =>
          // e.printStackTrace()
          Ok(Json.obj("error" -> s"Bad certificate : $e"))
        } get
      }
    }

  def certificateIsValid(): Action[Source[ByteString, _]] =
    BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { body =>
        Try {
          Cert.fromJsonSafe(Json.parse(body.utf8String)) match {
            case JsSuccess(cert, _) => Ok(Json.obj("valid" -> cert.isValid))
            case JsError(e)         => BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
          }
        } recover { case e =>
          e.printStackTrace()
          BadRequest(Json.obj("error" -> s"Bad certificate : $e"))
        } get
      }
    }

  def checkExistingLdapConnection(id: String) =
    BackOfficeActionAuth.async { ctx =>
      // env.datastores.authConfigsDataStore.findById(id).flatMap {
      env.proxyState.authModuleAsync(id).flatMap {
        case None                                                           => FastFuture.successful(NotFound(Json.obj("error" -> "auth. config. not found !")))
        case Some(module: LdapAuthModuleConfig) if !ctx.canUserRead(module) => ApiActionContext.fforbidden
        case Some(module: LdapAuthModuleConfig)                             => {
          module.checkConnection().map {
            case (works, error) if works => Ok(Json.obj("works" -> works))
            case (works, error)          => Ok(Json.obj("works" -> works, "error" -> error))
          }
        }
        case Some(_)                                                        => FastFuture.successful(BadRequest(Json.obj("error" -> "auth. config. not LDAP !")))
      }
    }

  def checkLdapConnection() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      if ((ctx.request.body \ "user").isDefined) {
        val username = (ctx.request.body \ "user" \ "username").as[String]
        val password = (ctx.request.body \ "user" \ "password").as[String]
        LdapAuthModuleConfig.fromJson((ctx.request.body \ "config").as[JsValue]) match {
          case Left(e)       => FastFuture.successful(BadRequest(Json.obj("error" -> "bad auth. module. config")))
          case Right(module) => {
            module.bindUser(username, password) match {
              case Left(err)   => FastFuture.successful(Ok(Json.obj("works" -> false, "error" -> err)))
              case Right(user) => FastFuture.successful(Ok(Json.obj("works" -> true, "user" -> user.asJson)))
            }
          }
        }
      } else {
        LdapAuthModuleConfig.fromJson(ctx.request.body) match {
          case Left(e)                                   => FastFuture.successful(BadRequest(Json.obj("error" -> "bad auth. module. config")))
          case Right(module) if !ctx.canUserRead(module) => ApiActionContext.fforbidden
          case Right(module)                             => {
            module.checkConnection().map {
              case (works, error) if works => Ok(Json.obj("works" -> works))
              case (works, error)          => Ok(Json.obj("works" -> works, "error" -> error))
            }
          }
        }
      }
    }

  def fetchGroupsAndServices() =
    BackOfficeActionAuth.async { ctx =>
      for {
        rgroups            <- env.proxyState.allServiceGroups().vfuture
        rservices          <- env.proxyState.allServices().vfuture
        rroutes            <- env.proxyState.allRoutes().vfuture
        rrouteCompositions <- env.proxyState.allNgServices().vfuture
      } yield {
        val groups            = rgroups
          .filter(ctx.canUserRead)
          .map(g => Json.obj("label" -> g.name, "value" -> s"group_${g.id}", "kind" -> "group"))
        val services          = rservices
          .filter(ctx.canUserRead)
          .map(g => Json.obj("label" -> g.name, "value" -> s"service_${g.id}", "kind" -> "service"))
        val routes            = rroutes
          .filter(ctx.canUserRead)
          .map(g => Json.obj("label" -> g.name, "value" -> s"route_${g.id}", "kind" -> "route"))
        val routeCompositions = rrouteCompositions
          .filter(ctx.canUserRead)
          .map(g => Json.obj("label" -> g.name, "value" -> s"route-composition_${g.id}", "kind" -> "route-composition"))
        Ok(JsArray(groups ++ services ++ routes ++ routeCompositions))
      }
    }

  def findServiceLike(serviceId: String): Future[Option[ServiceLike]] = {
    env.datastores.serviceDescriptorDataStore.findById(serviceId) flatMap {
      case Some(service) => ServiceLike.fromService(service).some.vfuture
      case None          =>
        env.datastores.routeDataStore.findById(serviceId) flatMap {
          case Some(service) => ServiceLike.fromRoute(service).some.vfuture
          case None          =>
            env.datastores.routeCompositionDataStore.findById(serviceId) map {
              case Some(service) => ServiceLike.fromRouteComposition(service).some
              case None          => None
            }
        }
    }
  }

  def fetchApikeysForGroupAndService(serviceId: String) =
    BackOfficeActionAuth.async { ctx =>
      findServiceLike(serviceId) flatMap {
        case None                                       => FastFuture.successful(NotFound(Json.obj("error" -> "service not found")))
        case Some(service) if !ctx.canUserRead(service) => ApiActionContext.fforbidden
        case Some(service)                              => {
          env.datastores.apiKeyDataStore.findAll().map { apikeys =>
            val filtered = apikeys
              .filter(ctx.canUserRead)
              .filter(_.authorizedOnServiceOrGroups(service.id, service.groups))
            Ok(JsArray(filtered.map(_.toJson)))
          }
        }
      }
    }

  def checkElasticsearchConnection() = BackOfficeActionAuth.async(parse.json) { ctx =>
    ElasticAnalyticsConfig.read(ctx.request.body) match {
      case None         => Ok(Json.obj("none" -> true)).future
      case Some(config) => {
        val read = new ElasticReadsAnalytics(config, env)
        for {
          version <- read.checkVersion()
          search  <- read.checkSearch()
        } yield {
          val versionJson = version match {
            case Left(err) => Json.obj("error" -> err)
            case Right(v)  => JsString(v)
          }
          val searchJson  = search match {
            case Left(err) => Json.obj("error" -> err)
            case Right(v)  => JsNumber(v)
          }
          Ok(
            Json.obj(
              "version" -> versionJson,
              "search"  -> searchJson
            )
          )
        }
      }
    }
  }

  def applyElasticsearchTemplate() = BackOfficeActionAuth.async(parse.json) { ctx =>
    ElasticAnalyticsConfig.read(ctx.request.body) match {
      case None         => Ok(Json.obj("error" -> "bad configuration")).future
      case Some(config) => {
        // val read = new ElasticReadsAnalytics(config, env)
        for {
          res <- ElasticUtils.applyTemplate(config, logger, env).map(_ => Right(())).recover { case t: Throwable =>
                   Left(t)
                 }
        } yield {
          res match {
            case Left(t)      => InternalServerError(Json.obj("error" -> t.getMessage))
            case Right(value) => Ok(Json.obj("done" -> true))
          }
        }
      }
    }
  }

  def elasticTemplate() = BackOfficeActionAuth.async(parse.json) { ctx =>
    ElasticAnalyticsConfig.read(ctx.request.body) match {
      case None         => Ok(Json.obj("error" -> "bad configuration")).future
      case Some(config) => {
        val index: String = config.index.getOrElse("otoroshi-events")
        for {
          version <- ElasticUtils.getElasticVersion(config, env)
        } yield {
          val strTpl: String   = version match {
            case ElasticVersion.UnderSeven      => ElasticTemplates.indexTemplate_v6
            case ElasticVersion.AboveSeven      => ElasticTemplates.indexTemplate_v7
            case ElasticVersion.AboveSevenEight => ElasticTemplates.indexTemplate_v7_8
          }
          val template: String = if (config.indexSettings.clientSide) {
            strTpl
              .replace("$$$INDEX$$$", index)
              .replace("$$$SHARDS$$$", config.indexSettings.numberOfShards.toString)
              .replace("$$$REPLICAS$$$", config.indexSettings.numberOfReplicas.toString)
          } else {
            strTpl
              .replace("$$$INDEX$$$-*", index)
              .replace("$$$SHARDS$$$", config.indexSettings.numberOfShards.toString)
              .replace("$$$REPLICAS$$$", config.indexSettings.numberOfReplicas.toString)
          }
          Ok(Json.obj("template" -> template))
        }
      }
    }
  }

  def elasticVersion() = BackOfficeActionAuth.async(parse.json) { ctx =>
    ElasticAnalyticsConfig.read(ctx.request.body) match {
      case None         => Ok(Json.obj("error" -> "bad configuration")).future
      case Some(config) => {
        val index: String = config.index.getOrElse("otoroshi-events")
        for {
          version <- ElasticUtils.checkVersion(config, env)
        } yield {
          version match {
            case Left(err) => InternalServerError(Json.obj("error" -> err))
            case Right(v)  => Ok(Json.obj("version" -> v))
          }
        }
      }
    }
  }

  def updateUiMode() = BackOfficeActionAuth.async(parse.json) { ctx =>
    implicit val reqh = ctx.request
    val mode          = ctx.request.body.select("mode").asOpt[String].getOrElse("dark")
    NoContent.addingToSession("ui-mode" -> mode).future
  }

  def graphqlProxy() = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    val url     = ctx.request.queryString.get("url").map(_.last).get
    val host    = Uri(url).authority.host.toString()
    val headers = (ctx.request.headers.toSimpleMap ++ Map("Host" -> host)).toSeq

    val builder = env.Ws
      .url(url)
      .withHttpHeaders(headers: _*)
      .withFollowRedirects(false)
      .withMethod(ctx.request.method)
      .withQueryStringParameters(ctx.request.queryString.toSeq.filterNot(_._1 == "url").map(t => (t._1, t._2.head)): _*)

    val builderWithBody = if (otoroshi.utils.body.BodyUtils.hasBody(ctx.request)) {
      builder.withBody(SourceBody(ctx.request.body))
    } else {
      builder
    }

    builderWithBody
      .execute()
      .fast
      .map { res =>
        Results
          .Status(res.status)(res.body)
          .withHeaders(res.headers.mapValues(_.last).toSeq.filterNot(_._1 == "Content-Type"): _*)
          .as(res.contentType)
      }
  }

  def routeEntries(routeId: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.routeDataStore.findById(routeId) flatMap {
      case None        => NotFound(Json.obj("error" -> "route not found")).future
      case Some(route) =>
        val isSecured = route.plugins.slots.exists(p => p.plugin.contains("ForceHttpsTraffic"))

        Ok(Json.obj("entries" -> route.frontend.domains.map(d => {
          Uri(s"http://${d.raw}")
            .withScheme(if (isSecured) "https" else "http")
            .withPort(if (isSecured) env.exposedHttpsPortInt else env.exposedHttpPortInt)
            .toString()
        }))).future
    }
  }

  def graphQLToJson() = BackOfficeActionAuth.async(parse.json) { ctx =>
    val schema = ctx.request.body.select("schema").asOpt[String].getOrElse("{}")

    sangria.parser.QueryParser.parse(schema) match {
      case scala.util.Failure(exception)   => BadRequest(Json.obj("error" -> exception.getMessage)).future
      case scala.util.Success(astDocument) =>
        val res = GraphQLFormats.astDocumentToJson(astDocument)

        Ok(Json.obj("types" -> res)).future
    }
  }

  def jsonToGraphqlSchema() = BackOfficeActionAuth.async(parse.json) { ctx =>
    import sangria.schema.Schema

    val types = ctx.request.body
      .select("types")
      .as[JsArray]
      .value
      .map(GraphQLFormats.objectTypeDefinitionFmt.reads)
      .flatMap {
        case JsSuccess(v, _) => Some(v)
        case JsError(_)      => None
      }

    val schema = ctx.request.body.select("schema").asOpt[String].getOrElse("{}")

    Try {
      sangria.parser.QueryParser.parse(schema) match {
        case scala.util.Failure(exception)   => BadRequest(Json.obj("error" -> exception.getMessage)).future
        case scala.util.Success(astDocument) =>
          val generatedSchema = Schema.buildFromAst(astDocument)
          val document        = generatedSchema.toAst

          val newDocument = document.copy(
            definitions = document.definitions.flatMap {
              case _: sangria.ast.TypeDefinition          => None
              case _: sangria.ast.InterfaceTypeDefinition => None
              case v                                      => Some(v)
            } ++ types
          )

          Ok(
            Json.obj(
              "schema" -> Schema.buildFromAst(newDocument).renderPretty
            )
          ).future
      }
    } recover { case e =>
      BadRequest(
        Json.obj(
          "error" -> e.getMessage
        )
      ).future
    } get
  }

  def toYaml = BackOfficeActionAuth(parse.json) { ctx =>
    Ok(Yaml.write(ctx.request.body)).as("application/yaml")
  }

  def wasmFiles() = BackOfficeActionAuth.async { ctx =>
    env.datastores.globalConfigDataStore
      .singleton()
      .flatMap { globalConfig =>
        globalConfig.wasmManagerSettings match {
          case Some(WasmManagerSettings(url, clientId, clientSecret, pluginsFilter)) =>
            Try {
              env.Ws
                .url(s"$url/plugins")
                .withFollowRedirects(false)
                .withHttpHeaders(
                  "Otoroshi-Client-Id"     -> clientId,
                  "Otoroshi-Client-Secret" -> clientSecret,
                  "kind"                   -> pluginsFilter.getOrElse("*")
                )
                .get()
                .map(res => {
                  if (res.status == 200) {
                    Ok(res.json)
                  } else {
                    Ok(Json.arr())
                  }
                })
                .recover { case e: Throwable =>
                  logger.error(e.getMessage)
                  Ok(Json.arr())
                }
            } match {
              case Failure(err) => Ok(Json.arr()).vfuture
              case Success(v)   => v
            }
          case _                                                                     =>
            BadRequest(
              Json.obj(
                "error" -> "Missing config in global configuration"
              )
            ).future
        }
      }
  }

  def anonymousReporting() = BackOfficeActionAuth.async(parse.json) { ctx =>
    val enabled = ctx.request.body.select("enabled").asOpt[Boolean].getOrElse(false)
    if (enabled) {
      env.datastores.globalConfigDataStore
        .set(env.datastores.globalConfigDataStore.latest().copy(anonymousReporting = true))
        .map { _ =>
          NoContent
        }
    } else {
      env.datastores.rawDataStore
        .set(
          s"${env.storageRoot}:backoffice:anonymous-reporting-refused",
          DateTime.now().toString().byteString,
          Some(30.days.toMillis)
        )
        .map { _ =>
          NoContent
        }
    }
  }
}
