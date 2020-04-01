package otoroshi.plugins.apikeys

import java.util.concurrent.atomic.AtomicBoolean

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.{ApiKey, RemainingQuotas}
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import otoroshi.plugins.JsonPathUtils
import otoroshi.script._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}
import play.core.parsers.FormUrlEncodedParser
import security.IdGenerator

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class HasAllowedApiKeyValidator extends AccessValidator {

  override def name: String = "Allowed apikeys only"

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

  override def name: String = "Allowed apikeys for this service only (service packs)"

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
        val subjectDN    = cert.getSubjectDN.getName
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
                authorizedGroup = context.descriptor.groupId,
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
              // TODO: make it work in otoroshi cluster ....
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

case class ClientCredentialFlowBody(grantType: String, clientId: String, clientSecret: String, scope: Option[String])

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
              ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
              FastFuture.successful(())
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

  override def name: String = "Client Credential Flow"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "ClientCredentialFlow" -> Json.obj(
          "expiration"   -> 1.hour.toMillis,
          "restrictToCurrentGroup" -> true,
          "jwtToken"     -> true,
          "rootPath" -> "/.well-known/otoroshi/oauth"
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin enabless the oauth client credentials flow on a service and add an endpoint (`/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret.
         |If you don't want to have access_tokens as JWT tokens, don't forget to use `ClientCredentialFlowExtractor` pre-routing plugin.
         |Don't forget to authorize access to `/.well-known/otoroshi/oauth/token` in service settings (public paths)
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

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
    val useJwtToken = (conf \ "jwtToken").asOpt[Boolean].getOrElse(true)
    val restrictToCurrentGroup = (conf \ "restrictToCurrentGroup").asOpt[Boolean].getOrElse(true)
    val expiration = (conf \ "expiration").asOpt[Long].map(_.millis).getOrElse(1.hour)
    val rootPath = (conf \ "rootPath").asOpt[String].getOrElse("/.well-known/otoroshi/oauth")
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
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
            case ClientCredentialFlowBody("client_credentials", clientId, clientSecret, scope) => {
              val possibleApiKey = if (restrictToCurrentGroup)
                env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, ctx.descriptor.id)
              else
                env.datastores.apiKeyDataStore.findById(clientId)
              possibleApiKey.flatMap {
                case Some(apiKey) if apiKey.clientSecret == clientSecret && useJwtToken=> {
                  val accessToken = JWT.create()
                    .withExpiresAt(DateTime.now().plusMillis(expiration.toMillis.toInt).toDate)
                    .withIssuedAt(DateTime.now().toDate)
                    .withNotBefore(DateTime.now().toDate)
                    .withIssuer(apiKey.clientId)
                    .withSubject("Otoroshi")
                    .sign(Algorithm.HMAC512(apiKey.clientSecret))
                  // no refresh token possible because of https://tools.ietf.org/html/rfc6749#section-4.4.3
                  val pass = scope.map(s => apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(s.split(" ").toSeq).nonEmpty)).getOrElse(true)
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
                case Some(apiKey) if apiKey.clientSecret == clientSecret && !useJwtToken=> {
                  val randomToken = IdGenerator.token(64)
                  env.datastores.rawDataStore.set(s"${env.storageRoot}:plugins:client-credentials-flow:access-tokens:$randomToken", ByteString(apiKey.clientSecret), Some(expiration.toMillis)).map { _ =>
                    val pass = scope.map(s => apiKey.metadata.get("scope").exists(_.split(" ").toSeq.intersect(s.split(" ").toSeq).nonEmpty)).getOrElse(true)
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
                ) match {
                  case (Some(gtype), Some(clientId), Some(clientSecret), scope) => handleTokenRequest(ClientCredentialFlowBody(gtype, clientId, clientSecret, scope))
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
                      case (clientId, clientSecret) => handleTokenRequest(ClientCredentialFlowBody(body.getOrElse("grant_type", "--"), clientId, clientSecret, None))
                    }
                    .getOrElse {
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
                ) match {
                  case (Some(gtype), Some(clientId), Some(clientSecret), scope) => handleTokenRequest(ClientCredentialFlowBody(gtype, clientId, clientSecret, scope))
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
                      case (clientId, clientSecret) => handleTokenRequest(ClientCredentialFlowBody((json \ "grant_type").asOpt[String].getOrElse("--"), clientId, clientSecret, None))
                    }
                    .getOrElse {
                      Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
                    }
                }
              }
              case _ => Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
            }
          } andThen {
            case _ =>
              if (!consumed.get()) bodySource.runWith(Sink.ignore)
          }
        } getOrElse {
          Results.Unauthorized(Json.obj("error" -> "access_denied", "error_description" -> s"Unauthorized")).leftf
        }
      }
      case _ => ctx.otoroshiRequest.rightf
    }
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }
}