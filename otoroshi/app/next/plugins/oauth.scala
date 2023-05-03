package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.google.common.base.Charsets
import org.joda.time.DateTime
import otoroshi.auth.Oauth1AuthModule.encodeURI
import otoroshi.env.Env
import otoroshi.models.{GlobalConfig, PrivateAppsUser, ServiceDescriptor}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.plugins.authcallers.{ForceRetryException, OAuth2Kind}
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterString, BetterSyntax}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.mvc.Results.BadRequest
import play.api.mvc.{AnyContent, Request, RequestHeader, Result, Results}
import play.utils.UriEncoding

import java.util.Base64
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

object OAuth1Caller {

  object Keys {
    val consumerKey     = "oauth_consumer_key"
    val consumerSecret  = "oauth_consumer_secret"
    val signatureMethod = "oauth_signature_method"
    val signature       = "oauth_signature"
    val timestamp       = "oauth_timestamp"
    val nonce           = "oauth_nonce"
    val token           = "oauth_token"
    val tokenSecret     = "oauth_token_secret"
  }

  val Algo = Map(
    "HMAC-SHA1"   -> "HmacSHA1",
    "HMAC-SHA256" -> "HmacSHA256",
    "HMAC-SHA384" -> "HmacSHA384",
    "HMAC-SHA512" -> "HmacSHA512",
    "TEXTPLAIN"   -> "TEXTPLAIN"
  )
}

case class OAuth1CallerConfig(
    consumerKey: Option[String] = None,
    consumerSecret: Option[String] = None,
    token: Option[String] = None,
    tokenSecret: Option[String] = None,
    algo: Option[String] = None
) extends NgPluginConfig {
  override def json: JsValue = OAuth1CallerConfig.format.writes(this)
}

object OAuth1CallerConfig {
  val format: Format[OAuth1CallerConfig] = new Format[OAuth1CallerConfig] {
    override def writes(o: OAuth1CallerConfig): JsValue = Json.obj(
      "consumerKey"    -> o.consumerKey,
      "consumerSecret" -> o.consumerSecret,
      "token"          -> o.token,
      "tokenSecret"    -> o.tokenSecret,
      "algo"           -> o.algo
    )

    override def reads(json: JsValue): JsResult[OAuth1CallerConfig] = Try {
      OAuth1CallerConfig(
        consumerKey = json.select("consumerKey").asOpt[String],
        consumerSecret = json.select("consumerSecret").asOpt[String],
        token = json.select("token").asOpt[String],
        tokenSecret = json.select("tokenSecret").asOpt[String],
        algo = json.select("algo").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage())
      case Success(v) => JsSuccess(v)
    }
  }
}

class OAuth1Caller extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-oauth1-caller-plugin")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def transformsError: Boolean                    = false
  override def defaultConfigObject: Option[NgPluginConfig] = OAuth1CallerConfig().some
  override def name: String                                = "OAuth1 caller"
  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call api that are authenticated using OAuth1.
         | Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
         | or via the configuration of this plugin.```
      """.stripMargin
    )
  }

  private def signRequest(
      verb: String,
      path: String,
      params: Seq[(String, String)],
      consumerSecret: String,
      oauthTokenSecret: String,
      algo: String
  ): String = {
    val sortedEncodedParams = encodeURI(
      params.sortBy(_._1).map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")
    )
    val encodedURL          = encodeURI(path)

    val base      = s"$verb&$encodedURL&$sortedEncodedParams"
    val key       = s"${encodeURI(consumerSecret)}&${Some(oauthTokenSecret).map(encodeURI).getOrElse("")}"
    val signature = Base64.getEncoder.encodeToString(Signatures.hmac(OAuth1Caller.Algo(algo), base, key))

    encodeURI(signature)
  }

  private def encode(param: String): String = UriEncoding.encodePathSegment(param, Charsets.UTF_8)

  def prepareParameters(params: Seq[(String, String)]): String = params
    .map { case (k, v) => (encode(k), encode(v)) }
    .sortBy(identity)
    .map { case (k, v) => s"$k=$v" }
    .mkString("&")

  def getOauthParams(
      consumerKey: String,
      consumerSecret: String,
      tokenSecret: Option[String] = None,
      signatureMethod: String
  ): Map[String, String] =
    Map(
      OAuth1Caller.Keys.consumerKey     -> consumerKey,
      OAuth1Caller.Keys.signatureMethod -> signatureMethod,
      OAuth1Caller.Keys.signature       -> s"${encodeURI(consumerSecret + "&" + tokenSecret.getOrElse(""))}",
      OAuth1Caller.Keys.timestamp       -> s"${Math.floor(System.currentTimeMillis() / 1000).toInt}",
      OAuth1Caller.Keys.nonce           -> s"${Random.nextInt(1000000000)}"
    )

  private def authorization(
      httpMethod: String,
      url: String,
      oauthParams: Map[String, String],
      queryParams: Map[String, String],
      consumerKey: String,
      consumerSecret: String,
      oauthToken: String,
      oauthTokenSecret: String
  ): Seq[(String, String)] = {
    val params: Seq[(String, String)] =
      (queryParams ++ oauthParams + (OAuth1Caller.Keys.token -> oauthToken))
        .map { case (k, v) => (k, v) }
        .toSeq
        .filter { case (k, _) => k != "oauth_signature" }

    val signature =
      if (oauthParams(OAuth1Caller.Keys.signatureMethod) != "TEXTPLAIN")
        signRequest(
          httpMethod,
          url,
          params,
          consumerSecret,
          oauthTokenSecret,
          oauthParams(OAuth1Caller.Keys.signatureMethod)
        )
      else
        oauthParams(OAuth1Caller.Keys.signature)

    Seq(
      OAuth1Caller.Keys.consumerKey     -> consumerKey,
      OAuth1Caller.Keys.token           -> oauthToken,
      OAuth1Caller.Keys.signatureMethod -> oauthParams(OAuth1Caller.Keys.signatureMethod),
      OAuth1Caller.Keys.timestamp       -> oauthParams(OAuth1Caller.Keys.timestamp),
      OAuth1Caller.Keys.nonce           -> oauthParams(OAuth1Caller.Keys.nonce),
      OAuth1Caller.Keys.signature       -> signature
    )
  }

  private def getOrElse(config: Option[String], metadata: Map[String, String], key: String): Either[Result, String] = {
    metadata.get(key) match {
      case Some(value) => Right(value)
      case None        =>
        config match {
          case None        => Left(BadRequest(Json.obj("error" -> "Bad parameters")))
          case Some(value) => Right(value)
        }
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val oAuth1CallerConfig =
      ctx.cachedConfig(internalName)(OAuth1CallerConfig.format).getOrElse(OAuth1CallerConfig())

    val metadata         = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.metadata).getOrElse(Map.empty)
    val consumerKey      = getOrElse(oAuth1CallerConfig.consumerKey, metadata, OAuth1Caller.Keys.consumerKey)
    val consumerSecret   = getOrElse(oAuth1CallerConfig.consumerSecret, metadata, OAuth1Caller.Keys.consumerSecret)
    val oauthToken       = getOrElse(oAuth1CallerConfig.token, metadata, OAuth1Caller.Keys.token)
    val oauthTokenSecret = getOrElse(oAuth1CallerConfig.tokenSecret, metadata, OAuth1Caller.Keys.tokenSecret)

    (consumerKey, consumerSecret, oauthToken, oauthTokenSecret) match {
      case (Right(key), Right(secret), Right(token), Right(tokenSecret)) =>
        val signature_method = oAuth1CallerConfig.algo.getOrElse("HmacSHA512")

        if (logger.isDebugEnabled) {
          logger.debug(s"Algo used : $signature_method")
        }

        val inParams = getOauthParams(key, secret, Some(tokenSecret), signature_method)

        val params: String = authorization(
          ctx.otoroshiRequest.method,
          ctx.otoroshiRequest.url,
          inParams,
          ctx.otoroshiRequest.queryString.map(_.asInstanceOf[Map[String, String]]).getOrElse(Map.empty[String, String]),
          key,
          secret,
          token,
          tokenSecret
        )
          .map { case (k, v) => s"""$k="$v"""" }
          .mkString(",")

        ctx.otoroshiRequest
          .copy(headers = ctx.otoroshiRequest.headers + ("Authorization" -> s"OAuth $params"))
          .right

      case _ =>
        if (logger.isDebugEnabled) {
          logger.debug(s"""
               |Consumer key : ${consumerKey.getOrElse("missing")}
               |OAuth token : ${oauthToken.getOrElse("missing")}
               |OAuth token secret : ${oauthTokenSecret.getOrElse("missing")}
               |""".stripMargin)
        }
        BadRequest(Json.obj("error" -> "Bad parameters")).left
    }
  }
}

case class OAuth2CallerConfig(
    kind: OAuth2Kind = OAuth2Kind.ClientCredentials,
    url: String = "https://127.0.0.1:8080/oauth/token",
    method: String = "POST",
    headerName: String = "Authorization",
    headerValueFormat: String = "Bearer %s",
    jsonPayload: Boolean = false,
    clientId: String = "the client_id",
    clientSecret: String = "the client_secret",
    scope: Option[String] = None,
    audience: Option[String] = None,
    user: Option[String] = None,
    password: Option[String] = None,
    cacheTokenSeconds: FiniteDuration = (10L * 60L).seconds,
    tlsConfig: MtlsConfig = MtlsConfig()
) extends NgPluginConfig {
  override def json: JsValue = OAuth2CallerConfig.format.writes(this)
}

object OAuth2CallerConfig {
  val format = new Format[OAuth2CallerConfig] {
    override def writes(o: OAuth2CallerConfig): JsValue = Json.obj(
      "kind"              -> o.kind.name,
      "url"               -> o.url,
      "method"            -> o.method,
      "headerName"        -> o.headerName,
      "headerValueFormat" -> o.headerValueFormat,
      "jsonPayload"       -> o.jsonPayload,
      "clientId"          -> o.clientId,
      "clientSecret"      -> o.clientSecret,
      "scope"             -> o.scope,
      "audience"          -> o.audience,
      "user"              -> o.user,
      "password"          -> o.password,
      "cacheTokenSeconds" -> o.cacheTokenSeconds.toMillis,
      "tlsConfig"         -> MtlsConfig.format.writes(o.tlsConfig)
    )

    override def reads(json: JsValue): JsResult[OAuth2CallerConfig] = Try {
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
    } match {
      case Failure(e) => JsError(e.getMessage())
      case Success(v) => JsSuccess(v)
    }
  }
}

class OAuth2Caller extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = true
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def transformsError: Boolean                    = false
  override def defaultConfigObject: Option[NgPluginConfig] = OAuth2CallerConfig().some

  override def name: String = "OAuth2 caller"

  override def description: Option[String] =
    s"""This plugin can be used to call api that are authenticated using OAuth2 client_credential/password flow.
       |Do not forget to enable client retry to handle token generation on expire.
       |""".stripMargin.some

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

  def computeKey(env: Env, config: OAuth2CallerConfig, route: NgRoute): String =
    s"${env.storageRoot}:plugins:oauth-caller-plugin:${config.kind.name}:${config.url}:${config.clientId}:${config.user
      .getOrElse("--")}:${config.password.getOrElse("--")}:${route.id}"

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val rawConfig = ctx.cachedConfig(internalName)(OAuth2CallerConfig.format)

    rawConfig match {
      case None         => Left(BadRequest(Json.obj("error" -> "bad configuration"))).vfuture
      case Some(config) =>
        val key = computeKey(env, config, ctx.route)
        env.datastores.rawDataStore.get(key).flatMap {
          case Some(tokenBody) =>
            val jsonToken = tokenBody.utf8String.parseJson
            val token     = jsonToken.select("access_token").asString
            if (isAlmostComplete(jsonToken, config)) {
              tryRenewToken(key, config)
            }
            Right(
              ctx.otoroshiRequest
                .copy(headers =
                  ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token))
                )
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
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val rawConfig = ctx.cachedConfig(internalName)(OAuth2CallerConfig.format)

    rawConfig match {
      case None         => Left(BadRequest(Json.obj("error" -> "bad configuration"))).vfuture
      case Some(config) =>
        val key = computeKey(env, config, ctx.route)
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
                case Right(token)         => Future.failed[Either[Result, NgPluginHttpResponse]](new ForceRetryException(token))
              }
            }
            case Right(token) => Future.failed[Either[Result, NgPluginHttpResponse]](new ForceRetryException(token))
          }
        } else {
          Right(ctx.otoroshiResponse).future
        }
    }
  }
}