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
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.plugins.jobs.kubernetes.{KubernetesClient, KubernetesConfig}
import otoroshi.utils.ReplaceAllWith
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme

import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

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

class EnvVault(vaultName: String, env: Env) extends Vault {

  private val logger        = Logger("otoroshi-env-vault")
  private val defaultPrefix =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${vaultName}.prefix")

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

class HashicorpVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-hashicorp-vault")

  private val url   = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
    .getOrElse("http://127.0.0.1:8200")
  private val mount =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.mount").getOrElse("secret")
  private val kv    = env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.kv").getOrElse("v2")
  private val token =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.token").getOrElse("root")

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

class AzureVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-azure-vault")

  private val baseUrl                    = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
    .getOrElse("https://myvault.vault.azure.net")
  private val apiVersion                 =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.api-version").getOrElse("7.2")
  private val maybetoken: Option[String] = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.token")
  private val tokenKey                   = "token"

  private val tokenCache = Scaffeine().maximumSize(2).expireAfterWrite(1.hour).build[String, String]()

  private def dataUrl(path: String, options: Map[String, String]) = {
    val opts =
      if (options.nonEmpty) s"?api-version=${apiVersion}&" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&")
      else s"?api-version=${apiVersion}"
    s"${baseUrl}/secrets${path}${opts}"
  }

  private def getToken(): Future[Either[String, String]] = {
    maybetoken match {
      case Some(token) => token.right[String].future
      case None        => {
        tokenCache.getIfPresent(tokenKey) match {
          case Some(token) => token.right[String].future
          case None        => {
            implicit val ec  = env.otoroshiExecutionContext
            val tenant       = env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.tenant").get
            val clientId     =
              env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client_id").get
            val clientSecret =
              env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client_secret").get
            val url          = s"https://login.microsoftonline.com/${tenant}/oauth2/token"
            logger.debug(s"fetching azure access_token from '${url}' ...")
            env.Ws
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

  def fetchSecret(url: String, token: String)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[CachedVaultSecretStatus] = {
    logger.debug(s"fetching secret at '${url}'")
    env.Ws
      .url(url)
      .withHttpHeaders("Authorization" -> s"Bearer ${token}")
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          logger.debug(s"found secret at '${url}'") // with value '${response.json.select("value").asOpt[JsValue]}'")
          response.json.select("value").asOpt[JsValue] match {
            case Some(JsString(value))  => CachedVaultSecretStatus.SecretReadSuccess(value)
            case Some(JsNumber(value))  => CachedVaultSecretStatus.SecretReadSuccess(value.toString())
            case Some(JsBoolean(value)) => CachedVaultSecretStatus.SecretReadSuccess(value.toString)
            case Some(o @ JsObject(_))  => CachedVaultSecretStatus.SecretReadSuccess(o.stringify)
            case Some(arr @ JsArray(_)) => CachedVaultSecretStatus.SecretReadSuccess(arr.stringify)
            case Some(JsNull)           => CachedVaultSecretStatus.SecretReadSuccess("null")
            case _                      => CachedVaultSecretStatus.SecretValueNotFound
          }
        } else if (response.status == 401) {
          logger.debug(s"secret at '$url' not found because of 401: ${response.body}")
          tokenCache.invalidate(tokenKey)
          CachedVaultSecretStatus.SecretReadUnauthorized
        } else if (response.status == 403) {
          logger.debug(s"secret at '$url' not found because of 403: ${response.body}")
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
    val url = dataUrl(path, options)
    for {
      token  <- getToken()
      status <- token match {
                  case Left(err)          =>
                    logger.debug(s"unable to get access_token: ${err}")
                    CachedVaultSecretStatus.SecretReadError(s"unable to get access_token: ${err}").future
                  case Right(accessToken) => fetchSecret(url, accessToken)
                }
    } yield {
      status
    }
  }
}

class GoogleSecretManagerVault(name: String, env: Env) extends Vault {

  private val logger  = Logger("otoroshi-gcloud-vault")
  private val baseUrl = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
    .getOrElse("https://secretmanager.googleapis.com")
  private val apikey  =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.apikey").getOrElse("secret")

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

class AlibabaCloudSecretManagerVault(name: String, env: Env) extends Vault {

  private val logger          = Logger("otoroshi-alibaba-cloud-vault")
  private val baseUrl         = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
    .getOrElse("https://kms.eu-central-1.aliyuncs.com")
  private val accessKeyId     = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-id")
    .getOrElse("access-key")
  private val accessKeySecret = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-secret")
    .getOrElse("secret")

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

class KubernetesVault(name: String, env: Env) extends Vault {

  private val logger        = Logger("otoroshi-kubernetes-vault")
  private implicit val _env = env
  private implicit val ec   = env.otoroshiExecutionContext

  private val kubeConfig =
    env.configurationJson.select(s"otoroshi").select("vaults").select(name).asOpt[JsValue] match {
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
  private val client     = new KubernetesClient(kubeConfig, env)

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

class AwsVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-aws-vault")

  private val accessKey       =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key").getOrElse("key")
  private val accessKeySecret = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.access-key-secret")
    .getOrElse("secret")
  private val region          =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.region").getOrElse("eu-west-3")

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

class IzanamiVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-azure-vault")

  private val baseUrl      = env.configuration
    .getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.url")
    .getOrElse("https://127.0.0.1:9000")
  private val clientId     =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client-id").getOrElse("client")
  private val clientSecret =
    env.configuration.getOptionalWithFileSupport[String](s"otoroshi.vaults.${name}.client-secret").getOrElse("secret")

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

class Vaults(env: Env) {

  private val logger              = Logger("otoroshi-vaults")
  private val secretsTtl          = env.configuration
    .getOptionalWithFileSupport[Long]("otoroshi.vaults.secrets-ttl")
    .map(_.milliseconds)
    .getOrElse(5.minutes)
  private val readTtl             = env.configuration
    .getOptionalWithFileSupport[Long]("otoroshi.vaults.read-ttl")
    .map(_.milliseconds)
    .getOrElse(10.seconds)
  private val parallelFetchs      = env.configuration
    .getOptionalWithFileSupport[Int]("otoroshi.vaults.parallel-fetchs")
    .getOrElse(4)
  private val cachedSecrets: Long =
    env.configuration.getOptionalWithFileSupport[Long]("otoroshi.vaults.cached-secrets").getOrElse(10000L)

  val leaderFetchOnly: Boolean =
    env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.vaults.leader-fetch-only").getOrElse(false)

  private val cache                          =
    Scaffeine().expireAfterWrite(secretsTtl).maximumSize(cachedSecrets).build[String, CachedVaultSecret]()
  private val expressionReplacer             = ReplaceAllWith("\\$\\{vault://([^}]*)\\}")
  private val vaults: TrieMap[String, Vault] = new TrieMap[String, Vault]()
  private implicit val _env                  = env
  private implicit val ec                    = env.otoroshiExecutionContext

  val enabled: Boolean =
    env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.vaults.enabled").getOrElse(false)

  if (enabled) {
    logger.warn("the vaults feature is enabled !")
    logger.warn("be aware that this feature is EXPERIMENTAL and might not work as expected.")
    env.configurationJson.select("otoroshi").select("vaults").asOpt[JsObject].map { vaultsConfig =>
      vaultsConfig.keys.map { key =>
        vaultsConfig.select(key).asOpt[JsObject].map { vault =>
          val typ = vault.select("type").asOpt[String].getOrElse("env")
          if (typ == "env") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new EnvVault(key, env))
          } else if (typ == "hashicorp-vault") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new HashicorpVault(key, env))
          } else if (typ == "azure") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new AzureVault(key, env))
          } else if (typ == "aws") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new AwsVault(key, env))
          } else if (typ == "kubernetes") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new KubernetesVault(key, env))
          } else if (typ == "izanami") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new IzanamiVault(key, env))
          } else if (typ == "gcloud") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new GoogleSecretManagerVault(key, env))
          } else if (typ == "alibaba-cloud") {
            logger.info(s"A vault named '${key}' of kind '${typ}' is now active !")
            vaults.put(key, new AlibabaCloudSecretManagerVault(key, env))
          } else {
            // TODO: support square https://github.com/square/keywhiz ?
            // TODO: support pinterest https://github.com/pinterest/knox ?
            logger.error(s"unknown vault type '${typ}'")
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

  def resolveExpression(expr: String): Future[CachedVaultSecretStatus] = {
    val uri     = Uri(expr)
    val name    = uri.authority.host.toString()
    val path    = uri.path.toString()
    val options = uri.query().toMap
    cache.getIfPresent(expr) match {
      case Some(res) => res.status.vfuture
      case None      => {
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
    }
  }

  def renewSecretsInCache(): Future[Done] = {
    if (enabled) {
      Source(cache.asMap().values.toList)
        .filter { secret =>
          secret.status match {
            case CachedVaultSecretStatus.SecretReadSuccess(_)
                if System.currentTimeMillis() - secret.at.toDate.getTime < (secretsTtl.toMillis - 20000) =>
              false
            case _ => true
          }
        }
        .mapAsync(parallelFetchs) { secret =>
          resolveExpression(secret.key).recover { case e: Throwable =>
            ()
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
        resolveExpression(expr)
          .map {
            case CachedVaultSecretStatus.SecretReadSuccess(value) =>
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
