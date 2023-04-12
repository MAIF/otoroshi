package otoroshi.plugins.authcallers

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{GlobalConfig, ServiceDescriptor}
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.script._
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.mvc.{Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

class ForceRetryException(token: String)
    extends RuntimeException(
      "Unauthorized call on OAuth2 API, new token generated, add retry on your service to avoid seeing this message !"
    )
    with NoStackTrace

sealed trait OAuth2Kind {
  def name: String
}

object OAuth2Kind {
  case object ClientCredentials extends OAuth2Kind { def name: String = "client_credentials" }
  case object Password          extends OAuth2Kind { def name: String = "password"           }
}

case class OAuth2CallerConfig(
    kind: OAuth2Kind,
    url: String,
    method: String,
    headerName: String,
    headerValueFormat: String,
    jsonPayload: Boolean,
    clientId: String,
    clientSecret: String,
    scope: Option[String],
    audience: Option[String],
    user: Option[String],
    password: Option[String],
    cacheTokenSeconds: FiniteDuration,
    tlsConfig: MtlsConfig
)

object OAuth2CallerConfig {
  def parse(json: JsValue): OAuth2CallerConfig = {
    OAuth2CallerConfig(
      kind = json
        .select("kind")
        .asOpt[String]
        .map {
          case "client_credentials" => OAuth2Kind.ClientCredentials
          case _                    => OAuth2Kind.Password
        }
        .getOrElse(OAuth2Kind.ClientCredentials),
      url = json.select("url").asOpt[String].getOrElse("https://127.0.0.1:8080/oauth/token"),
      method = json.select("method").asOpt[String].getOrElse("POST"),
      headerName = json.select("headerName").asOpt[String].getOrElse("Authorization"),
      headerValueFormat = json.select("headerValueFormat").asOpt[String].getOrElse("Bearer %s"),
      jsonPayload = json.select("jsonPayload").asOpt[Boolean].getOrElse(false),
      clientId = json.select("clientId").asOpt[String].getOrElse("--"),
      clientSecret = json.select("clientSecret").asOpt[String].getOrElse("--"),
      scope = json.select("scope").asOpt[String].filter(_.nonEmpty),
      audience = json.select("audience").asOpt[String].filter(_.nonEmpty),
      user = json.select("user").asOpt[String].filter(_.nonEmpty),
      password = json.select("password").asOpt[String].filter(_.nonEmpty),
      cacheTokenSeconds = json.select("cacheTokenSeconds").asOpt[Long].getOrElse(10L * 60L).seconds,
      tlsConfig = MtlsConfig.read(json.select("tlsConfig").asOpt[JsValue])
    )
  }
}

// MIGRATED
class OAuth2Caller extends RequestTransformer {

  override def name: String = "OAuth2 caller"

  override def description: Option[String] =
    s"""This plugin can be used to call api that are authenticated using OAuth2 client_credential/password flow.
       |Do not forget to enable client retry to handle token generation on expire.
       |
       |This plugin accepts the following configuration
       |
       |${Json.prettyPrint(defaultConfig.get)}
       |""".stripMargin.some

  override def defaultConfig: Option[JsObject] = Json
    .obj(
      "kind"              -> "the oauth2 flow, can be 'client_credentials' or 'password'",
      "url"               -> "https://127.0.0.1:8080/oauth/token",
      "method"            -> "POST",
      "headerName"        -> "Authorization",
      "headerValueFormat" -> "Bearer %s",
      "jsonPayload"       -> false,
      "clientId"          -> "the client_id",
      "clientSecret"      -> "the client_secret",
      "scope"             -> "an optional scope",
      "audience"          -> "an optional audience",
      "user"              -> "an optional username if using password flow",
      "password"          -> "an optional password if using password flow",
      "cacheTokenSeconds" -> "the number of second to wait before asking for a new token",
      "tlsConfig"         -> "an optional TLS settings object"
    )
    .some

  override def configRoot: Option[String] = "OAuth2Caller".some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  def getToken(key: String, config: OAuth2CallerConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[(String, Int), String]] = {
    val body: String = config.kind match {
      case OAuth2Kind.ClientCredentials if config.jsonPayload =>
        Json
          .obj(
            "client_id"     -> config.clientId,
            "client_secret" -> config.clientSecret,
            "grant_type"    -> "client_credentials"
          )
          .applyOnWithOpt(config.scope) { (json, scope) => json ++ Json.obj("scope" -> scope) }
          .applyOnWithOpt(config.audience) { (json, audience) => json ++ Json.obj("audience" -> audience) }
          .stringify
      case OAuth2Kind.Password if config.jsonPayload          =>
        val user: String     = config.user.getOrElse("--")
        val password: String = config.password.getOrElse("--")
        Json
          .obj(
            "client_id"     -> config.clientId,
            "client_secret" -> config.clientSecret,
            "grant_type"    -> "password",
            "username"      -> user,
            "password"      -> password
          )
          .applyOnWithOpt(config.scope) { (json, scope) => json ++ Json.obj("scope" -> scope) }
          .applyOnWithOpt(config.audience) { (json, audience) => json ++ Json.obj("audience" -> audience) }
          .stringify
      case OAuth2Kind.ClientCredentials                       =>
        s"client_id=${config.clientId}&client_secret=${config.clientSecret}&grant_type=client_credentials${config.scope
          .map(s => s"&scope=$s")
          .getOrElse("")}${config.audience.map(s => s"&audience=$s").getOrElse("")}"
      case OAuth2Kind.Password                                =>
        s"client_id=${config.clientId}&client_secret=${config.clientSecret}&grant_type=password&username=${config.user
          .getOrElse("--")}&password=${config.password.getOrElse("--")}${config.scope
          .map(s => s"&scope=$s")
          .getOrElse("")}${config.audience.map(s => s"&audience=$s").getOrElse("")}"
    }
    val ctype        = if (config.jsonPayload) "application/json" else "application/x-www-form-urlencoded"
    env.MtlsWs
      .url(config.url, config.tlsConfig)
      .withMethod(config.method)
      .withHttpHeaders("Content-Type" -> ctype)
      .withBody(body)
      .execute()
      .flatMap { resp =>
        val respBody = resp.body
        if (resp.status == 200) {
          if (resp.contentType.toLowerCase().equals("application/x-www-form-urlencoded")) {
            val body             = resp.body
              .split("&")
              .map { p =>
                val parts = p.split("=")
                (parts.head, parts.last)
              }
              .toMap
            val jsonBody         = JsObject(body.mapValues(JsString.apply))
            val token            = body.getOrElse("access_token", "--")
            val expires_in: Long = body.getOrElse("expires_in", config.cacheTokenSeconds.toSeconds.toString).toLong
            val expiration_date  = DateTime.now().plusSeconds(expires_in.toInt).toDate.getTime
            val newjsonBody      = jsonBody.as[JsObject] ++ Json.obj("expiration_date" -> expiration_date)
            env.datastores.rawDataStore
              .set(key, ByteString(newjsonBody.stringify), expires_in.seconds.toMillis.some)
              .map { _ =>
                Right(token)
              }
          } else {
            val bodyJson         = resp.json
            val token            = bodyJson.select("access_token").as[String]
            val expires_in: Long =
              bodyJson.select("expires_in").asOpt[Long].getOrElse(config.cacheTokenSeconds.toSeconds)
            val expiration_date  = DateTime.now().plusSeconds(expires_in.toInt).toDate.getTime
            val newjsonBody      = bodyJson.as[JsObject] ++ Json.obj("expiration_date" -> expiration_date)
            env.datastores.rawDataStore
              .set(key, ByteString(newjsonBody.stringify), expires_in.seconds.toMillis.some)
              .map { _ =>
                Right(token)
              }
          }
        } else {
          Left((respBody, resp.status)).future
        }
      }
  }

  def fetchRefreshTheToken(
      refreshToken: String,
      config: OAuth2CallerConfig
  )(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val ctype   = if (config.jsonPayload) "application/json" else "application/x-www-form-urlencoded"
    val builder =
      env.MtlsWs
        .url(config.url, config.tlsConfig)
        .withMethod(config.method)
        .withHttpHeaders("Content-Type" -> ctype)
    val future1 = if (config.jsonPayload) {
      builder.post(
        Json
          .obj(
            "refresh_token" -> refreshToken,
            "grant_type"    -> "refresh_token",
            "client_id"     -> config.clientId,
            "client_secret" -> config.clientSecret
          )
          .applyOnWithOpt(config.scope) { (json, scope) => json ++ Json.obj("scope" -> scope) }
          .applyOnWithOpt(config.audience) { (json, audience) => json ++ Json.obj("audience" -> audience) }
      )
    } else {
      builder.post(
        Map(
          "refresh_token" -> refreshToken,
          "grant_type"    -> "refresh_token",
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret
        )
          .applyOnWithOpt(config.scope) { (json, scope) => json ++ Map("scope" -> scope) }
          .applyOnWithOpt(config.audience) { (json, audience) => json ++ Map("audience" -> audience) }
      )(writeableOf_urlEncodedSimpleForm)
    }
    // TODO: check status code
    future1.map(_.json).map { json =>
      val rtok      = json.select("refresh_token").asOpt[String].getOrElse(refreshToken)
      val tokenbody = json.as[JsObject] ++ Json.obj("refresh_token" -> rtok)
      tokenbody
    }
  }

  def tryRenewToken(key: String, config: OAuth2CallerConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, String]] = {
    env.datastores.rawDataStore.get(key).flatMap {
      case None            => Left("no token found !").vfuture
      case Some(tokenBody) => {
        val tokenBodyJson = tokenBody.utf8String.parseJson.asObject
        tokenBodyJson.select("refresh_token").asOpt[String] match {
          case None               => Left("no refresh_token found !").vfuture
          case Some(refreshToken) =>
            fetchRefreshTheToken(refreshToken, config).flatMap { newTokenBody =>
              val rtok             = newTokenBody.select("refresh_token").asOpt[String].getOrElse(refreshToken)
              val expires_in: Long =
                newTokenBody.select("expires_in").asOpt[Long].getOrElse(config.cacheTokenSeconds.toSeconds)
              val expiration_date  = DateTime.now().plusSeconds(expires_in.toInt).toDate.getTime
              val newnewTokenBody  =
                newTokenBody.as[JsObject] ++ Json.obj("refresh_token" -> rtok, "expiration_date" -> expiration_date)
              val token            = newnewTokenBody.select("access_token").as[String]
              env.datastores.rawDataStore
                .set(key, ByteString(newnewTokenBody.stringify), expires_in.seconds.toMillis.some)
                .map { _ =>
                  Right(token)
                }
            }
        }
      }
    }
  }

  def isAlmostComplete(tokenBody: JsValue, config: OAuth2CallerConfig): Boolean = {
    val expires_in = tokenBody.select("expires_in").asOpt[Long].getOrElse(config.cacheTokenSeconds.toSeconds)
    val limit      = expires_in.toDouble * 0.1
    tokenBody.select("expiration_date").asOpt[Long] match {
      case None                      => false
      case Some(expiration_date_lng) => {
        val expiration_date = new DateTime(expiration_date_lng)
        val dur             = new org.joda.time.Duration(DateTime.now(), expiration_date)
        dur.toStandardSeconds.getSeconds <= limit
      }
    }
  }

  def computeKey(env: Env, config: OAuth2CallerConfig, descriptor: ServiceDescriptor): String =
    s"${env.storageRoot}:plugins:oauth-caller-plugin:${config.kind.name}:${config.url}:${config.clientId}:${config.user
      .getOrElse("--")}:${config.password.getOrElse("--")}:${descriptor.id}"

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = OAuth2CallerConfig.parse(ctx.configFor(configRoot.get))
    val key    = computeKey(env, config, ctx.descriptor)
    env.datastores.rawDataStore.get(key).flatMap {
      case Some(tokenBody) =>
        val jsonToken = tokenBody.utf8String.parseJson
        val token     = jsonToken.select("access_token").asString
        if (isAlmostComplete(jsonToken, config)) {
          tryRenewToken(key, config)
        }
        Right(
          ctx.otoroshiRequest
            .copy(headers = ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token)))
        ).future
      case None            =>
        getToken(key, config).map {
          case Left((body, status)) =>
            Left(
              Results.Unauthorized(
                Json.obj("error" -> "unauthorized", "error_description" -> body, "error_status" -> status)
              )
            )
          case Right(token)         =>
            Right(
              ctx.otoroshiRequest.copy(headers =
                ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token))
              )
            )
        }
    }
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val config = OAuth2CallerConfig.parse(ctx.configFor(configRoot.get))
    val key    = computeKey(env, config, ctx.descriptor)
    if (ctx.otoroshiResponse.status == 401) {
      tryRenewToken(key, config).flatMap {
        case Left(_)      => {
          getToken(key, config).flatMap {
            case Left((body, status)) =>
              Left(
                Results.Unauthorized(
                  Json.obj("error" -> "unauthorized", "error_description" -> body, "error_status" -> status)
                )
              ).vfuture
            case Right(token)         => Future.failed[Either[Result, HttpResponse]](new ForceRetryException(token))
          }
        }
        case Right(token) => Future.failed[Either[Result, HttpResponse]](new ForceRetryException(token))
      }
    } else {
      Right(ctx.otoroshiResponse).future
    }
  }
}

case class BasicAuthCallerConfig(username: String, password: String, headerName: String, headerValueFormat: String)

// MIGRATED
object BasicAuthCallerConfig {
  def parse(json: JsValue): BasicAuthCallerConfig = {
    BasicAuthCallerConfig(
      username = json.select("username").asOpt[String].filter(_.nonEmpty).getOrElse("--"),
      password = json.select("password").asOpt[String].filter(_.nonEmpty).getOrElse("--"),
      headerName = json.select("headerName").asOpt[String].getOrElse("Authorization"),
      headerValueFormat = json.select("headerValueFormat").asOpt[String].getOrElse("Basic %s")
    )
  }
}

class BasicAuthCaller extends RequestTransformer {

  override def name: String = "Basic Auth. caller"

  override def description: Option[String] =
    s"""This plugin can be used to call api that are authenticated using basic auth.
      |
      |This plugin accepts the following configuration
      |
      |${Json.prettyPrint(defaultConfig.get)}
      |""".stripMargin.some

  override def defaultConfig: Option[JsObject] = Json
    .obj(
      "username"          -> "the_username",
      "password"          -> "the_password",
      "headerName"        -> "Authorization",
      "headerValueFormat" -> "Basic %s"
    )
    .some

  override def configRoot: Option[String] = "BasicAuthCaller".some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config        = BasicAuthCallerConfig.parse(ctx.configFor(configRoot.get))
    val token: String = ByteString(s"${config.username}:${config.password}").encodeBase64.utf8String
    Right(
      ctx.otoroshiRequest.copy(headers =
        ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token))
      )
    ).future
  }
}
