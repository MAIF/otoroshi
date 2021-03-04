package otoroshi.plugins.apikeys

import java.nio.charset.StandardCharsets
import java.security.{KeyPair, SecureRandom}
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.util
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import otoroshi.cluster.ClusterAgent
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.clevercloud.biscuit.token
import com.clevercloud.biscuit.token.builder.Caveat
import com.clevercloud.biscuit.token.builder.parser.Parser
import com.clevercloud.biscuit.token.format.SealedBiscuit
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.Descriptors.ServiceDescriptor
import com.nimbusds.jose.jwk.{Curve, ECKey, RSAKey}
import env.Env
import models.{ApiKey, RemainingQuotas, ServiceDescriptorIdentifier, ServiceGroupIdentifier}
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import otoroshi.plugins.JsonPathUtils
import otoroshi.script._
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.http.DN
import otoroshi.utils.jwk.JWKSHelper
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import play.core.parsers.FormUrlEncodedParser
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class HasAllowedApiKeyValidator extends AccessValidator {

  override def name: String = "[DEPRECATED] Allowed apikeys only"

  override def configRoot: Option[String] = Some("HasAllowedApiKeyValidator")

  override def configFlow: Seq[String] = Seq("clientIds", "tags", "metadata")

  override def configSchema: Option[JsObject] =
    Some(
      Json.parse("""{
      |  "clientIds": {
      |    "type": "array",
      |    "props": { "label": "Allowed apikeys", "valuesFrom": "/bo/api/proxy/api/apikeys", "transformerMapping": { "label":"clientName", "value":"clientId" } }
      |  },
      |  "tags": {
      |    "type": "array",
      |    "props": { "label": "Allowed tags" }
      |  },
      |  "metadata": {
      |    "type": "object",
      |    "props": { "label": "Allowed metadata" }
      |  },
      |  "apikeyMatch": {
      |    "type": "array",
      |    "props": { "label": "Apikey matching", "placeholder": "JSON Path query" }
      |  },
      |  "apikeyNotMatch": {
      |    "type": "array",
      |    "props": { "label": "Apikey not matching", "placeholder": "JSON Path query" }
      |  }
      |}""".stripMargin).as[JsObject]
    )

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        configRoot.get -> Json.obj(
          "clientIds"      -> Json.arr(),
          "tags"           -> Json.arr(),
          "metadata"       -> Json.obj(),
          "apikeyMatch"    -> Json.obj(),
          "apikeyNotMatch" -> Json.obj(),
        )
      )
    )

  override def description: Option[String] =
    Some("""Validation based on apikeys
      |
      |```json
      |{
      |  "HasAllowedApiKeyValidator": {
      |    "clientIds": [],  // list of allowed client ids,
      |    "tags": [],       // list of allowed tags
      |    "metadata": {}    // allowed metadata,
      |    "apikeyMatch": [],    // json path expressions to match against apikey. passes if one match
      |    "apikeyNotMatch": [], // json path expressions to match against apikey. passes if none match
      |  }
      |}
      |```
    """.stripMargin)

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.apikey match {
      case Some(apiKey) => {
        val config = (context.config \ "HasAllowedApiKeyValidator")
          .asOpt[JsValue]
          .orElse((context.config \ "HasAllowedApiKeyValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val allowedClientIds =
          (config \ "clientIds").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedTags      = (config \ "tags").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedMetadatas = (config \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        val apikeyMatch =
          (config \ "apikeyMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val apikeyNotMatch =
          (config \ "apikeyNotMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        lazy val apikeyJson = apiKey.toJson
        if (allowedClientIds.contains(apiKey.clientId) ||
            allowedTags.exists(tag => apiKey.tags.contains(tag)) ||
            allowedMetadatas.exists(meta => apiKey.metadata.get(meta._1).contains(meta._2)) ||
            (apikeyMatch.exists(JsonPathUtils.matchWith(apikeyJson, "apikey")) && !apikeyNotMatch.exists(
              JsonPathUtils.matchWith(apikeyJson, "apikey")
            ))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ =>
        FastFuture.successful(false)
    }
  }
}

class ApiKeyAllowedOnThisServiceValidator extends AccessValidator {

  override def name: String = "[DEPRECATED] Allowed apikeys for this service only (service packs)"

  override def description: Option[String] =
    Some(
      """This plugin only let pass apikeys containing the id of the service on their tags. It is quite useful to create apikeys that
      |can access a `pack` of services. Apikeys should have tags named like
      |
      |```
      |"allowed-on-${service.id}"
      |```
      |
    """.stripMargin
    )

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    ctx.apikey match {
      case Some(apiKey) => {
        val serviceIds = apiKey.tags.map(tag => tag.replace("allowed-on-", ""))
        FastFuture.successful(serviceIds.exists(id => id == ctx.descriptor.id))
      }
      case _ => FastFuture.successful(false)
    }
  }
}

class CertificateAsApikey extends PreRouting {

  override def name: String = "Client certificate as apikey"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "CertificateAsApikey" -> Json.obj(
          "readOnly"                -> false,
          "allowClientIdOnly"       -> false,
          "throttlingQuota"         -> 100,
          "dailyQuota"              -> RemainingQuotas.MaxValue,
          "monthlyQuota"            -> RemainingQuotas.MaxValue,
          "constrainedServicesOnly" -> false,
          "tags"                    -> Json.arr(),
          "metadata"                -> Json.obj(),
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage
        |
        |```json
        |${Json.prettyPrint(defaultConfig.get)}
        |```
      """.stripMargin
    )

  override def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    context.request.clientCertificateChain.flatMap(_.headOption) match {
      case None => FastFuture.successful(())
      case Some(cert) => {
        val conf         = context.configFor("CertificateAsApikey")
        val serialNumber = cert.getSerialNumber.toString
        val subjectDN    = DN(cert.getSubjectDN.getName).stringify
        val clientId     = Base64.encodeBase64String((subjectDN + "-" + serialNumber).getBytes)
        // TODO: validate CA DN based on config array
        // TODO: validate CA serial based on config array
        env.datastores.apiKeyDataStore
          .findById(clientId)
          .flatMap {
            case Some(apikey) => FastFuture.successful(apikey)
            case None => {
              val apikey = ApiKey(
                clientId = clientId,
                clientSecret = IdGenerator.token(128),
                clientName = s"$subjectDN ($serialNumber)",
                authorizedEntities = Seq(ServiceDescriptorIdentifier(context.descriptor.id)),
                validUntil = Some(new DateTime(cert.getNotAfter)),
                readOnly = (conf \ "readOnly").asOpt[Boolean].getOrElse(false),
                allowClientIdOnly = (conf \ "allowClientIdOnly").asOpt[Boolean].getOrElse(false),
                throttlingQuota = (conf \ "throttlingQuota").asOpt[Long].getOrElse(100),
                dailyQuota = (conf \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
                monthlyQuota = (conf \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
                constrainedServicesOnly = (conf \ "constrainedServicesOnly").asOpt[Boolean].getOrElse(false),
                tags = (conf \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
                metadata = (conf \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
              )
              if (env.clusterConfig.mode.isWorker) {
                ClusterAgent.clusterSaveApikey(env, apikey)
              }
              apikey.save().map(_ => apikey)
            }
          }
          .map { apikey =>
            context.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
            ()
          }
      }
    }
  }
}

case class ClientCredentialFlowBody(grantType: String, clientId: String, clientSecret: String, scope: Option[String], bearerKind: String)

class ClientCredentialFlowExtractor extends PreRouting {

  override def name: String = "Client Credential Flow ApiKey extractor"

  override def description: Option[String] =
    Some(
      s"""This plugin can extract an apikey from an opaque access_token generate by the `ClientCredentialFlow` plugin""".stripMargin
    )

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ctx.request.headers.get("Authorization") match {
      case Some(auth) if auth.startsWith("Bearer ") => {
        val token = auth.replace("Bearer ", "")
        env.datastores.rawDataStore.get(s"${env.storageRoot}:plugins:client-credentials-flow:access-tokens:$token").flatMap {
          case Some(clientId) => env.datastores.apiKeyDataStore.findById(clientId.utf8String).map {
            case Some(apikey) => {
              env.datastores.rawDataStore.get(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$token")
                .map(_.map(_.utf8String.toBoolean).getOrElse(false))
                  .flatMap {
                    case true =>
                      FastFuture.successful(())
                    case false =>
                      ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                      FastFuture.successful(())
                  }
            }
            case _ => FastFuture.successful(())
          }
          case _ => FastFuture.successful(())
        }
      }
      case _ => FastFuture.successful(())
    }
  }
}

class ClientCredentialFlow extends RequestTransformer {

  import otoroshi.utils.syntax.implicits._
  import otoroshi.utils.http.RequestImplicits._

  private val revokedCache: Cache[String, Boolean] = Scaffeine()
    .recordStats()
    .expireAfterWrite(1.hour)
    .maximumSize(1000)
    .build()

  override def name: String = "Client Credential Flow (deprecated, use the 'Client Credential Service' sink)"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        "ClientCredentialFlow" -> Json.obj(
          "expiration"   -> 1.hour.toMillis,
          "supportRevoke"     -> true,
          "supportIntrospect" -> true,
          "jwtToken"          -> true,
          "autonomous"        -> false,
          "signWithKeyPair"   -> false,
          "defaultKeyPair"    -> JsNull,
          "rootPath"          -> "/.well-known/otoroshi/oauth"
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin enables the oauth client credentials flow on a service and add an endpoint (`/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret.
         |If you don't want to have access_tokens as JWT tokens, don't forget to use `ClientCredentialFlowExtractor` pre-routing plugin.
         |Don't forget to authorize access to `/.well-known/otoroshi/oauth/token` in service settings (public paths)
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def beforeRequest(ctx: BeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(ctx: AfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val conf = ctx.configFor("ClientCredentialFlow")
    val _signWithKeyPair = (conf \ "signWithKeyPair").asOpt[Boolean].getOrElse(false)
    val useJwtToken = (conf \ "jwtToken").asOpt[Boolean].getOrElse(true)
    val autonomous = (conf \ "autonomous").asOpt[Boolean].getOrElse(false)
    val supportRevoke = (conf \ "supportRevoke").asOpt[Boolean].getOrElse(false)
    val supportIntrospect = (conf \ "supportIntrospect").asOpt[Boolean].getOrElse(false)
    val expiration = (conf \ "expiration").asOpt[Long].map(_.millis).getOrElse(1.hour)
    val rootPath = (conf \ "rootPath").asOpt[String].getOrElse("/.well-known/otoroshi/oauth")
    val defaultKeyPair = (conf \ "defaultKeyPair").asOpt[String].filter(_.trim.nonEmpty)

    def handleBody[A](f: Map[String, String] => Future[Either[Result, HttpRequest]]): Future[Either[Result, HttpRequest]] = {
      awaitingRequests.get(ctx.snowflake).map { promise =>

        val consumed = new AtomicBoolean(false)

        val bodySource: Source[ByteString, _] = Source
          .future(promise.future)
          .flatMapConcat(s => s)
          .alsoTo(Sink.onComplete {
            case _ => consumed.set(true)
          })

        bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          ctx.request.headers.get("Content-Type") match {
            case Some(ctype) if ctype.toLowerCase().contains("application/x-www-form-urlencoded") => {

              val charset          = ctx.request.charset.getOrElse("UTF-8")
              val urlEncodedString = bodyRaw.utf8String
              val body = FormUrlEncodedParser.parse(urlEncodedString, charset).mapValues(_.head)
              val map: Map[String, String] = body ++ ctx.request.headers
                .get("Authorization")
                .filter(_.startsWith("Basic "))
                .map(_.replace("Basic ", ""))
                .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
                .map(v => new String(v))
                .filter(_.contains(":"))
                .map(_.split(":").toSeq)
                .map(v => Map("client_id" -> v.head, "client_secret" -> v.last))
                .getOrElse(Map.empty[String, String])
              f(map)
            }
            case Some(ctype) if ctype.toLowerCase().contains("application/json") => {
              val json = Json.parse(bodyRaw.utf8String).as[JsObject]
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
                .map(v => Map("client_id" -> v.head, "client_secret" -> v.last))
                .getOrElse(Map.empty[String, String])
              f(map)
            }
            case _ =>
              // bad content type
              Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
          }
        } andThen {
          case _ =>
            if (!consumed.get()) bodySource.runWith(Sink.ignore)
        }
      } getOrElse {
        // no body
        Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
      }
    }

    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", path)  if supportRevoke && path == s"/.well-known/jwks.json" => {
        env.datastores.apiKeyDataStore.findAll().flatMap { apikeys =>
          val ids = apikeys.map(_.metadata.get("jwt-sign-keypair")).collect { case Some(value) => value } ++ defaultKeyPair
          env.datastores.certificatesDataStore.findAll().map { certs =>
            val exposedCerts: Seq[JsValue] = certs.filter(c => ids.contains(c.id)).map(c => (c.id, c.cryptoKeyPair.getPublic)).flatMap {
              case (id, pub: RSAPublicKey) => new RSAKey.Builder(pub).keyID(id).build().toJSONString.parseJson.some
              case (id, pub: ECPublicKey)  => new ECKey.Builder(Curve.forECParameterSpec(pub.getParams), pub).keyID(id).build().toJSONString.parseJson.some
              case _ => None
            }
            Results.Ok(Json.obj("keys" -> JsArray(exposedCerts))).left
          }
        }
      }
      case ("post", path) if supportRevoke && path == s"$rootPath/token/revoke" && useJwtToken => handleBody { body =>
        (
          body.get("token"),
          body.get("revoke")
        ) match {
          case (Some(token), revokeRaw) => {
            val revoke = revokeRaw.map(_.toBoolean).getOrElse(true)
            val decoded = JWT.decode(token)
            Try(decoded.getId).toOption match {
              case None => Results.Ok("").leftf
              case Some(jti) if revoke => env.datastores.rawDataStore.set(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$jti", token.byteString, None)
                  .map(_ => Results.Ok("").left)
              case Some(jti) if !revoke => env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$jti"))
                .map(_ => Results.Ok("").left)
            }
          }
          case _ =>
            // bad body
            Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case ("post", path) if supportRevoke && path == s"$rootPath/token/revoke" && !useJwtToken => handleBody { body =>
        (
          body.get("token"),
          body.get("revoke")
        ) match {
          case (Some(token), revokeRaw) => {
            val revoke = revokeRaw.map(_.toBoolean).getOrElse(true)
            if (revoke) {
              env.datastores.rawDataStore.set(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$token", token.byteString, None)
                .map(_ => Results.Ok("").left)
            } else {
              env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$token"))
                .map(_ => Results.Ok("").left)
            }
          }
          case _ =>
            // bad body
            Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case ("post", path) if supportIntrospect && path == s"$rootPath/token/introspect" && useJwtToken => handleBody { body =>
        body.get("token") match {
          case Some(token) => {
            val decoded = JWT.decode(token)
            val clientId = Try(decoded.getClaim("clientId").asString()).orElse(Try(decoded.getIssuer())).getOrElse("--")
            val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
            possibleApiKey.flatMap {
              case Some(apiKey) => {
                Try(JWT.require(Algorithm.HMAC512(apiKey.clientSecret)).acceptLeeway(10).build().verify(token)) match {
                  case Failure(e) => Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
                  case Success(_) => Results.Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")).leftf
                }
              }
              case None =>
                // apikey not found
                Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
            }
          }
          case _ =>
            // bad body
            Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case ("post", path) if supportIntrospect && path == s"$rootPath/token/introspect" && !useJwtToken => handleBody { body =>
        body.get("token") match {
          case Some(token) => {
            val possibleApiKey: Future[Option[ApiKey]] = env.datastores.rawDataStore.get(s"${env.storageRoot}:plugins:client-credentials-flow:access-tokens:$token").flatMap {
              case Some(clientId) => env.datastores.apiKeyDataStore.findById(clientId.utf8String)
              case _ => None.future
            }
            possibleApiKey.flatMap {
              case Some(apiKey) => Results.Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")).leftf
              case None =>
                // apikey not found
                Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
            }
          }
          case _ =>
            // bad body
            Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case ("post", path) if path == s"$rootPath/token" => {

        awaitingRequests.get(ctx.snowflake).map { promise =>

          val consumed = new AtomicBoolean(false)

          val bodySource: Source[ByteString, _] = Source
            .future(promise.future)
            .flatMapConcat(s => s)
            .alsoTo(Sink.onComplete {
              case _ => consumed.set(true)
            })

          def handleTokenRequest(ccfb: ClientCredentialFlowBody): Future[Either[Result, HttpRequest]] = ccfb match {
            case ClientCredentialFlowBody("client_credentials", clientId, clientSecret, scope, kind) => {
              val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
              possibleApiKey.flatMap {
                case Some(apiKey) if (apiKey.clientSecret == clientSecret || apiKey.rotation.nextSecret.contains(clientSecret)) && useJwtToken=> {

                  val keyPairId = apiKey.metadata.get("jwt-sign-keypair").orElse(defaultKeyPair)
                  val signWithKeyPair = _signWithKeyPair && keyPairId.isDefined

                  val maybeKeyPair: Option[KeyPair] = if (signWithKeyPair) {
                    keyPairId.flatMap(id => DynamicSSLEngineProvider.certificates.get(id)).map(_.cryptoKeyPair)
                  } else {
                    None
                  }

                  val algo: Algorithm = maybeKeyPair.map { kp =>
                    (kp.getPublic, kp.getPrivate) match {
                      case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
                      case (pub: ECPublicKey,  priv: ECPrivateKey)  => Algorithm.ECDSA384(pub, priv)
                      case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
                    }
                  } getOrElse {
                    Algorithm.HMAC512(apiKey.clientSecret)
                  }

                  val accessToken = JWT.create()
                    .withJWTId(IdGenerator.uuid)
                    .withExpiresAt(DateTime.now().plus(expiration.toMillis).toDate)
                    .withIssuedAt(DateTime.now().toDate)
                    .withNotBefore(DateTime.now().toDate)
                    .withClaim("cid", apiKey.clientId)
                    .withIssuer(ctx.request.theProtocol + "://" + ctx.request.host)
                    .withSubject(apiKey.clientId)
                    .withAudience("otoroshi")
                    .applyOnIf(signWithKeyPair) { builder =>
                      builder.withKeyId(keyPairId.get)
                    }
                    .sign(algo)
                  // no refresh token possible because of https://tools.ietf.org/html/rfc6749#section-4.4.3

                  val pass = scope.forall { s =>
                    val scopes = s.split(" ").toSeq
                    val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
                    scopeInter && apiKey.metadata.get("scope").map(_.split(" ").toSeq.intersect(scopes).size).getOrElse(scopes.size) == scopes.size
                  }
                  if (pass) {
                    val scopeObj = scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
                    Results.Ok(Json.obj(
                      "access_token" -> accessToken,
                      "token_type" -> "Bearer",
                      "expires_in" -> expiration.toSeconds
                    ) ++ scopeObj).leftf
                  } else {
                    Results.Forbidden(Json.obj("error" -> "access_denied", "error_description" -> s"Client has not been granted scopes: ${scope.get}")).leftf
                  }
                }
                case Some(apiKey) if (apiKey.clientSecret == clientSecret || apiKey.rotation.nextSecret.contains(clientSecret)) && !useJwtToken=> {
                  val randomToken = IdGenerator.token(64)
                  env.datastores.rawDataStore.set(s"${env.storageRoot}:plugins:client-credentials-flow:access-tokens:$randomToken", ByteString(apiKey.clientSecret), Some(expiration.toMillis)).map { _ =>
                    val pass = scope.forall { s =>
                      val scopes = s.split(" ").toSeq
                      val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
                      scopeInter && apiKey.metadata.get("scope").map(_.split(" ").toSeq.intersect(scopes).size).getOrElse(scopes.size) == scopes.size
                    }
                    if (pass) {
                      val scopeObj = scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
                      Results.Ok(Json.obj(
                        "access_token" -> randomToken,
                        "token_type" -> "Bearer",
                        "expires_in" -> expiration.toSeconds
                      ) ++ scopeObj).left
                    } else {
                      Results.Forbidden(Json.obj("error" -> "access_denied", "error_description" -> s"Client has not been granted scopes: ${scope.get}")).left
                    }
                  }
                }
                case _ => Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Bad client credentials")).leftf
              }
            }
            case _ => Results.BadRequest(Json.obj("error" -> "unauthorized_client", "error_description" -> s"Grant type '${ccfb.grantType}' not supported !")).leftf
          }

          bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            ctx.request.headers.get("Content-Type") match {
              case Some(ctype) if ctype.toLowerCase().contains("application/x-www-form-urlencoded") => {

                val charset          = ctx.request.charset.getOrElse("UTF-8")
                val urlEncodedString = bodyRaw.utf8String
                val body = FormUrlEncodedParser.parse(urlEncodedString, charset).mapValues(_.head)
                (
                  body.get("grant_type"),
                  body.get("client_id"),
                  body.get("client_secret"),
                  body.get("scope"),
                  body.get("bearer_kind"),
                ) match {
                  case (Some(gtype), Some(clientId), Some(clientSecret), scope, kind) => handleTokenRequest(ClientCredentialFlowBody(gtype, clientId, clientSecret, scope, kind.getOrElse("jwt")))
                  case _ => ctx.request.headers
                    .get("Authorization")
                    .filter(_.startsWith("Basic "))
                    .map(_.replace("Basic ", ""))
                    .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
                    .map(v => new String(v))
                    .filter(_.contains(":"))
                    .map(_.split(":").toSeq)
                    .map(v => (v.head, v.last))
                    .map {
                      case (clientId, clientSecret) => handleTokenRequest(ClientCredentialFlowBody(body.getOrElse("grant_type", "--"), clientId, clientSecret, None, body.getOrElse("bearer_kind", "jwt")))
                    }
                    .getOrElse {
                      // bad credentials
                      Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
                    }
                }
              }
              case Some(ctype) if ctype.toLowerCase().contains("application/json") => {
                val json = Json.parse(bodyRaw.utf8String)
                (
                  (json \ "grant_type").asOpt[String],
                  (json \ "client_id").asOpt[String],
                  (json \ "client_secret").asOpt[String],
                  (json \ "scope").asOpt[String],
                  (json \ "bearer_kind").asOpt[String],
                ) match {
                  case (Some(gtype), Some(clientId), Some(clientSecret), scope, kind) => handleTokenRequest(ClientCredentialFlowBody(gtype, clientId, clientSecret, scope, kind.getOrElse("jwt")))
                  case _ => ctx.request.headers
                    .get("Authorization")
                    .filter(_.startsWith("Basic "))
                    .map(_.replace("Basic ", ""))
                    .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
                    .map(v => new String(v))
                    .filter(_.contains(":"))
                    .map(_.split(":").toSeq)
                    .map(v => (v.head, v.last))
                    .map {
                      case (clientId, clientSecret) => handleTokenRequest(ClientCredentialFlowBody((json \ "grant_type").asOpt[String].getOrElse("--"), clientId, clientSecret, None, (json \ "bearer_kind").asOpt[String].getOrElse("jwt")))
                    }
                    .getOrElse {
                      // bad credentials
                      Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
                    }
                }
              }
              case _ =>
                // bad content type
                Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
            }
          } andThen {
            case _ =>
              if (!consumed.get()) bodySource.runWith(Sink.ignore)
          }
        } getOrElse {
          // no body
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case _ if autonomous     => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> s"Resource not found")).leftf
      case _ if !supportRevoke => ctx.otoroshiRequest.rightf
      case _ => {

        val req = ctx.request
        val descriptor = ctx.descriptor
        val authByJwtToken = ctx.request.headers
          .get(
            descriptor.apiKeyConstraints.jwtAuth.headerName
              .getOrElse(env.Headers.OtoroshiBearer)
          )
          .orElse(
            req.headers.get("Authorization").filter(_.startsWith("Bearer "))
          )
          .map(_.replace("Bearer ", ""))
          .orElse(
            req.queryString
              .get(
                descriptor.apiKeyConstraints.jwtAuth.queryName
                  .getOrElse(env.Headers.OtoroshiBearerAuthorization)
              )
              .flatMap(_.lastOption)
          )
          .orElse(
            req.cookies
              .get(
                descriptor.apiKeyConstraints.jwtAuth.cookieName
                  .getOrElse(env.Headers.OtoroshiJWTAuthorization)
              )
              .map(_.value)
          )
          // .filter(_.split("\\.").length == 3)
        authByJwtToken match {
          case None =>
            // no token, weird, should not happen
            Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
          case Some(token) if useJwtToken => {
            val decoded = JWT.decode(token)
            Try(decoded.getId).toOption match {
              case None =>
                // no jti, weird
                Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
              case Some(jti) =>
                revokedCache
                  .getIfPresent(jti)
                  .map(_.future)
                  .getOrElse(
                    env.datastores.rawDataStore.get(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$jti")
                      .map(_.map(_.utf8String.toBoolean).getOrElse(false))
                      .andThen {
                        case Success(b) => revokedCache.put(jti, b)
                      }
                  ).flatMap {
                  case true =>
                    // revoked token
                    Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
                  case false =>
                    ctx.otoroshiRequest.rightf
                }
            }
          }
          case Some(token) if !useJwtToken => {
            revokedCache
              .getIfPresent(token)
              .map(_.future)
              .getOrElse(
                env.datastores.rawDataStore.get(s"${env.storageRoot}:plugins:client-credentials-flow:revoked-tokens:$token")
                  .map(_.map(_.utf8String.toBoolean).getOrElse(false))
                  .andThen {
                    case Success(b) => revokedCache.put(token, b)
                  }
              ).flatMap {
              case true =>
                // revoked token
                Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
              case false =>
                ctx.otoroshiRequest.rightf
            }
          }
        }
      }
    }
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }
}

case class BiscuitConf(
  privkey: Option[String] = None,
  secret: Option[String] = Some("secret"),
  sealedToken: Boolean = false,
  caveats: Seq[String] = Seq.empty,
  facts: Seq[String] = Seq.empty,
  rules: Seq[String] = Seq.empty,
)

class ClientCredentialService extends RequestSink {

  import otoroshi.utils.http.RequestImplicits._
  import otoroshi.utils.syntax.implicits._

  case class ClientCredentialServiceConfig(raw: JsValue) {
    lazy val expiration = (raw \ "expiration").asOpt[Long].map(_.millis).getOrElse(1.hour)
    lazy val defaultKeyPair = (raw \ "defaultKeyPair").asOpt[String].filter(_.trim.nonEmpty).getOrElse(Cert.OtoroshiJwtSigning)
    lazy val domain = (raw \ "domain").asOpt[String].filter(_.trim.nonEmpty).getOrElse("*")
    lazy val secure = (raw \ "secure").asOpt[Boolean].getOrElse(true)
    lazy val biscuit = (raw \ "biscuit").asOpt[JsObject].map { js =>
      BiscuitConf(
        privkey = (js \ "privkey").asOpt[String],
        secret = (js \ "secret").asOpt[String],
        sealedToken = (js \ "sealedToken").asOpt[Boolean].getOrElse(false),
        caveats = (js \ "caveats").asOpt[Seq[String]].getOrElse(Seq.empty),
        facts = (js \ "facts").asOpt[Seq[String]].getOrElse(Seq.empty),
        rules = (js \ "rules").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    }.getOrElse(BiscuitConf())
  }

  override def name: String = "Client Credential Service"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        "ClientCredentialService" -> Json.obj(
          "domain"  -> "*",
          "expiration"     -> 1.hour.toMillis,
          "defaultKeyPair" -> Cert.OtoroshiJwtSigning,
          "secure"         -> true
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin add an an oauth client credentials service (`https://unhandleddomain/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret.
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val conf = ClientCredentialServiceConfig(ctx.configFor("ClientCredentialService"))
    val domainMatches = conf.domain match {
      case "*"   => true
      case value => ctx.request.theDomain == value
    }
    domainMatches && ctx.origin == RequestOrigin.ReverseProxy && ctx.request.relativeUri.startsWith("/.well-known/otoroshi/oauth/")
  }

  private def handleBody(ctx: RequestSinkContext)(f: Map[String, String] => Future[Result])(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    val charset = ctx.request.charset.getOrElse("UTF-8")
    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      ctx.request.headers.get("Content-Type") match {
        case Some(ctype) if ctype.toLowerCase().contains("application/x-www-form-urlencoded") => {
          val urlEncodedString = bodyRaw.utf8String
          val body = FormUrlEncodedParser.parse(urlEncodedString, charset).mapValues(_.head)
          val map: Map[String, String] = body ++ ctx.request.headers
            .get("Authorization")
            .filter(_.startsWith("Basic "))
            .map(_.replace("Basic ", ""))
            .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
            .map(v => new String(v))
            .filter(_.contains(":"))
            .map(_.split(":").toSeq)
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.last))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case Some(ctype) if ctype.toLowerCase().contains("application/json") => {
          val json = Json.parse(bodyRaw.utf8String).as[JsObject]
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
            .map(v => Map("client_id" -> v.head, "client_secret" -> v.last))
            .getOrElse(Map.empty[String, String])
          f(map)
        }
        case _ =>
          // bad content type
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
      }
    }
  }

  private def jwks(conf: ClientCredentialServiceConfig, ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    JWKSHelper.jwks(ctx.request, conf.defaultKeyPair.some.toSeq).map {
      case Left(body) => Results.NotFound(body)
      case Right(body) => Results.Ok(body)
    }
  }

  private def introspect(conf: ClientCredentialServiceConfig, ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    handleBody(ctx) { body =>
      body.get("token") match {
        case Some(token) => {
          val decoded = JWT.decode(token)
          val clientId = Try(decoded.getClaim("clientId").asString()).orElse(Try(decoded.getIssuer())).getOrElse("--")
          val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
          possibleApiKey.flatMap {
            case Some(apiKey) => {
              val keyPairId = apiKey.metadata.getOrElse("jwt-sign-keypair", conf.defaultKeyPair)
              val maybeKeyPair: Option[KeyPair] = DynamicSSLEngineProvider.certificates.get(keyPairId).map(_.cryptoKeyPair)
              val algo: Algorithm = maybeKeyPair.map { kp =>
                (kp.getPublic, kp.getPrivate) match {
                  case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
                  case (pub: ECPublicKey,  priv: ECPrivateKey)  => Algorithm.ECDSA384(pub, priv)
                  case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
                }
              } getOrElse {
                Algorithm.HMAC512(apiKey.clientSecret)
              }
              Try(JWT.require(algo).acceptLeeway(10).build().verify(token)) match {
                case Failure(e) => Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
                case Success(_) => Results.Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")).future
              }
            }
            case None =>
              // apikey not found
              Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
          }
        }
        case _ =>
          // bad body
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
      }
    }
  }

  private def handleTokenRequest(ccfb: ClientCredentialFlowBody, conf: ClientCredentialServiceConfig, ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = ccfb match {
    case ClientCredentialFlowBody("client_credentials", clientId, clientSecret, scope, bearerKind) => {
      val possibleApiKey = env.datastores.apiKeyDataStore.findById(clientId)
      possibleApiKey.flatMap {
        case Some(apiKey) if (apiKey.clientSecret == clientSecret || apiKey.rotation.nextSecret.contains(clientSecret)) && bearerKind == "biscuit" => {

          import com.clevercloud.biscuit.crypto.KeyPair
          import com.clevercloud.biscuit.token.Biscuit
          import com.clevercloud.biscuit.token.builder.Utils._
          import com.clevercloud.biscuit.token.builder.Block
          import collection.JavaConverters._

          val biscuitConf: BiscuitConf = conf.biscuit

          val symbols = Biscuit.default_symbol_table()
          val authority_builder = new Block(0, symbols)

          authority_builder.add_fact(fact("token_id",    Seq(s("authority"), string(IdGenerator.uuid)).asJava))
          authority_builder.add_fact(fact("token_exp",   Seq(s("authority"), date(DateTime.now().plus(conf.expiration.toMillis).toDate)).asJava))
          authority_builder.add_fact(fact("token_iat",   Seq(s("authority"), date(DateTime.now().toDate)).asJava))
          authority_builder.add_fact(fact("token_nbf",   Seq(s("authority"), date(DateTime.now().toDate)).asJava))
          authority_builder.add_fact(fact("token_iss",   Seq(s("authority"), string(ctx.request.theProtocol + "://" + ctx.request.host)).asJava))
          authority_builder.add_fact(fact("token_aud",   Seq(s("authority"), s("otoroshi")).asJava))
          authority_builder.add_fact(fact("client_id",   Seq(s("authority"), string(apiKey.clientId)).asJava))
          authority_builder.add_fact(fact("client_sign", Seq(s("authority"), string(Signatures.hmacSha256Sign(apiKey.clientId, apiKey.clientSecret))).asJava))

          biscuitConf.caveats.map(Parser.rule).filter(_.isRight).map(_.get()._2).map(r => new Caveat(r)).foreach(r => authority_builder.add_caveat(r))
          biscuitConf.facts.map(Parser.fact).filter(_.isRight).map(_.get()._2).foreach(r => authority_builder.add_fact(r))
          biscuitConf.rules.map(Parser.rule).filter(_.isRight).map(_.get()._2).foreach(r => authority_builder.add_rule(r))

          def fromApiKey(name: String): Seq[String] = apiKey.metadata.get(name).map(Json.parse).map(_.asArray.value.map(_.asString)).getOrElse(Seq.empty)

          fromApiKey("biscuit_caveats").map(Parser.rule).filter(_.isRight).map(_.get()._2).map(r => new Caveat(r)).foreach(r => authority_builder.add_caveat(r))
          fromApiKey("biscuit_facts").map(Parser.fact).filter(_.isRight).map(_.get()._2).foreach(r => authority_builder.add_fact(r))
          fromApiKey("biscuit_rules").map(Parser.rule).filter(_.isRight).map(_.get()._2).foreach(r => authority_builder.add_rule(r))

          val accessToken: String = if (biscuitConf.sealedToken) {
            val bytes = SealedBiscuit.make(authority_builder.build(), new util.ArrayList[com.clevercloud.biscuit.token.Block](), biscuitConf.secret.get.getBytes(StandardCharsets.UTF_8)).get().serialize().get()
            Base64.encodeBase64URLSafeString(bytes)
          } else {
            val privKeyValue = apiKey.metadata.get("biscuit_pubkey").orElse(biscuitConf.privkey)
            val keypair = new KeyPair(privKeyValue.get)
            val rng = new SecureRandom()
            Biscuit.make(rng, keypair, symbols, authority_builder.build()).get().serialize_b64().get()
          }

          val pass = scope.forall { s =>
            val scopes = s.split(" ").toSeq
            val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
            scopeInter && apiKey.metadata.get("scope").map(_.split(" ").toSeq.intersect(scopes).size).getOrElse(scopes.size) == scopes.size
          }
          if (pass) {
            val scopeObj = scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
            Results.Ok(Json.obj(
              "access_token" -> accessToken,
              "token_type" -> "Bearer",
              "expires_in" -> conf.expiration.toSeconds
            ) ++ scopeObj).future
          } else {
            Results.Forbidden(Json.obj("error" -> "access_denied", "error_description" -> s"Client has not been granted scopes: ${scope.get}")).future
          }
        }
        case Some(apiKey) if apiKey.clientSecret == clientSecret || apiKey.rotation.nextSecret.contains(clientSecret) => {
          val keyPairId = apiKey.metadata.getOrElse("jwt-sign-keypair", conf.defaultKeyPair)
          val maybeKeyPair: Option[KeyPair] = DynamicSSLEngineProvider.certificates.get(keyPairId).map(_.cryptoKeyPair)
          val algo: Algorithm = maybeKeyPair.map { kp =>
            (kp.getPublic, kp.getPrivate) match {
              case (pub: RSAPublicKey, priv: RSAPrivateKey) => Algorithm.RSA256(pub, priv)
              case (pub: ECPublicKey,  priv: ECPrivateKey)  => Algorithm.ECDSA384(pub, priv)
              case _                                        => Algorithm.HMAC512(apiKey.clientSecret)
            }
          } getOrElse {
            Algorithm.HMAC512(apiKey.clientSecret)
          }

          val accessToken = JWT.create()
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
            val scopes = s.split(" ").toSeq
            val scopeInter = apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(scopes).nonEmpty)
            scopeInter && apiKey.metadata.get("scope").map(_.split(" ").toSeq.intersect(scopes).size).getOrElse(scopes.size) == scopes.size
          }
          if (pass) {
            val scopeObj = scope.orElse(apiKey.metadata.get("scope")).map(v => Json.obj("scope" -> v)).getOrElse(Json.obj())
            Results.Ok(Json.obj(
              "access_token" -> accessToken,
              "token_type" -> "Bearer",
              "expires_in" -> conf.expiration.toSeconds
            ) ++ scopeObj).future
          } else {
            Results.Forbidden(Json.obj("error" -> "access_denied", "error_description" -> s"Client has not been granted scopes: ${scope.get}")).future
          }
        }
        case _ => Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Bad client credentials")).future
      }
    }
    case _ => Results.BadRequest(Json.obj("error" -> "unauthorized_client", "error_description" -> s"Grant type '${ccfb.grantType}' not supported !")).future
  }

  private def token(conf: ClientCredentialServiceConfig, ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = handleBody(ctx) { body =>
    (
      body.get("grant_type"),
      body.get("client_id"),
      body.get("client_secret"),
      body.get("scope"),
      body.get("bearer_kind"),
    ) match {
      case (Some(gtype), Some(clientId), Some(clientSecret), scope, kind) => handleTokenRequest(ClientCredentialFlowBody(gtype, clientId, clientSecret, scope, kind.getOrElse("jwt")), conf, ctx)
      case _ => ctx.request.headers
        .get("Authorization")
        .filter(_.startsWith("Basic "))
        .map(_.replace("Basic ", ""))
        .map(v => org.apache.commons.codec.binary.Base64.decodeBase64(v))
        .map(v => new String(v))
        .filter(_.contains(":"))
        .map(_.split(":").toSeq)
        .map(v => (v.head, v.last))
        .map {
          case (clientId, clientSecret) => handleTokenRequest(ClientCredentialFlowBody(body.getOrElse("grant_type", "--"), clientId, clientSecret, None, body.getOrElse("bearer_kind", "jwt")), conf, ctx)
        }
        .getOrElse {
          // bad credentials
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).future
        }
    }
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val conf = ClientCredentialServiceConfig(ctx.configFor("ClientCredentialService"))
    val secureMatch = if (conf.secure) ctx.request.theSecured else true
    if (secureMatch) {
      (ctx.request.method.toLowerCase(), ctx.request.relativeUri) match {
        case ("get", "/.well-known/otoroshi/oauth/jwks.json") => jwks(conf, ctx)
        case ("post", "/.well-known/otoroshi/oauth/token/introspect") => introspect(conf, ctx)
        case ("post", "/.well-known/otoroshi/oauth/token") => token(conf, ctx)
        case _ => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> s"resource not found")).future
      }
    } else {
      Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> s"use a secure channel")).future
    }
  }
}