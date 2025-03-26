package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.biscuitsec.biscuit.datalog.SymbolTable
import org.biscuitsec.biscuit.token.builder.parser.Parser
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{ApiKey, ApiKeyHelper, ServiceGroupIdentifier}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.plugins.apikeys.ClientCredentialFlowBody
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.jwk.JWKSHelper
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import play.core.parsers.FormUrlEncodedParser

import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPair, SecureRandom}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class BiscuitConf(
    privkey: Option[String] = None,
    checks: Seq[String] = Seq.empty,
    facts: Seq[String] = Seq.empty,
    rules: Seq[String] = Seq.empty
) {
  def json: JsValue = Json.obj(
    "privkey" -> privkey,
    "checks"  -> checks,
    "facts"   -> facts,
    "rules"   -> rules
  )
}

object BiscuitConf {
  val format = new Format[BiscuitConf] {
    override def writes(o: BiscuitConf): JsValue             = o.json
    override def reads(json: JsValue): JsResult[BiscuitConf] = Try {
      BiscuitConf(
        privkey = json.select("privkey").asOpt[String],
        checks = json.select("checks").asOpt[Seq[String]].getOrElse(Seq.empty),
        facts = json.select("facts").asOpt[Seq[String]].getOrElse(Seq.empty),
        rules = json.select("rules").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

case class NgClientCredentialsConfig(
    expiration: FiniteDuration = 1.hour,
    defaultKeyPair: String = Cert.OtoroshiJwtSigning,
    domain: String = "*",
    secure: Boolean = true,
    biscuit: Option[BiscuitConf] = None
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "expiration"       -> expiration.toMillis,
    "default_key_pair" -> defaultKeyPair,
    "domain"           -> domain,
    "secure"           -> secure,
    "biscuit"          -> biscuit.map(_.json).getOrElse(JsNull).asValue
  )
}

object NgClientCredentialsConfig {
  val format = new Format[NgClientCredentialsConfig] {
    override def writes(o: NgClientCredentialsConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgClientCredentialsConfig] = Try {
      NgClientCredentialsConfig(
        expiration = json.select("expiration").asOpt[Long].getOrElse(1.hour.toMillis).millis,
        defaultKeyPair = json.select("defaultKeyPair").asOpt[String].getOrElse(Cert.OtoroshiJwtSigning),
        domain = json.select("domain").asOpt[String].getOrElse("*"),
        secure = json.select("secure").asOpt[Boolean].getOrElse(true),
        biscuit = json.select("biscuit").asOpt(BiscuitConf.format)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgClientCredentials extends NgRequestSink {

  override def name: String                                = "Client Credential Service"
  override def description: Option[String]                 =
    "This plugin add an an oauth client credentials service (`https://unhandleddomain/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgClientCredentialsConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.Sink)

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val conf          = NgClientCredentialsConfig.format.reads(ctx.config).getOrElse(NgClientCredentialsConfig())
    val domainMatches = conf.domain match {
      case "*"   => true
      case value => ctx.request.theDomain == value
    }
    domainMatches && ctx.origin == NgRequestOrigin.NgReverseProxy && ctx.request.relativeUri.startsWith(
      "/.well-known/otoroshi/oauth/"
    )
  }

  private def handleBody(
      ctx: NgRequestSinkContext
  )(f: Map[String, String] => Future[Result])(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    val charset      = ctx.request.charset.getOrElse("UTF-8")
    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      ctx.request.headers.get("Content-Type") match {
        case Some(ctype) if ctype.toLowerCase().contains("application/x-www-form-urlencoded") => {
          val urlEncodedString         = bodyRaw.utf8String
          val body                     = FormUrlEncodedParser.parse(urlEncodedString, charset).mapValues(_.head)
          val map: Map[String, String] = body ++ ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.tail.mkString(":")))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case Some(ctype) if ctype.toLowerCase().contains("application/json")                  => {
          val json                     = Json.parse(bodyRaw.utf8String).as[JsObject]
          val map: Map[String, String] = json.value.toSeq.collect {
            case (key, JsString(v))  => (key, v)
            case (key, JsNumber(v))  => (key, v.toString())
            case (key, JsBoolean(v)) => (key, v.toString)
          }.toMap ++ ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.tail.mkString(":")))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case _                                                                                =>
          // bad content type
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
      }
    }
  }

  private def jwks(conf: NgClientCredentialsConfig, ctx: NgRequestSinkContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    JWKSHelper.jwks(ctx.request, conf.defaultKeyPair.some.toSeq).map {
      case Left(body)  => Results.NotFound(body)
      case Right(keys) => Results.Ok(Json.obj("keys" -> JsArray(keys)))
    }
  }

  private def introspect(conf: NgClientCredentialsConfig, ctx: NgRequestSinkContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    handleBody(ctx) { body =>
      body.get("token") match {
        case Some(token) => {
          val decoded        = JWT.decode(token)
          val clientId       = Try(decoded.getClaim("clientId").asString()).orElse(Try(decoded.getIssuer())).getOrElse("--")
          val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
          possibleApiKey.flatMap {
            case Some(apiKey) => {
              val keyPairId                     = apiKey.metadata.getOrElse("jwt-sign-keypair", conf.defaultKeyPair)
              val maybeKeyPair: Option[KeyPair] =
                env.proxyState.certificate(keyPairId).map(_.cryptoKeyPair)
              val algo: Algorithm               = maybeKeyPair.map { kp =>
                (kp.getPublic, kp.getPrivate) match {
                  case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
                  case (pub: ECPublicKey, priv: ECPrivateKey)   => Algorithm.ECDSA384(pub, priv)
                  case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
                }
              } getOrElse {
                Algorithm.HMAC512(apiKey.clientSecret)
              }
              Try(JWT.require(algo).acceptLeeway(10).build().verify(token)) match {
                case Failure(e) =>
                  Results
                    .Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized"))
                    .future
                case Success(_) => Results.Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")).future
              }
            }
            case None         =>
              // apikey not found
              Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
          }
        }
        case _           =>
          // bad body
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
      }
    }
  }

  private def handleTokenRequest(
      ccfb: ClientCredentialFlowBody,
      conf: NgClientCredentialsConfig,
      ctx: NgRequestSinkContext
  )(implicit env: Env, ec: ExecutionContext): Future[Result] =
    ccfb match {
      case ClientCredentialFlowBody("client_credentials", clientId, clientSecret, scope, bearerKind) => {
        val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
        possibleApiKey.flatMap {
          case Some(apiKey) if apiKey.isValid(clientSecret) && apiKey.isActive() && bearerKind == "biscuit" => {

            import org.biscuitsec.biscuit.crypto.KeyPair
            import org.biscuitsec.biscuit.token.Biscuit
            import org.biscuitsec.biscuit.token.builder.Block
            import org.biscuitsec.biscuit.token.builder.Utils._

            import collection.JavaConverters._

            val biscuitConf: BiscuitConf = conf.biscuit.getOrElse(BiscuitConf())

            val symbols           = new SymbolTable()
            val authority_builder = new org.biscuitsec.biscuit.token.builder.Block()

            authority_builder.add_fact(fact("token_id", Seq(s("authority"), string(IdGenerator.uuid)).asJava))
            authority_builder.add_fact(
              fact("token_exp", Seq(s("authority"), date(DateTime.now().plus(conf.expiration.toMillis).toDate)).asJava)
            )
            authority_builder.add_fact(fact("token_iat", Seq(s("authority"), date(DateTime.now().toDate)).asJava))
            authority_builder.add_fact(fact("token_nbf", Seq(s("authority"), date(DateTime.now().toDate)).asJava))
            authority_builder.add_fact(
              fact("token_iss", Seq(s("authority"), string(ctx.request.theProtocol + "://" + ctx.request.host)).asJava)
            )
            authority_builder.add_fact(fact("token_aud", Seq(s("authority"), s("otoroshi")).asJava))
            authority_builder.add_fact(fact("client_id", Seq(s("authority"), string(apiKey.clientId)).asJava))
            authority_builder.add_fact(
              fact(
                "client_sign",
                Seq(s("authority"), string(Signatures.hmacSha256Sign(apiKey.clientId, apiKey.clientSecret))).asJava
              )
            )

            biscuitConf.checks
              .map(Parser.check)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_check(r))
            biscuitConf.facts
              .map(Parser.fact)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_fact(r))
            biscuitConf.rules
              .map(Parser.rule)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_rule(r))

            def fromApiKey(name: String): Seq[String] =
              apiKey.metadata.get(name).map(Json.parse).map(_.asArray.value.map(_.asString)).getOrElse(Seq.empty)

            fromApiKey("biscuit_checks")
              .map(Parser.check)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_check(r))
            fromApiKey("biscuit_facts")
              .map(Parser.fact)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_fact(r))
            fromApiKey("biscuit_rules")
              .map(Parser.rule)
              .filter(_.isRight)
              .map(_.get()._2)
              .foreach(r => authority_builder.add_rule(r))

            val accessToken: String = {
              val privKeyValue = apiKey.metadata.get("biscuit_pubkey").orElse(biscuitConf.privkey)
              val keypair      = new KeyPair(privKeyValue.get)
              val rng          = new SecureRandom()
              Biscuit.make(rng, keypair, authority_builder.build(symbols)).serialize_b64url()
            }

            val pass = scope.forall { s =>
              val scopes     = s.split(" ").toSeq
              val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
              scopeInter && apiKey.metadata
                .get("scope")
                .map(_.split(" ").toSeq.intersect(scopes).size)
                .getOrElse(scopes.size) == scopes.size
            }
            if (pass) {
              val scopeObj =
                scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
              Results
                .Ok(
                  Json.obj(
                    "access_token" -> accessToken,
                    "token_type"   -> "Bearer",
                    "expires_in"   -> conf.expiration.toSeconds
                  ) ++ scopeObj
                )
                .future
            } else {
              Results
                .Forbidden(
                  Json.obj(
                    "error"             -> "access_denied",
                    "error_description" -> s"Client has not been granted scopes: ${scope.get}"
                  )
                )
                .future
            }
          }
          case Some(apiKey) if apiKey.isValid(clientSecret) && apiKey.isActive() => {
            val keyPairId                     = apiKey.metadata.getOrElse("jwt-sign-keypair", conf.defaultKeyPair)
            val maybeKeyPair: Option[KeyPair] =
              DynamicSSLEngineProvider.certificates.get(keyPairId).map(_.cryptoKeyPair)
            val algo: Algorithm               = maybeKeyPair.map { kp =>
              (kp.getPublic, kp.getPrivate) match {
                case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
                case (pub: ECPublicKey, priv: ECPrivateKey)   => Algorithm.ECDSA384(pub, priv)
                case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
              }
            } getOrElse {
              Algorithm.HMAC512(apiKey.clientSecret)
            }

            val accessToken = JWT
              .create()
              .withJWTId(IdGenerator.uuid)
              .withExpiresAt(DateTime.now().plus(conf.expiration.toMillis).toDate)
              .withIssuedAt(DateTime.now().toDate)
              .withNotBefore(DateTime.now().toDate)
              .withClaim("cid", apiKey.clientId)
              .withIssuer(ctx.request.theProtocol + "://" + ctx.request.host)
              .withSubject(apiKey.clientId)
              .withAudience("otoroshi")
              .withKeyId(keyPairId)
              .sign(algo)
            // no refresh token possible because of https://tools.ietf.org/html/rfc6749#section-4.4.3

            val pass = scope.forall { s =>
              val scopes     = s.split(" ").toSeq
              val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
              scopeInter && apiKey.metadata
                .get("scope")
                .map(_.split(" ").toSeq.intersect(scopes).size)
                .getOrElse(scopes.size) == scopes.size
            }
            if (pass) {
              val scopeObj =
                scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
              Results
                .Ok(
                  Json.obj(
                    "access_token" -> accessToken,
                    "token_type"   -> "Bearer",
                    "expires_in"   -> conf.expiration.toSeconds
                  ) ++ scopeObj
                )
                .future
            } else {
              Results
                .Forbidden(
                  Json.obj(
                    "error"             -> "access_denied",
                    "error_description" -> s"Client has not been granted scopes: ${scope.get}"
                  )
                )
                .future
            }
          }
          case _                                                                 =>
            Results
              .Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Bad client credentials"))
              .future
        }
      }
      case _                                                                                         =>
        Results
          .BadRequest(
            Json.obj(
              "error"             -> "unauthorized_client",
              "error_description" -> s"Grant type '${ccfb.grantType}' not supported !"
            )
          )
          .future
    }

  private def token(conf: NgClientCredentialsConfig, ctx: NgRequestSinkContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Result] =
    handleBody(ctx) { body =>
      (
        body.get("grant_type"),
        body.get("client_id"),
        body.get("client_secret"),
        body.get("scope"),
        body.get("bearer_kind")
      ) match {
        case (Some(gtype), Some(clientId), Some(clientSecret), scope, kind) =>
          handleTokenRequest(
            ClientCredentialFlowBody(gtype, clientId, clientSecret, scope, kind.getOrElse("jwt")),
            conf,
            ctx
          )
        case _                                                              =>
          ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => (v.head, v.tail.mkString(":")))
            .map { case (clientId, clientSecret) =>
              handleTokenRequest(
                ClientCredentialFlowBody(
                  body.getOrElse("grant_type", "--"),
                  clientId,
                  clientSecret,
                  None,
                  body.getOrElse("bearer_kind", "jwt")
                ),
                conf,
                ctx
              )
            }
            .getOrElse {
              // bad credentials
              Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
            }
      }
    }

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val conf        = NgClientCredentialsConfig.format.reads(ctx.config).getOrElse(NgClientCredentialsConfig())
    val secureMatch = if (conf.secure) ctx.request.theSecured else true
    if (secureMatch) {
      (ctx.request.method.toLowerCase(), ctx.request.relativeUri) match {
        case ("get", "/.well-known/otoroshi/oauth/jwks.json")         => jwks(conf, ctx)
        case ("post", "/.well-known/otoroshi/oauth/token/introspect") => introspect(conf, ctx)
        case ("post", "/.well-known/otoroshi/oauth/token")            => token(conf, ctx)
        case _                                                        =>
          Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> s"resource not found")).future
      }
    } else {
      Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> s"use a secure channel")).future
    }
  }
}

case class NgClientCredentialTokenEndpointBody(
    grantType: String,
    clientId: String,
    clientSecret: String,
    scope: Option[String],
    bearerKind: String,
    aud: Option[String]
)
case class NgClientCredentialTokenEndpointConfig(
    expiration: FiniteDuration,
    defaultKeyPair: String,
    allowedApikeys: Seq[String],
    allowedGroups: Seq[String]
) extends NgPluginConfig {
  override def json: JsValue = NgClientCredentialTokenEndpointConfig.format.writes(this)
}
object NgClientCredentialTokenEndpointConfig {
  val default = NgClientCredentialTokenEndpointConfig(1.hour, Cert.OtoroshiJwtSigning, Seq.empty, Seq.empty)
  val format  = new Format[NgClientCredentialTokenEndpointConfig] {
    override def reads(json: JsValue): JsResult[NgClientCredentialTokenEndpointConfig] = Try {
      NgClientCredentialTokenEndpointConfig(
        expiration = json.select("expiration").asOpt[Long].map(_.millis).getOrElse(1.hour),
        defaultKeyPair =
          json.select("default_key_pair").asOpt[String].filter(_.trim.nonEmpty).getOrElse(Cert.OtoroshiJwtSigning),
        allowedApikeys = json.select("allowed_apikeys").asOpt[Seq[String]].getOrElse(Seq.empty),
        allowedGroups = json.select("allowed_groups").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }

    override def writes(o: NgClientCredentialTokenEndpointConfig): JsValue = Json.obj(
      "expiration"       -> o.expiration.toMillis,
      "default_key_pair" -> o.defaultKeyPair,
      "allowed_apikeys"  -> o.allowedApikeys,
      "allowed_groups"   -> o.allowedGroups
    )
  }
}

class NgClientCredentialTokenEndpoint extends NgBackendCall {

  override def name: String = "Client credential token endpoint"

  override def description: Option[String] =
    "This plugin provide the endpoint for the client_credential flow".some

  override def useDelegates: Boolean = false

  override def multiInstance: Boolean = true

  override def defaultConfigObject: Option[NgPluginConfig] = Some(NgClientCredentialTokenEndpointConfig.default)

  override def deprecated: Boolean = false

  override def core: Boolean = true

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  private def handleBody(
      ctx: NgbBackendCallContext
  )(f: Map[String, String] => Future[Result])(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    val charset      = ctx.rawRequest.charset.getOrElse("UTF-8")
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      ctx.request.headers.get("Content-Type") match {
        case Some(ctype) if ctype.toLowerCase().contains("application/x-www-form-urlencoded") => {
          val urlEncodedString         = bodyRaw.utf8String
          val body                     = FormUrlEncodedParser.parse(urlEncodedString, charset).mapValues(_.head)
          val map: Map[String, String] = body ++ ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.tail.mkString(":")))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case Some(ctype) if ctype.toLowerCase().contains("application/json")                  => {
          val json                     = Json.parse(bodyRaw.utf8String).as[JsObject]
          val map: Map[String, String] = json.value.toSeq.collect {
            case (key, JsString(v))  => (key, v)
            case (key, JsNumber(v))  => (key, v.toString())
            case (key, JsBoolean(v)) => (key, v.toString)
          }.toMap ++ ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.tail.mkString(":")))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case _                                                                                =>
          // bad content type
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
      }
    }
  }

  private def apikeyAllowed(
      conf: NgClientCredentialTokenEndpointConfig,
      apikey: ApiKey,
      ctx: NgbBackendCallContext
  ): Boolean = {
    if (conf.allowedApikeys.isEmpty && conf.allowedGroups.isEmpty) {
      true
    } else {
      if (conf.allowedApikeys.contains(apikey.clientId)) {
        true
      } else {
        val apkgroups = apikey.authorizedEntities.collect { case ServiceGroupIdentifier(id) =>
          id
        }
        conf.allowedGroups.exists(s => apkgroups.contains(s))
      }
    }
  }

  private def handleTokenRequest(
      ccfb: NgClientCredentialTokenEndpointBody,
      conf: NgClientCredentialTokenEndpointConfig,
      ctx: NgbBackendCallContext
  )(implicit env: Env, ec: ExecutionContext): Future[Result] =
    ccfb match {
      case NgClientCredentialTokenEndpointBody(
            "client_credentials",
            clientId,
            clientSecret,
            scope,
            bearerKind,
            aud
          ) => {
        val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
        possibleApiKey.flatMap {
          case Some(apiKey)
              if apiKey.isValid(clientSecret) && apiKey.isActive() && apikeyAllowed(conf, apiKey, ctx) => {
            val keyPairId                     = apiKey.metadata.getOrElse("jwt-sign-keypair", conf.defaultKeyPair)
            val maybeKeyPair: Option[KeyPair] = env.proxyState.certificate(keyPairId).map(_.cryptoKeyPair)
            val algo: Algorithm               = maybeKeyPair.map { kp =>
              (kp.getPublic, kp.getPrivate) match {
                case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
                case (pub: ECPublicKey, priv: ECPrivateKey)   => Algorithm.ECDSA384(pub, priv)
                case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
              }
            } getOrElse {
              Algorithm.HMAC512(apiKey.clientSecret)
            }

            val accessToken = JWT
              .create()
              .withJWTId(IdGenerator.uuid)
              .withExpiresAt(DateTime.now().plus(conf.expiration.toMillis).toDate)
              .withIssuedAt(DateTime.now().toDate)
              .withNotBefore(DateTime.now().toDate)
              .withClaim("cid", apiKey.clientId)
              .withIssuer(ctx.rawRequest.theProtocol + "://" + ctx.rawRequest.host)
              .withSubject(apiKey.clientId)
              .withAudience(aud.getOrElse("otoroshi"))
              .withKeyId(keyPairId)
              .sign(algo)
            // no refresh token possible because of https://tools.ietf.org/html/rfc6749#section-4.4.3

            val pass = scope.forall { s =>
              val scopes     = s.split(" ").toSeq
              val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
              scopeInter && apiKey.metadata
                .get("scope")
                .map(_.split(" ").toSeq.intersect(scopes).size)
                .getOrElse(scopes.size) == scopes.size
            }
            if (pass) {
              val scopeObj =
                scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
              Results
                .Ok(
                  Json.obj(
                    "access_token" -> accessToken,
                    "token_type"   -> "Bearer",
                    "expires_in"   -> conf.expiration.toSeconds
                  ) ++ scopeObj
                )
                .future
            } else {
              Results
                .Forbidden(
                  Json.obj(
                    "error"             -> "access_denied",
                    "error_description" -> s"client has not been granted scopes: ${scope.get}"
                  )
                )
                .future
            }
          }
          case _ =>
            Results
              .Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"bad client credentials"))
              .future
        }
      }
      case _ =>
        Results
          .BadRequest(
            Json.obj(
              "error"             -> "unauthorized_client",
              "error_description" -> s"grant type '${ccfb.grantType}' not supported !"
            )
          )
          .future
    }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(NgClientCredentialTokenEndpointConfig.format)
      .getOrElse(NgClientCredentialTokenEndpointConfig.default)
    handleBody(ctx) { body =>
      println("body", body)
      (
        body.get("grant_type"),
        body.get("client_id"),
        body.get("client_secret"),
        body.get("scope"),
        body.get("bearer_kind"),
        body.get("aud")
      ) match {
        case (Some(gtype), Some(clientId), Some(clientSecret), scope, kind, aud) =>
          handleTokenRequest(
            NgClientCredentialTokenEndpointBody(gtype, clientId, clientSecret, scope, kind.getOrElse("jwt"), aud),
            config,
            ctx
          )
        case e                                                                   =>
          ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => (v.head, v.tail.mkString(":")))
            .map { case (clientId, clientSecret) =>
              handleTokenRequest(
                NgClientCredentialTokenEndpointBody(
                  body.getOrElse("grant_type", "--"),
                  clientId,
                  clientSecret,
                  None,
                  body.getOrElse("bearer_kind", "jwt"),
                  body.get("aud")
                ),
                config,
                ctx
              )
            }
            .getOrElse {
              // bad credentials
              Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"unauthorized")).future
            }
      }
    }.map { result =>
      BackendCallResponse(
        NgPluginHttpResponse(
          result.header.status,
          result.header.headers ++ Map(
            "Content-Type"   -> result.body.contentType.getOrElse("application/json"),
            "Content-Length" -> result.body.contentLength.getOrElse("0").toString
          ),
          Seq.empty,
          result.body.dataStream
        ),
        None
      ).right[NgProxyEngineError]
    }
  }
}
