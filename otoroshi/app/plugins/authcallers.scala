package otoroshi.plugins.authcallers

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.ServiceDescriptor
import otoroshi.script._
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

class ForceRetryException(token: String) extends RuntimeException("Unauthorized call on OAuth2 API, new token generated !") with NoStackTrace

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
      kind = json.select("kind").asOpt[String].map {
        case "client_credentials" => OAuth2Kind.ClientCredentials
        case _ => OAuth2Kind.Password
      }.getOrElse(OAuth2Kind.ClientCredentials),
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
      tlsConfig = MtlsConfig.read(json.select("tlsConfig").asOpt[JsValue]),
    )
  }
}

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

  override def defaultConfig: Option[JsObject] = Json.obj(
    "kind" -> "the oauth2 flow, can be 'client_credentials' or 'password'",
    "url" -> "https://127.0.0.1:8080/oauth/token",
    "method" -> "POST",
    "headerName" -> "Authorization",
    "headerValueFormat" -> "Bearer %s",
    "jsonPayload" -> false,
    "clientId" -> "the client_id",
    "clientSecret" -> "the client_secret",
    "scope" -> "an optional scope",
    "audience" -> "an optional audience",
    "user" -> "an optional username if using password flow",
    "password" -> "an optional password if using password flow",
    "cacheTokenSeconds" -> "the number of second to wait before asking for a new token",
    "tlsConfig" -> "an optional TLS settings object"
  ).some

  override def configRoot: Option[String] = "OAuth2Caller".some

  def getToken(key: String, config: OAuth2CallerConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[(String, Int), String]] = {
    val body: String = config.kind match {
      case OAuth2Kind.ClientCredentials if config.jsonPayload => (Json.obj("client_id" -> config.clientId, "client_secret" -> config.clientSecret, "grant_type" -> "client_credentials").applyOnWithOpt(config.scope) { (json, scope) => json ++ Json.obj("scope" -> scope) }.applyOnWithOpt(config.audience) { (json, audience) => json ++ Json.obj("audience" -> audience) }).stringify
      case OAuth2Kind.Password          if config.jsonPayload => (Json.obj("client_id" -> config.clientId, "client_secret" -> config.clientSecret, "grant_type" -> "password", "username" -> config.user.getOrElse("--"), "password" -> config.password.getOrElse("--")).applyOnWithOpt(config.scope) { (json, scope) => json ++ Json.obj("scope" -> scope) }.applyOnWithOpt(config.audience) { (json, audience) => json ++ Json.obj("audience" -> audience) }).stringify
      case OAuth2Kind.ClientCredentials => s"client_id=${config.clientId}&client_secret=${config.clientSecret}&grant_type=client_credentials${config.scope.map(s => s"&scope=$s").getOrElse("")}${config.audience.map(s => s"&audience=$s").getOrElse("")}"
      case OAuth2Kind.Password          => s"client_id=${config.clientId}&client_secret=${config.clientSecret}&grant_type=password&username=${config.user.getOrElse("--")}&password=${config.password.getOrElse("--")}${config.scope.map(s => s"&scope=$s").getOrElse("")}${config.audience.map(s => s"&audience=$s").getOrElse("")}"
    }
    val ctype = if (config.jsonPayload) "application/json" else "application/x-www-form-urlencoded"
    env.MtlsWs.url(config.url, config.tlsConfig)
      .withMethod(config.method)
      .withHttpHeaders("Content-Type" -> ctype)
      .withBody(body)
      .execute()
      .flatMap { resp =>
        val respBody = resp.body
        if (resp.status == 200) {
          if (resp.contentType.toLowerCase().equals("application/x-www-form-urlencoded")) {
            val body = resp.body.split("&").map{ p =>
              val parts = p.split("=")
              (parts.head, parts.last)
            }.toMap
            val token = body.getOrElse("access_token", "--")
            val expires_in: Long = body.getOrElse("expires_in", config.cacheTokenSeconds.toSeconds.toString).toLong
            env.datastores.rawDataStore.set(key, ByteString(token), expires_in.seconds.toMillis.some).map { _ =>
              Right(token)
            }
          } else {
            val token = resp.json.select("access_token").as[String]
            val expires_in: Long = resp.json.select("expires_in").asOpt[Long].getOrElse(config.cacheTokenSeconds.toSeconds)
            env.datastores.rawDataStore.set(key, ByteString(token), expires_in.seconds.toMillis.some).map { _ =>
              Right(token)
            }
          }
        } else {
          Left((respBody, resp.status)).future
        }
      }
  }

  def computeKey(env: Env, config: OAuth2CallerConfig, descriptor: ServiceDescriptor): String = s"${env.storageRoot}:plugins:oauth-caller-plugin:${config.kind.name}:${config.url}:${config.clientId}:${config.user.getOrElse("--")}:${config.password.getOrElse("--")}:${descriptor.id}"

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = OAuth2CallerConfig.parse(ctx.configFor(configRoot.get))
    val key = computeKey(env, config, ctx.descriptor)
    env.datastores.rawDataStore.get(key).flatMap {
      case Some(token) => Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token.utf8String)))).future
      case None => getToken(key, config).map {
        case Left((body, status)) => Left(Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> body, "error_status" -> status)))
        case Right(token)         => Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token))))
      }
    }
  }

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val config = OAuth2CallerConfig.parse(ctx.configFor(configRoot.get))
    val key = computeKey(env, config, ctx.descriptor)
    if (ctx.otoroshiResponse.status == 401) {
      getToken(key, config).map {
        case Left((body, status)) => Left(Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> body, "error_status" -> status)))
        case Right(token)         => throw new ForceRetryException(token)
      }
    } else {
      Right(ctx.otoroshiResponse).future
    }
  }
}

case class BasicAuthCallerConfig(username: String, password: String, headerName: String, headerValueFormat: String)

object BasicAuthCallerConfig {
  def parse(json: JsValue): BasicAuthCallerConfig = {
    BasicAuthCallerConfig(
      username = json.select("username").asOpt[String].filter(_.nonEmpty).getOrElse("--"),
      password = json.select("password").asOpt[String].filter(_.nonEmpty).getOrElse("--"),
      headerName = json.select("headerName").asOpt[String].getOrElse("Authorization"),
      headerValueFormat = json.select("headerValueFormat").asOpt[String].getOrElse("Basic %s"),
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

  override def defaultConfig: Option[JsObject] = Json.obj(
    "username" -> "the_username",
    "password" -> "the_password",
    "headerName" -> "Authorization",
    "headerValueFormat" -> "Basic %s"
  ).some

  override def configRoot: Option[String] = "BasicAuthCaller".some

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = BasicAuthCallerConfig.parse(ctx.configFor(configRoot.get))
    val token: String = ByteString(s"${config.username}:${config.password}").encodeBase64.utf8String
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token)))).future
  }
}