package otoroshi.next.utils

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.secretsmanager.AWSSecretsManagerAsyncClientBuilder
import com.amazonaws.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResult}
import com.github.blemale.scaffeine.Scaffeine
import com.google.common.base.Charsets
import com.nimbusds.jose.jwk.JWK
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.plugins.jobs.kubernetes.{KubernetesClient, KubernetesConfig}
import otoroshi.ssl.SSLImplicits._
import otoroshi.utils.ReplaceAllWith
import otoroshi.utils.cache.Caches
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme
import play.api.{Configuration, Logger}

import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

sealed trait AzureSecretKind {
  def path: String
  def get(json: JsValue, base64: Boolean): CachedVaultSecretStatus
}

object AzureSecretKind {
  case object AzureSecretCertificate extends AzureSecretKind {
    def path: String = "certificates"
    def get(json: JsValue, base64: Boolean): CachedVaultSecretStatus = {
      CachedVaultSecretStatus.SecretReadSuccess(
        otoroshi.ssl.PemHeaders.BeginCertificate + "\n" +
        json.select("cer").asString.decodeBase64.grouped(64).mkString("\n") +
        otoroshi.ssl.PemHeaders.EndCertificate + "\n"
      )
    }
  }
  case object AzureSecretPrivateKey  extends AzureSecretKind {
    def path: String = "keys"
    def get(json: JsValue, base64: Boolean): CachedVaultSecretStatus = {
      val jwk = JWK.parse(json.select("key").asObject.stringify)
      jwk.getKeyType.getValue match {
        case "EC"  => CachedVaultSecretStatus.SecretReadSuccess(jwk.toECKey.toPrivateKey.encoded)
        case "RSA" => CachedVaultSecretStatus.SecretReadSuccess(jwk.toRSAKey.toPrivateKey.encoded)
        case t     => CachedVaultSecretStatus.SecretReadError(s"bad jwk type: ${t}")
      }
    }
  }
  case object AzureSecretPublicKey   extends AzureSecretKind {
    def path: String = "keys"
    def get(json: JsValue, base64: Boolean): CachedVaultSecretStatus = {
      val jwk = JWK.parse(json.select("key").asObject.stringify)
      jwk.getKeyType.getValue match {
        case "EC"  => CachedVaultSecretStatus.SecretReadSuccess(jwk.toECKey.toPublicKey.encoded)
        case "RSA" => CachedVaultSecretStatus.SecretReadSuccess(jwk.toRSAKey.toPublicKey.encoded)
        case t     => CachedVaultSecretStatus.SecretReadError(s"bad jwk type: ${t}")
      }
    }
  }
  case object AzureSecret            extends AzureSecretKind {
    def path: String = "secrets"
    def get(json: JsValue, base64: Boolean): CachedVaultSecretStatus = {
      json.select("value").asOpt[JsValue] match {
        case Some(JsString(value)) if base64 => CachedVaultSecretStatus.SecretReadSuccess(value.decodeBase64)
        case Some(JsString(value))           => CachedVaultSecretStatus.SecretReadSuccess(value)
        case Some(JsNumber(value))           => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
        case Some(JsBoolean(value))          => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
        case Some(o @ JsObject(_))           => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
        case Some(arr @ JsArray(_))          => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
        case Some(JsNull)                    => CachedVaultSecretStatus.SecretReadSuccess("null")
        case _                               => CachedVaultSecretStatus.SecretValueNotFound
      }
    }
  }
}

sealed trait CachedVaultSecretStatus {
  def value: String
}

object CachedVaultSecretStatus {
  case object VaultNotFound                    extends CachedVaultSecretStatus { def value: String = "vault-not-found"        }
  case object BadSecretPath                    extends CachedVaultSecretStatus { def value: String = "bad-secret-path"        }
  case object SecretNotFound                   extends CachedVaultSecretStatus { def value: String = "secret-not-found"       }
  case object SecretValueNotFound              extends CachedVaultSecretStatus { def value: String = "secret-value-not-found" }
  case object SecretReadUnauthorized           extends CachedVaultSecretStatus {
    def value: String = "secret-read-not-authorized"
  }
  case object SecretReadForbidden              extends CachedVaultSecretStatus { def value: String = "secret-read-forbidden"  }
  case object SecretReadTimeout                extends CachedVaultSecretStatus { def value: String = "secret-read-timeout"    }
  case class SecretReadError(error: String)    extends CachedVaultSecretStatus {
    def value: String = s"secret-read-error: ${error}"
  }
  case class SecretReadSuccess(secret: String) extends CachedVaultSecretStatus { def value: String = secret                   }
}

case class CachedVaultSecret(key: String, at: DateTime, status: CachedVaultSecretStatus)

trait Vault {
  def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus]
}

class EnvVault(vaultName: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger        = Logger("otoroshi-env-vault")
  private val defaultPrefix = configuration.getOptionalWithFileSupport[String](s"prefix")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${vaultName}.prefix")

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val prefix = options.get("prefix").orElse(defaultPrefix).getOrElse("")
    val parts  = path.split("/").toSeq.map(_.trim).filterNot(_.isEmpty)
    if (parts.isEmpty) {
      CachedVaultSecretStatus.BadSecretPath.vfuture
    } else if (parts.size == 1) {
      val name = prefix + parts.head
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())) match {
        case None         => CachedVaultSecretStatus.SecretNotFound.vfuture
        case Some(secret) => CachedVaultSecretStatus.SecretReadSuccess(secret).vfuture
      }
    } else {
      val name    = prefix + parts.head
      val pointer = parts.tail.mkString("/", "/", "")
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())).filter(_.trim.startsWith("{")).flatMap { jsonraw =>
        Try {
          val obj = Json.parse(jsonraw).asOpt[JsObject].getOrElse(Json.obj())
          obj.atPointer(pointer).asOpt[JsValue] match {
            case Some(JsString(value))  => value.some
            case Some(JsNumber(value))  => value.toString().some
            case Some(JsBoolean(value)) => value.toString.some
            case Some(o @ JsObject(_))  => o.stringify.some
            case Some(arr @ JsArray(_)) => arr.stringify.some
            case Some(JsNull)           => "null".some
            case _                      => None
          }
        } match {
          case Failure(e)            =>
            logger.error("error while trying to read JSON env. variable", e)
            CachedVaultSecretStatus.SecretReadError(e.getMessage).some
          case Success(None)         => CachedVaultSecretStatus.SecretNotFound.some
          case Success(Some(secret)) => CachedVaultSecretStatus.SecretReadSuccess(secret).some
        }
      } match {
        case None         => CachedVaultSecretStatus.SecretNotFound.vfuture
        case Some(status) => status.vfuture
      }
    }
  }
}

class LocalVault(vaultName: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger      = Logger("otoroshi-local-vault")
  private val defaultRoot = configuration.getOptionalWithFileSupport[String](s"root")

  private def extractValue(obj: JsObject, path: String): CachedVaultSecretStatus = {
    Try {
      obj.atPointer(path).asOpt[JsValue] match {
        case Some(JsString(value))  => value.some
        case Some(JsNumber(value))  => value.toString().some
        case Some(JsBoolean(value)) => value.toString.some
        case Some(o @ JsObject(_))  => o.stringify.some
        case Some(arr @ JsArray(_)) => arr.stringify.some
        case Some(JsNull)           => "null".some
        case _                      => None
      }
    } match {
      case Failure(e)            =>
        logger.error("error while trying to read JSON env. variable", e)
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      case Success(None)         => CachedVaultSecretStatus.SecretNotFound
      case Success(Some(secret)) => CachedVaultSecretStatus.SecretReadSuccess(secret)
    }
  }

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts = path.split("/").toSeq.map(_.trim).filterNot(_.isEmpty)
    if (parts.isEmpty) {
      CachedVaultSecretStatus.BadSecretPath.vfuture
    } else {
      val root = options.get("root").orElse(defaultRoot)
      val name = (root ++ parts).mkString("/", "/", "")
      extractValue(env.datastores.globalConfigDataStore.latest().env, name).vfuture
    }
  }
}

class HashicorpVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-hashicorp-vault")

  private val url   = configuration.getOptionalWithFileSupport[String]("url").getOrElse("http://127.0.0.1:8200")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url").getOrElse("http://127.0.0.1:8200")
  private val mount = configuration.getOptionalWithFileSupport[String]("mount").getOrElse("secret")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.mount").getOrElse("secret")
  private val kv    = configuration.getOptionalWithFileSupport[String](s"kv").getOrElse("v2")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.kv").getOrElse("v2")
  private val token = configuration.getOptionalWithFileSupport[String](s"token").getOrElse("root")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.token").getOrElse("root")

  private val baseUrl = s"${url}/v1/${mount}"

  private def dataUrlV2(path: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) "?" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else ""
    s"${baseUrl}/data${path}${opts}"
  }

  private def dataUrlV1(path: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) "?" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else ""
    s"${baseUrl}${path}${opts}"
  }

  override def get(rawpath: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts     = rawpath.split("/").toSeq.filterNot(_.isEmpty)
    val path      = parts.init.mkString("/", "/", "")
    val valuename = parts.last
    val url       = if (kv == "v2") dataUrlV2(path, options) else dataUrlV1(path, options)
    env.Ws
      .url(url)
      .withHttpHeaders("X-Vault-Token" -> token)
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          if (kv == "v2") {
            response.json.select("data").select("data").select(valuename).asOpt[JsValue] match {
              case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
              case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
              case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
              case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
              case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
              case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
              case _                      => CachedVaultSecretStatus.SecretValueNotFound
            }
          } else {
            response.json.select("data").select(valuename).asOpt[JsValue] match {
              case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
              case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
              case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
              case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
              case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
              case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
              case _                      => CachedVaultSecretStatus.SecretValueNotFound
            }
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class AzureVault(_name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-azure-vault")

  private val baseUrl                    = configuration
    .getOptionalWithFileSupport[String]("url")
    .getOrElse("https://myvault.vault.azure.net")
  private val apiVersion                 = configuration.getOptionalWithFileSupport[String]("api-version").getOrElse("7.2")
  private val maybetoken: Option[String] = configuration
    .getOptionalWithFileSupport[String]("token")
  private val tokenKey                   = "token"

  private val tokenCache = Scaffeine().maximumSize(2).expireAfterWrite(1.hour).build[String, String]()

  private def dataUrl(path: String, kind: AzureSecretKind, options: Map[String, String]) = {
    val opts =
      if (options.nonEmpty) s"?api-version=${apiVersion}&" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&")
      else s"?api-version=${apiVersion}"
    s"${baseUrl}/${kind.path}${path}${opts}"
  }

  private def getToken(): Future[Either[String, String]] = {
    maybetoken match {
      case Some(token) => token.right[String].future
      case None        => {
        tokenCache.getIfPresent(tokenKey) match {
          case Some(token) => token.right[String].future
          case None        => {
            implicit val ec  = _env.otoroshiExecutionContext
            val tenant       = configuration.getOptionalWithFileSupport[String](s"tenant").get
            //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.tenant").get
            val clientId     =
              configuration.getOptionalWithFileSupport[String](s"client_id").get
            // env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client_id").get
            val clientSecret =
              configuration.getOptionalWithFileSupport[String](s"client_secret").get
            // env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client_secret").get
            val url          = s"https://login.microsoftonline.com/${tenant}/oauth2/token"
            if (logger.isDebugEnabled) logger.debug(s"fetching azure access_token from '${url}' ...")
            _env.Ws
              .url(url)
              .post(
                Map(
                  "grant_type"    -> "client_credentials",
                  "client_id"     -> clientId,
                  "client_secret" -> clientSecret,
                  "resource"      -> "https://vault.azure.net"
                )
              )
              .map { resp =>
                if (resp.status == 200) {
                  resp.json.select("access_token").asOpt[String] match {
                    case None              => {
                      tokenCache.invalidate(tokenKey)
                      Left(s"no access_token found in response: ${resp.body}")
                    }
                    case Some(accessToken) => {
                      tokenCache.put(tokenKey, accessToken)
                      accessToken.right[String]
                    }
                  }
                } else {
                  tokenCache.invalidate(tokenKey)
                  Left(s"bad status code for response: ${resp.body}")
                }
              }
              .recover { case e: Throwable =>
                logger.error("error while fetching azure key vault token", e)
                tokenCache.invalidate(tokenKey)
                Left(s"error while fetching azure key vault token: ${e.getMessage}")
              }
          }
        }
      }
    }
  }

  def fetchSecret(url: String, token: String, base64: Boolean, kind: AzureSecretKind)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    if (logger.isDebugEnabled) logger.debug(s"fetching secret at '${url}'")
    env.Ws
      .url(url)
      .withHttpHeaders("Authorization" -> s"Bearer ${token}")
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          if (logger.isDebugEnabled)
            logger.debug(s"found secret at '${url}'") // with value '${response.json.select("value").asOpt[JsValue]}'")
          kind.get(response.json, base64)
        } else if (response.status == 401) {
          if (logger.isDebugEnabled) logger.debug(s"secret at '$url' not found because of 401: ${response.body}")
          tokenCache.invalidate(tokenKey)
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          if (logger.isDebugEnabled) logger.debug(s"secret at '$url' not found because of 403: ${response.body}")
          // tokenCache.invalidate(tokenKey) ???
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val finalOpts             = options - "azure_secret_base_64" - "azure_secret_kind"
    val base64                = options.get("azure_secret_base_64").contains("true")
    val kind: AzureSecretKind = options.get("azure_secret_kind") match {
      case Some("privkey")     => AzureSecretKind.AzureSecretPrivateKey
      case Some("pubkey")      => AzureSecretKind.AzureSecretPublicKey
      case Some("certificate") => AzureSecretKind.AzureSecretCertificate
      case _                   => AzureSecretKind.AzureSecret
    }
    val url                   = dataUrl(path, kind, finalOpts)
    for {
      token  <- getToken()
      status <- token match {
                  case Left(err)          =>
                    if (logger.isDebugEnabled) logger.debug(s"unable to get access_token: ${err}")
                    CachedVaultSecretStatus.SecretReadError(s"unable to get access_token: ${err}").future
                  case Right(accessToken) => fetchSecret(url, accessToken, base64, kind)
                }
    } yield {
      status
    }
  }
}

class GoogleSecretManagerVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger  = Logger("otoroshi-gcloud-vault")
  private val baseUrl = configuration
    .getOptionalWithFileSupport[String](s"url")
    .getOrElse("https://secretmanager.googleapis.com")
  // env.configuration
  // .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
  // .getOrElse("https://secretmanager.googleapis.com")
  private val apikey  =
    configuration.getOptionalWithFileSupport[String](s"apikey").getOrElse("secret")
  // env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.apikey").getOrElse("secret")

  private def dataUrl(path: String, options: Map[String, String]) = {
    val opts =
      if (options.nonEmpty) s"?key=${apikey}&" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&")
      else s"?key=${apikey}"
    s"${baseUrl}/v1${path}:access${opts}"
  }

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val url = dataUrl(path, options)
    env.Ws
      .url(url)
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.select("payload").select("data").asOpt[String] match {
            case Some(value) => CachedVaultSecretStatus.SecretReadSuccess(value.fromBase64)
            case _           => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class AlibabaCloudSecretManagerVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger          = Logger("otoroshi-alibaba-cloud-vault")
  private val baseUrl         = configuration
    .getOptionalWithFileSupport[String](s"url")
    .getOrElse("https://kms.eu-central-1.aliyuncs.com")
  // env.configuration
  // .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
  // .getOrElse("https://kms.eu-central-1.aliyuncs.com")
  private val accessKeyId     = configuration
    .getOptionalWithFileSupport[String](s"access-key-id")
    .getOrElse("access-key")
  // env.configuration
  // .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-id")
  // .getOrElse("access-key")
  private val accessKeySecret = configuration
    .getOptionalWithFileSupport[String](s"access-key-secret")
    .getOrElse("secret")
  // env.configuration
  // .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-secret")
  // .getOrElse("secret")

  def makeStringToSign(opts: String): String = {
    "GET%2F&" + URLEncoder.encode(opts, Charsets.UTF_8)
  }

  def makeSignature(stringToSign: String, secret: String): String = {
    Base64.getEncoder.encodeToString(Signatures.hmac("HmacSHA1", stringToSign, secret))
  }

  private def dataUrl(path: String, options: Map[String, String]): String = {
    val opts      = if (options.nonEmpty) options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else s""
    val name      = path.split("/").filterNot(_.isEmpty).head
    val timestamp = DateTime.now().toString()
    val query     =
      s"Action=GetSecretValue&SecretName=${name}&Format=json&AccessKeyId=${accessKeyId}&SignatureMethod=HMAC-SHA1&Timestamp=${timestamp}&SignatureVersion=1.0&${opts}"
    val signature = makeSignature(query, accessKeySecret)
    s"${baseUrl}/?${query}&Signature=${signature}"
  }

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val url = dataUrl(path, options)
    env.Ws
      .url(url)
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.select("SecretData").asOpt[String] match {
            case Some(value) => CachedVaultSecretStatus.SecretReadSuccess(value)
            case _           => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class KubernetesVault(name: String, configuration: Configuration, env: Env) extends Vault {

  private val logger        = Logger("otoroshi-kubernetes-vault")
  private implicit val _env = env
  private implicit val ec   = env.otoroshiExecutionContext

  private val kubeConfig = {
    //env.configurationJson
    configuration.json.select(s"otoroshi").select("vaults").select(name).asOpt[JsValue] match {
      case Some(JsString("global")) => {
        val global = env.datastores.globalConfigDataStore.latest()
        val c1     = global.scripts.jobConfig.select("KubernetesConfig").asOpt[JsObject]
        val c2     = global.plugins.config.select("KubernetesConfig").asOpt[JsObject]
        val c3     = c1.orElse(c2).getOrElse(Json.obj())
        KubernetesConfig.theConfig(c3)
      }
      case Some(obj @ JsObject(_))  => KubernetesConfig.theConfig(obj)
      case _                        => KubernetesConfig.theConfig(KubernetesConfig.defaultConfig)
    }
  }
  private val client = new KubernetesClient(kubeConfig, env)

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts      = path.split("/").toSeq.filterNot(_.isEmpty)
    val namespace  = parts.head
    val secretName = parts.tail.head
    client
      .fetchSecret(namespace, secretName)
      .map {
        case None         => CachedVaultSecretStatus.SecretNotFound
        case Some(secret) => {
          if (parts.size > 2 && secret.hasStringData) {
            val valueName = parts.tail.tail.head
            secret.stringData.getOrElse(Map.empty).get(valueName) match {
              case None        => CachedVaultSecretStatus.SecretValueNotFound
              case Some(value) => CachedVaultSecretStatus.SecretReadSuccess(value)
            }
          } else if (parts.size > 2) {
            CachedVaultSecretStatus.SecretValueNotFound
          } else {
            CachedVaultSecretStatus.SecretReadSuccess(secret.data)
          }
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class AwsVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-aws-vault")

  private val accessKey       =
    configuration.getOptionalWithFileSupport[String](s"access-key").getOrElse("key")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key").getOrElse("key")
  private val accessKeySecret = configuration
    .getOptionalWithFileSupport[String](s"access-key-secret")
    .getOrElse("secret")
  //env.configuration
  //.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-secret")
  //.getOrElse("secret")
  private val region          =
    configuration.getOptionalWithFileSupport[String](s"region").getOrElse("eu-west-3")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.region").getOrElse("eu-west-3")

  private val secretsManager = AWSSecretsManagerAsyncClientBuilder
    .standard()
    .withRegion(region)
    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, accessKeySecret)))
    .build()

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val promise = Promise.apply[CachedVaultSecretStatus]()
    try {
      val parts   = path.split("/").toSeq.filterNot(_.isEmpty)
      var request = new GetSecretValueRequest()
      request = request.withSecretId(parts.head)
      if (parts.size > 1) {
        request = request.withVersionId(parts.tail.head)
      }
      if (parts.size > 2) {
        request = request.withVersionStage(parts.tail.tail.head)
      }
      val handler = new AsyncHandler[GetSecretValueRequest, GetSecretValueResult]() {
        override def onError(exception: Exception): Unit =
          promise.trySuccess(CachedVaultSecretStatus.SecretReadError(exception.getMessage))
        override def onSuccess(request: GetSecretValueRequest, result: GetSecretValueResult): Unit = {
          promise.trySuccess(CachedVaultSecretStatus.SecretReadSuccess(result.getSecretString))
        }
      }
      secretsManager.getSecretValueAsync(request, handler)
    } catch {
      case e: Throwable => promise.trySuccess(CachedVaultSecretStatus.SecretReadError(e.getMessage))
    }
    promise.future
  }
}

class IzanamiVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-azure-vault")

  private val baseUrl      = configuration
    .getOptionalWithFileSupport[String](s"url")
    .getOrElse("https://127.0.0.1:9000")
  //env.configuration
  //.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
  //.getOrElse("https://127.0.0.1:9000")
  private val clientId     =
    configuration.getOptionalWithFileSupport[String](s"client-id").getOrElse("client")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client-id").getOrElse("client")
  private val clientSecret =
    configuration.getOptionalWithFileSupport[String](s"client-secret").getOrElse("secret")
  //env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client-secret").getOrElse("secret")

  private def dataUrl(id: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) s"?" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else ""
    s"${baseUrl}/api/configs/${id}${opts}"
  }

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts     = path.split("/").toSeq.filterNot(_.isEmpty)
    val featureId = parts.head
    val pointer   = parts.tail.mkString("/", "/", "")
    val url       = dataUrl(featureId, options)
    env.Ws
      .url(url)
      .withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.atPointer(pointer).asOpt[JsValue] match {
            case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
            case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
            case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
            case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
            case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
            case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
            case _                      => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class SpringCloudConfigVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-spring-cloud-vault")

  private val baseUrl = configuration
    .getOptionalWithFileSupport[String](s"url")
    .getOrElse("http://127.0.0.1:8888")

  private val method  =
    configuration.getOptionalWithFileSupport[String](s"method").getOrElse("GET")
  private val headers =
    configuration.getOptionalWithFileSupport[Map[String, String]](s"headers").getOrElse(Map.empty).toSeq
  private val timeout =
    configuration
      .getOptionalWithFileSupport[Long](s"timeout")
      .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
      .getOrElse(1.minute)
  private val root    =
    configuration.getOptionalWithFileSupport[String](s"root").getOrElse("foo/dev")

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts   = path.split("/").toSeq.filterNot(_.isEmpty)
    val pointer = parts.mkString("/", "/", "")
    val url     = s"${baseUrl}/${root}"
    env.Ws
      .url(url)
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .withMethod(method)
      .withFollowRedirects(false)
      .execute()
      .map { response =>
        println(response.status, response.body)
        if (response.status == 200) {
          val sources = response.json
            .select("propertySources")
            .asOpt[Seq[JsValue]]
            .getOrElse(Seq.empty)
            .map(_.select("source").asOpt[JsObject].getOrElse(Json.obj()))
          val source  = sources.foldRight(Json.obj())((s, next) => s.deepMerge(next))
          source.atPointer(pointer).asOpt[JsValue] match {
            case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
            case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
            case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
            case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
            case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
            case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
            case _                      => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class HttpVault(name: String, configuration: Configuration, _env: Env) extends Vault {

  private val logger = Logger("otoroshi-http-vault")

  private val baseUrl = configuration
    .getOptionalWithFileSupport[String](s"url")
    .getOrElse("http://127.0.0.1:8888")

  private val method  =
    configuration.getOptionalWithFileSupport[String](s"method").getOrElse("GET")
  private val headers =
    configuration.getOptionalWithFileSupport[Map[String, String]](s"headers").getOrElse(Map.empty).toSeq
  private val timeout =
    configuration
      .getOptionalWithFileSupport[Long](s"timeout")
      .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
      .getOrElse(1.minute)

  override def get(path: String, options: Map[String, String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    val parts   = path.split("/").toSeq.filterNot(_.isEmpty)
    val pointer = parts.mkString("/", "/", "")
    val url     = s"${baseUrl}"
    env.Ws
      .url(url)
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .withMethod(method)
      .withFollowRedirects(false)
      .execute()
      .map { response =>
        println(response.status, response.body)
        if (response.status == 200) {
          val source = response.json
          source.atPointer(pointer).asOpt[JsValue] match {
            case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
            case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
            case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
            case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
            case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
            case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
            case _                      => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretReadForbidden
        } else {
          CachedVaultSecretStatus.SecretReadError(response.status + " - " + response.body)
        }
      }
      .recover { case e: Throwable =>
        CachedVaultSecretStatus.SecretReadError(e.getMessage)
      }
  }
}

class Vaults(env: Env) {

  private val logger              = Logger("otoroshi-vaults")
  private val vaultConfig         =
    env._configuration.getOptionalWithFileSupport[Configuration]("otoroshi.vaults").getOrElse(Configuration.empty)
  private val secretsTtl          = vaultConfig
    .getOptionalWithFileSupport[Long]("secrets-ttl")
    .map(_.milliseconds)
    .getOrElse(5.minutes)
  private val secretsErrorTtl     = vaultConfig
    .getOptionalWithFileSupport[Long]("secrets-error-ttl")
    .map(_.milliseconds)
    .getOrElse(20.seconds)
  private val readTtl             = vaultConfig
    .getOptionalWithFileSupport[Long]("read-timeout")
    .map(_.milliseconds)
    .getOrElse(10.seconds)
  private val parallelFetchs      = vaultConfig
    .getOptionalWithFileSupport[Int]("parallel-fetchs")
    .getOrElse(4)
  private val cachedSecrets: Long =
    vaultConfig.getOptionalWithFileSupport[Long]("cached-secrets").getOrElse(10000L)

  val leaderFetchOnly: Boolean =
    vaultConfig.getOptionalWithFileSupport[Boolean]("leader-fetch-only").getOrElse(false)

  private val cache                          = Caches.bounded[String, CachedVaultSecret](cachedSecrets.toInt)
  // Scaffeine().expireAfterWrite(secretsTtl).maximumSize(cachedSecrets).build[String, CachedVaultSecret]()
  private val expressionReplacer             = ReplaceAllWith("\\$\\{vault://([^}]*)\\}")
  private val vaults: TrieMap[String, Vault] = new UnboundedTrieMap[String, Vault]()
  private implicit val _env                  = env
  private implicit val ec                    = env.otoroshiExecutionContext

  val enabled: Boolean =
    vaultConfig.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(false)

  if (enabled) {
    logger.warn("the vaults feature is enabled !")
    logger.warn("be aware that this feature is EXPERIMENTAL and might not work as expected.")
    val vaultsConfig = vaultConfig.json
    vaultsConfig.keys.map { key =>
      vaultsConfig.select(key).asOpt[JsObject].map { vault =>
        val typ = vault.select("type").asOpt[String].getOrElse("env")
        if (typ == "env") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new EnvVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "local") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new LocalVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "hashicorp-vault") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new HashicorpVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "azure") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new AzureVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "aws") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new AwsVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "kubernetes") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new KubernetesVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "izanami") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new IzanamiVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "spring-cloud") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new SpringCloudConfigVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "http") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new HttpVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "gcloud") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new GoogleSecretManagerVault(key, vaultConfig.get[Configuration](key), env))
        } else if (typ == "alibaba-cloud") {
          logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
          vaults.put(key, new AlibabaCloudSecretManagerVault(key, vaultConfig.get[Configuration](key), env))
        } else {
          // TODO: support square https://github.com/square/keywhiz ?
          // TODO: support pinterest https://github.com/pinterest/knox ?
          env.adminExtensions.vault(typ) match {
            case None => logger.error(s"unknown vault type '${typ}'")
            case Some(adminVault) => {
              logger.info(s"a vault named '${key}' of kind '${typ}' is now active !")
              vaults.put(key, adminVault.build(key, vaultConfig.get[Configuration](key), env))
            }
          }
        }
      }
    }
  }

  def timeout(status: CachedVaultSecretStatus): Future[CachedVaultSecretStatus] = {
    val promise = Promise[CachedVaultSecretStatus]()
    env.otoroshiScheduler.scheduleOnce(readTtl) {
      promise.trySuccess(status)
    }
    promise.future
  }

  def getWithTimeout(vault: Vault, path: String, options: Map[String, String]): Future[CachedVaultSecretStatus] = {
    Future.firstCompletedOf(
      Seq(
        vault.get(path, options),
        timeout(CachedVaultSecretStatus.SecretReadTimeout)
      )
    )
  }

  def resolveExpression(expr: String, force: Boolean): Future[CachedVaultSecretStatus] = {
    val uri     = Uri(expr)
    val name    = uri.authority.host.toString()
    val path    = uri.path.toString()
    val options = uri.query().toMap

    def fetchSecret(): Future[CachedVaultSecretStatus] = {
      vaults.get(name) match {
        case None        =>
          cache.put(expr, CachedVaultSecret(expr, DateTime.now(), CachedVaultSecretStatus.VaultNotFound))
          CachedVaultSecretStatus.VaultNotFound.vfuture
        case Some(vault) => {
          getWithTimeout(vault, path, options)
            .map { status =>
              val theStatus = status match {
                case CachedVaultSecretStatus.SecretReadSuccess(v) =>
                  val computed = JsString(v).stringify.substring(1).init
                  CachedVaultSecretStatus.SecretReadSuccess(computed)
                case s                                            => s
              }
              val secret    = CachedVaultSecret(expr, DateTime.now(), theStatus)
              cache.put(expr, secret)
              theStatus
            }
            .recover {
              case e: Throwable => {
                val secret =
                  CachedVaultSecret(expr, DateTime.now(), CachedVaultSecretStatus.SecretReadError(e.getMessage))
                cache.put(expr, secret)
                secret.status
              }
            }
        }
      }
    }

    if (force) {
      fetchSecret()
    } else {
      cache.getIfPresent(expr) match {
        case Some(res) => res.status.vfuture
        case None      => fetchSecret()
      }
    }
  }

  def renewSecretsInCache(): Future[Done] = {
    if (enabled) {
      Source(cache.asMap().values.toList)
        .filter { secret =>
          secret.status match {
            case CachedVaultSecretStatus.SecretReadSuccess(_)
                if (System.currentTimeMillis() - secret.at.toDate.getTime) < (secretsTtl.toMillis - 20000) =>
              false
            case _ => true
          }
        }
        .mapAsync(parallelFetchs) { secret =>
          def resolve(force: Boolean): Future[Unit] = {
            resolveExpression(secret.key, force)
              .map(_ => ())
              .recover { case e: Throwable =>
                ()
              }
          }

          val shouldRetry: Boolean = (System.currentTimeMillis() - secret.at.toDate.getTime) > secretsErrorTtl.toMillis
          secret.status match {
            case CachedVaultSecretStatus.SecretReadSuccess(_) => resolve(force = false)
            case CachedVaultSecretStatus.VaultNotFound        => resolve(force = false)
            case _ if shouldRetry                             => resolve(force = true)
            case _ if !shouldRetry                            => resolve(force = false)
          }
        }
        .runWith(Sink.ignore)(env.otoroshiMaterializer)
    } else {
      Done.vfuture
    }
  }

  // private def fillSecrets(source: String): String = {
  //   if (enabled) {
  //     expressionReplacer.replaceOn(source) { expr =>
  //       val status = Await.result(resolveExpression(expr), 1.minute)
  //       status match {
  //         case CachedVaultSecretStatus.SecretReadSuccess(_) => logger.debug(s"fill secret from '${expr}' successfully")
  //         case _ => logger.error(s"filling secret from '${expr}' failed because of '${status.value}'")
  //       }
  //       status.value
  //     }
  //   } else {
  //     source
  //   }
  // }

  def fillSecretsAsync(id: String, source: String)(implicit ec: ExecutionContext): Future[String] = {
    if (enabled) {
      expressionReplacer.replaceOnAsync(source) { expr =>
        resolveExpression(expr, force = false)
          .map {
            case CachedVaultSecretStatus.SecretReadSuccess(value) =>
              if (logger.isDebugEnabled)
                logger.debug(s"fill secret on '${id}' from '${expr}' successfully") //, secret value is '${value}'")
              value
            case status                                           =>
              logger.error(s"filling secret on '${id}' from '${expr}' failed because of '${status.value}'")
              "not-found"
          }
          .recover { case e: Throwable =>
            val error = CachedVaultSecretStatus.SecretReadError(e.getMessage)
            logger.error(s"filling secret on '${id}' from '${expr}' failed because of '${error.value}'")
            "not-found"
          }
      }
    } else {
      source.vfuture
    }
  }
}
