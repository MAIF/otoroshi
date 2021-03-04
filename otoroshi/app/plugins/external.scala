package otoroshi.plugins.external

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor, WSProxyServerJson}
import org.apache.commons.codec.binary.Hex
import otoroshi.script.{AccessContext, AccessValidator}
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import otoroshi.ssl.{ClientCertificateValidator, PemHeaders}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

case class ExternalHttpValidatorConfig(config: JsValue) {
  lazy val url: String                  = (config \ "url").as[String]
  lazy val host: String                 = (config \ "host").asOpt[String].getOrElse(Uri(url).authority.host.toString())
  lazy val goodTtl: Long                = (config \ "goodTtl").asOpt[Long].getOrElse(10L * 60000L)
  lazy val badTtl: Long                 = (config \ "badTtl").asOpt[Long].getOrElse(1L * 60000L)
  lazy val method: String               = (config \ "method").asOpt[String].getOrElse("POST")
  lazy val path: String                 = (config \ "path").asOpt[String].getOrElse("/certificates/_validate")
  lazy val timeout: Long                = (config \ "timeout").asOpt[Long].getOrElse(10000L)
  lazy val noCache: Boolean             = (config \ "noCache").asOpt[Boolean].getOrElse(false)
  lazy val allowNoClientCert: Boolean   = (config \ "allowNoClientCert").asOpt[Boolean].getOrElse(false)
  lazy val headers: Map[String, String] = (config \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val proxy: Option[WSProxyServer] =
    (config \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p))
  lazy val mtlsConfig: MtlsConfig       = MtlsConfig.read((config \ "mtlsConfig").asOpt[JsValue])
}

class ExternalHttpValidator extends AccessValidator {

  import otoroshi.utils.http.Implicits._

  override def name: String = "External Http Validator"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "ExternalHttpValidator" -> Json.obj(
          "url"               -> "http://foo.bar",
          "host"              -> "api.foo.bar",
          "goodTtl"           -> 600000,
          "badTtl"            -> 60000,
          "method"            -> "POST",
          "path"              -> "/certificates/_validate",
          "timeout"           -> 10000,
          "noCache"           -> false,
          "allowNoClientCert" -> false,
          "headers"           -> Json.obj(),
          "mtlsConfig"        -> Json.obj(
            "certId" -> "...",
            "mtls"   -> false,
            "loose"  -> false
          )
        )
      )
    )

  override def description: Option[String] =
    Some("""Calls an external http service to know if a user has access or not. Uses cache for performances.
      |
      |The sent payload is the following:
      |
      |```json
      |{
      |  "apikey": {...},
      |  "user": {...},
      |  "service": : {...},
      |  "chain": "...",  // PEM cert chain
      |  "fingerprints": [...]
      |}
      |```
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "ExternalHttpValidator": {
      |    "url": "...",                      // url for the http call
      |    "host": "...",                     // value of the host header for the call. default is host of the url
      |    "goodTtl": 600000,                 // ttl in ms for a validated call
      |    "badTtl": 60000,                   // ttl in ms for a not validated call
      |    "method": "POST",                  // http methode
      |    "path": "/certificates/_validate", // http uri path
      |    "timeout": 10000,                  // http call timeout
      |    "noCache": false,                  // use cache or not
      |    "allowNoClientCert": false,        //
      |    "headers": {},                      // headers for the http call if needed
      |    "mtlsConfig": {
      |      "certId": "xxxxx",
      |       "mtls": false,
      |       "loose": false
      |    }
      |  }
      |}
      |```
    """.stripMargin)

  override def configSchema: Option[JsObject] =
    super.configSchema.map(
      _ ++ Json.obj(
        "mtlsConfig.certId" -> Json.obj(
          "type"  -> "select",
          "props" -> Json.obj(
            "label"              -> "certId",
            "placeholer"         -> "Client cert used for mTLS call",
            "valuesFrom"         -> "/bo/api/proxy/api/certificates?client=true",
            "transformerMapping" -> Json.obj("label" -> "name", "value" -> "id")
          )
        )
      )
    )

  private val digester = MessageDigest.getInstance("SHA-1")

  private def computeFingerPrint(cert: X509Certificate): String = {
    Hex.encodeHexString(digester.digest(cert.getEncoded())).toLowerCase()
  }

  private def computeKeyFromChain(chain: Seq[X509Certificate]): String = {
    chain.map(computeFingerPrint).mkString("-")
  }

  private def getLocalValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] = {
    env.datastores.clientCertificateValidationDataStore.getValidation(key)
  }

  private def setGoodLocalValidation(key: String, goodTtl: Long)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, true, goodTtl).map(_ => ())
  }

  private def setBadLocalValidation(key: String, badTtl: Long)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, false, badTtl).map(_ => ())
  }

  private def validateCertificateChain(
      chain: Seq[X509Certificate],
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      cfg: ExternalHttpValidatorConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] = {
    import otoroshi.ssl.SSLImplicits._
    val globalConfig                        = env.datastores.globalConfigDataStore.latest()
    val certPayload                         = chain
      .map { cert =>
        cert.asPem
      //s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
      }
      .mkString("\n")
    val payload                             = Json.obj(
      "apikey"       -> apikey.map(_.toJson.as[JsObject] - "clientSecret").getOrElse(JsNull).as[JsValue],
      "user"         -> user.map(_.toJson).getOrElse(JsNull).as[JsValue],
      "service"      -> Json.obj(
        "id"        -> desc.id,
        "name"      -> desc.name,
        "groups"    -> desc.groups,
        "domain"    -> desc.domain,
        "env"       -> desc.env,
        "subdomain" -> desc.subdomain,
        "root"      -> desc.root,
        "metadata"  -> desc.metadata
      ),
      "chain"        -> certPayload,
      "fingerprints" -> JsArray(chain.map(computeFingerPrint).map(JsString.apply))
    )
    val finalHeaders: Seq[(String, String)] =
      cfg.headers.toSeq ++ Seq("Host" -> cfg.host, "Content-Type" -> "application/json", "Accept" -> "application/json")
    env.MtlsWs
      .url(cfg.url + cfg.path, cfg.mtlsConfig)
      .withHttpHeaders(finalHeaders: _*)
      .withMethod(cfg.method)
      .withBody(payload)
      .withRequestTimeout(Duration(cfg.timeout, TimeUnit.MILLISECONDS))
      .withMaybeProxyServer(cfg.proxy.orElse(globalConfig.proxies.authority))
      .execute()
      .map { resp =>
        resp.status match { // TODO: can be good | revoked | unknown
          case 200 =>
            (resp.json.as[JsObject] \ "status")
              .asOpt[String]
              .map(_.toLowerCase == "good") // TODO: return custom message, also device identification for logging
          case _   =>
            resp.ignore()(env.otoroshiMaterializer)
            None
        }
      }
      .recover { case e =>
        ClientCertificateValidator.logger.error("Error while validating client certificate chain", e)
        None
      }
  }

  def canAccessWithClientCertChain(
      chain: Seq[X509Certificate],
      context: AccessContext,
      valCfg: ExternalHttpValidatorConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val apikey = context.apikey
    val user   = context.user
    val desc   = context.descriptor
    val key    = computeKeyFromChain(chain) + "-" + apikey
      .map(_.clientId)
      .orElse(user.map(_.randomId))
      .getOrElse("none") + "-" + desc.id
    if (valCfg.noCache) {
      validateCertificateChain(chain, desc, apikey, user, valCfg).map {
        case Some(bool) => bool
        case None       => false
      }
    } else {
      getLocalValidation(key).flatMap {
        case Some(true)  => FastFuture.successful(true)
        case Some(false) => FastFuture.successful(false)
        case None        => {
          validateCertificateChain(chain, desc, apikey, user, valCfg).flatMap {
            case Some(false) => setBadLocalValidation(key, valCfg.badTtl).map(_ => false)
            case Some(true)  => setGoodLocalValidation(key, valCfg.goodTtl).map(_ => true)
            case None        => setBadLocalValidation(key, valCfg.badTtl).map(_ => false)
          }
        }
      }
    }
  }

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val config = (context.config \ "ExternalHttpValidator")
      .asOpt[JsValue]
      .orElse((context.config \ "ExternalHttpValidator").asOpt[JsValue])
      .getOrElse(context.config)
    val valCfg = ExternalHttpValidatorConfig(config)
    context.request.clientCertificateChain match {
      case None if !valCfg.allowNoClientCert => FastFuture.successful(false)
      case None if valCfg.allowNoClientCert  => {
        val chain: Seq[X509Certificate] = Seq.empty
        canAccessWithClientCertChain(chain, context, valCfg)
      }
      case Some(chain)                       => {
        canAccessWithClientCertChain(chain, context, valCfg)
      }
    }
  }
}
