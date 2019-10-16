package otoroshi.script

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import env.Env
import gateway.Errors
import models._
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{RequestHeader, Result, Results}
import ssl.{ClientCertificateValidator, PemHeaders}
import utils.{RegexPool, TypedMap}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

case class AccessValidatorRef(enabled: Boolean = false, excludedPatterns: Seq[String] = Seq.empty[String], refs: Seq[String] = Seq.empty, config: JsValue = Json.obj()) {
  def json: JsValue = AccessValidatorRef.format.writes(this)
}

object AccessValidatorRef {
  val format = new Format[AccessValidatorRef] {
    override def writes(o: AccessValidatorRef): JsValue = Json.obj(
      "enabled"          -> o.enabled,
      "refs"             -> JsArray(o.refs.map(JsString.apply)),
      "config"           -> o.config,
      "excludedPatterns" -> JsArray(o.excludedPatterns.map(JsString.apply)),
    )
    override def reads(json: JsValue): JsResult[AccessValidatorRef] =
      Try {
        JsSuccess(
          AccessValidatorRef(
            refs = (json \ "refs").asOpt[Seq[String]].orElse((json \ "ref").asOpt[String].map(r => Seq(r))).getOrElse(Seq.empty) ,
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            config = (json \ "config").asOpt[JsValue].getOrElse(Json.obj()),
            excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

sealed trait Access
case object Allowed extends Access
case class Denied(result: Result) extends Access

trait AccessValidator {
  def access(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Access] = {
    canAccess(context)(env, ec).flatMap {
      case true => FastFuture.successful(Allowed)
      case false => Errors.craftResponseResult(
        "bad request",
        Results.BadRequest,
        context.request,
        None,
        None
      ).map(Denied.apply)
    }
  }
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean]
}

case class AccessContext(
  snowflake: String,
  index: Int,
  request: RequestHeader,
  descriptor: ServiceDescriptor,
  user: Option[PrivateAppsUser],
  apikey: Option[ApiKey],
  config: JsValue,
  attrs: TypedMap
  // TODO: add user-agent infos
  // TODO: add client geoloc infos
) {
  def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _ => None
    }
  }
  def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _ => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

object DefaultValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(true)
  }
}

object CompilingValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(false)
  }
}

class HasClientCertValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(_) => FastFuture.successful(true)
      case _ => FastFuture.successful(false)
    }
  }
}

class AlwaysDenyValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(false)
  }
}

class AlwaysAllowValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(true)
  }
}

class HasClientCertMatchingValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(certs) => {
        val allowedSerialNumbers = (context.config \ "serialNumbers").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedSubjectDNs = (context.config \ "subjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedIssuerDNs = (context.config \ "issuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedSubjectDNs = (context.config \ "regexSubjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedIssuerDNs = (context.config \ "regexIssuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        if (certs.exists(cert => allowedSerialNumbers.exists(s => s == cert.getSerialNumber.toString(16))) ||
            certs.exists(cert => allowedSubjectDNs.exists(s => RegexPool(s).matches(cert.getSubjectDN.getName))) ||
            certs.exists(cert => allowedIssuerDNs.exists(s => RegexPool(s).matches(cert.getIssuerDN.getName))) ||
            certs.exists(cert => regexAllowedSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.getSubjectDN.getName))) ||
            certs.exists(cert => regexAllowedIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.getIssuerDN.getName)))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
        // val subjectDnMatching = (context.config \ "subjectDN").asOpt[String]
        // val issuerDnMatching = (context.config \ "issuerDN").asOpt[String]
        // (subjectDnMatching, issuerDnMatching) match {
        //   case (None, None)                  => FastFuture.successful(true)
        //   case (Some(subject), None)         => FastFuture.successful(certs.exists(_.getSubjectDN.getName.matches(subject)))
        //   case (None, Some(issuer))          => FastFuture.successful(certs.exists(_.getIssuerDN.getName.matches(issuer)))
        //   case (Some(subject), Some(issuer)) => FastFuture.successful(
        //     certs.exists(_.getSubjectDN.getName.matches(subject)) && certs.exists(_.getIssuerDN.getName.matches(issuer))
        //   )
        // }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

class HasClientCertMatchingHttpValidator extends AccessValidator {

  private val cache = new TrieMap[String, (Long, JsValue)]

  private def validate(certs: Seq[X509Certificate], values: JsValue): Boolean = {
    val allowedSerialNumbers = (values \ "serialNumbers").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val allowedSubjectDNs = (values \ "subjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val allowedIssuerDNs = (values \ "issuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val regexAllowedSubjectDNs = (values \ "regexSubjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val regexAllowedIssuerDNs = (values \ "regexIssuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    if (certs.exists(cert => allowedSerialNumbers.exists(s => s == cert.getSerialNumber.toString(16))) ||
      certs.exists(cert => allowedSubjectDNs.exists(s => RegexPool(s).matches(cert.getSubjectDN.getName))) ||
      certs.exists(cert => allowedIssuerDNs.exists(s => RegexPool(s).matches(cert.getIssuerDN.getName))) ||
      certs.exists(cert => regexAllowedSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.getSubjectDN.getName))) ||
      certs.exists(cert => regexAllowedIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.getIssuerDN.getName)))) {
      true
    } else {
      false
    }
  }

  private def fetch(url: String, headers: Map[String, String], ttl: Long)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    env.Ws.url(url).withHttpHeaders(headers.toSeq: _*).get().map {
      case r if r.status == 200 =>
        cache.put(url, (System.currentTimeMillis(), r.json))
        r.json
      case _ =>
        cache.put(url, (System.currentTimeMillis(), Json.obj()))
        Json.obj()
    }.recover {
      case e =>
        e.printStackTrace()
        cache.put(url, (System.currentTimeMillis(), Json.obj()))
        Json.obj()
    }
  }

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(certs) => {
        val config =  (context.config \ "HasClientCertMatchingHttpValidator").asOpt[JsValue].getOrElse(context.config)
        val url =     (config \ "url").as[String]
        val headers = (config \ "headers").as[Map[String, String]]
        val ttl =     (config \ "ttl").as[Long]
        val start =   System.currentTimeMillis()
        cache.get(url) match {
          case None                                        =>
            fetch(url, headers, ttl).map(b => validate(certs, b))
          case Some((time, values)) if start - time <= ttl =>
            FastFuture.successful(validate(certs, values))
          case Some((time, values)) if start - time > ttl  =>
            fetch(url, headers, ttl)
            FastFuture.successful(validate(certs, values))
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

class HasAllowedUsersValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.user match {
      case Some(user) => {
        val allowedUsernames = (context.config \ "usernames").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmails = (context.config \ "emails").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmailDomains = (context.config \ "emailDomains").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        if (allowedUsernames.contains(user.name) || allowedEmails.contains(user.email) || allowedEmailDomains.exists(domain => user.email.endsWith(domain))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

class HasAllowedApiKeyValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.apikey match {
      case Some(apiKey) => {
        val allowedClientIds = (context.config \ "clientIds").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedTags = (context.config \ "tags").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedMetadatas = (context.config \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        if (allowedClientIds.contains(apiKey.clientId) || allowedTags.exists(tag => apiKey.tags.contains(tag)) || allowedMetadatas.exists(meta => apiKey.metadata.get(meta._1) == Some(meta._2))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

case class ExternalHttpValidatorConfig(config: JsValue) {
  lazy val url: String = (config \ "url").as[String]
  lazy val host: String = (config \ "host").asOpt[String].getOrElse(Uri(url).authority.host.toString())
  lazy val goodTtl: Long = (config \ "goodTtl").asOpt[Long].getOrElse(10L * 60000L)
  lazy val badTtl: Long = (config \ "badTtl").asOpt[Long].getOrElse(1L * 60000L)
  lazy val method: String = (config \ "method").asOpt[String].getOrElse("POST")
  lazy val path: String = (config \ "path").asOpt[String].getOrElse("/certificates/_validate")
  lazy val timeout: Long = (config \ "timeout").asOpt[Long].getOrElse(10000L)
  lazy val noCache: Boolean = (config \ "noCache").asOpt[Boolean].getOrElse(false)
  lazy val allowNoClientCert: Boolean = (config \ "allowNoClientCert").asOpt[Boolean].getOrElse(false)
  lazy val headers: Map[String, String] = (config \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val proxy: Option[WSProxyServer] = (config \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p))
}

class ExternalHttpValidator extends AccessValidator {

  import utils.http.Implicits._

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

  private def setGoodLocalValidation(key: String, goodTtl: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, true, goodTtl).map(_ => ())
  }

  private def setBadLocalValidation(key: String, badTtl: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, false, badTtl).map(_ => ())
  }

  private def validateCertificateChain(
    chain: Seq[X509Certificate],
    desc: ServiceDescriptor,
    apikey: Option[ApiKey] = None,
    user: Option[PrivateAppsUser] = None,
    cfg: ExternalHttpValidatorConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] = {
    val globalConfig= env.datastores.globalConfigDataStore.latest()
    val certPayload = chain
      .map { cert =>
        s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
      }
      .mkString("\n")
    val payload = Json.obj(
      "apikey" -> apikey.map(_.toJson.as[JsObject] - "clientSecret").getOrElse(JsNull).as[JsValue],
      "user"   -> user.map(_.toJson).getOrElse(JsNull).as[JsValue],
      "service" -> Json.obj(
        "id"        -> desc.id,
        "name"      -> desc.name,
        "groupId"   -> desc.groupId,
        "domain"    -> desc.domain,
        "env"       -> desc.env,
        "subdomain" -> desc.subdomain,
        "root"      -> desc.root,
        "metadata"  -> desc.metadata
      ),
      "chain"        -> certPayload,
      "fingerprints" -> JsArray(chain.map(computeFingerPrint).map(JsString.apply))
    )
    val finalHeaders: Seq[(String, String)] = cfg.headers.toSeq ++ Seq("Host" -> cfg.host,
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json")
    env.Ws
      .url(cfg.url + cfg.path)
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
          case _ =>
            resp.ignore()(env.otoroshiMaterializer)
            None
        }
      }
      .recover {
        case e =>
          ClientCertificateValidator.logger.error("Error while validating client certificate chain", e)
          None
      }
  }

  def canAccessWithClientCertChain(chain: Seq[X509Certificate], context: AccessContext, valCfg: ExternalHttpValidatorConfig)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val apikey = context.apikey
    val user = context.user
    val desc = context.descriptor
    val key = computeKeyFromChain(chain) + "-" + apikey
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
        case None => {
          validateCertificateChain(chain, desc, apikey, user, valCfg).flatMap {
            case Some(false) => setBadLocalValidation(key, valCfg.badTtl).map(_ => false)
            case Some(true)  => setGoodLocalValidation(key, valCfg.goodTtl).map(_ => true)
            case None        => setBadLocalValidation(key, valCfg.badTtl).map(_ => false)
          }
        }
      }
    }
  }

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val valCfg = ExternalHttpValidatorConfig(context.config)
    context.request.clientCertificateChain match {
      case None if !valCfg.allowNoClientCert => FastFuture.successful(false)
      case None if valCfg.allowNoClientCert => {
        val chain: Seq[X509Certificate] = Seq.empty
        canAccessWithClientCertChain(chain, context, valCfg)
      }
      case Some(chain) => {
        canAccessWithClientCertChain(chain, context, valCfg)
      }
    }
  }
}
