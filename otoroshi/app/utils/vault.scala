package otoroshi.utils

import akka.http.scaladsl.model.Uri
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.secretsmanager.AWSSecretsManagerAsyncClientBuilder
import com.amazonaws.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResult}
import com.github.blemale.scaffeine.Scaffeine
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.plugins.jobs.kubernetes.{KubernetesClient, KubernetesConfig}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

sealed trait CachedVaultSecretStatus {
  def value: String
}
object CachedVaultSecretStatus {
  case object VaultNotFound extends CachedVaultSecretStatus  { def value: String = "vault-not-found" }
  case object BadSecretPath extends CachedVaultSecretStatus  { def value: String = "bad-secret-path" }
  case object SecretNotFound extends CachedVaultSecretStatus { def value: String = "secret-not-found" }
  case object SecretValueNotFound extends CachedVaultSecretStatus { def value: String = "secret-value-not-found" }
  case object SecretFetchUnauthorized extends CachedVaultSecretStatus { def value: String = "secret-read-not-authorized" }
  case object SecretFetchForbidden extends CachedVaultSecretStatus { def value: String = "secret-read-forbidden" }
  case class  SecretFetchError(error: String) extends CachedVaultSecretStatus { def value: String = s"secret-read-error: ${error}" }
  case class  SecretFetchSuccess(secret: String) extends CachedVaultSecretStatus { def value: String = secret }
}
case class CachedVaultSecret(key: String, at: DateTime, status: CachedVaultSecretStatus)

trait Vault {
  def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus]
}

class EnvVault(vaultName: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-env-vault")
  private val defaultPrefix = env.configuration.getOptional[String](s"otoroshi.vaults.${vaultName}.prefix")

  override def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus] = {
    val prefix = options.get("prefix").orElse(defaultPrefix).getOrElse("")
    val parts = path.split("/").toSeq.map(_.trim).filterNot(_.isEmpty)
    if (parts.isEmpty) {
      CachedVaultSecretStatus.BadSecretPath.vfuture
    } else if (parts.size == 1) {
      val name = prefix + parts.head
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())) match {
        case None => CachedVaultSecretStatus.SecretNotFound.vfuture
        case Some(secret) => CachedVaultSecretStatus.SecretFetchSuccess(secret).vfuture
      }
    } else {
      val name = prefix + parts.head
      val pointer = parts.tail.mkString("/", "/", "")
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())).filter(_.trim.startsWith("{")).flatMap { jsonraw =>
        Try {
          val obj = Json.parse(jsonraw).asOpt[JsObject].getOrElse(Json.obj())
          obj.atPointer(pointer).asOpt[JsValue] match {
            case Some(JsString(value)) => value.some
            case Some(JsNumber(value)) => value.toString().some
            case Some(JsBoolean(value)) => value.toString.some
            case Some(o@JsObject(_)) => o.stringify.some
            case Some(arr@JsArray(_)) => arr.stringify.some
            case Some(JsNull) => "null".some
            case _ => None
          }
        } match {
          case Failure(e) =>
            logger.error("error while trying to read JSON env. variable", e)
            CachedVaultSecretStatus.SecretFetchError(e.getMessage).some
          case Success(None) => CachedVaultSecretStatus.SecretNotFound.some
          case Success(Some(secret)) => CachedVaultSecretStatus.SecretFetchSuccess(secret).some
        }
      } match {
        case None => CachedVaultSecretStatus.SecretNotFound.vfuture
        case Some(status) => status.vfuture
      }
    }
  }
}

class HashicorpVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-hashicorp-vault")

  private val protocol = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.protocol").getOrElse("http")
  private val host = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.host").getOrElse("127.0.0.1")
  private val port = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.port").getOrElse("8200")
  private val mount = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.mount").getOrElse("secret")
  private val kv = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.kv").getOrElse("v2")
  private val token = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.token").getOrElse("root")

  private val baseUrl = s"${protocol}://${host}:${port}/v1/${mount}"

  private def dataUrlV2(path: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) "?" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else ""
    s"${baseUrl}/data${path}${opts}"
  }

  private def dataUrlV1(path: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) "?" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else ""
    s"${baseUrl}${path}${opts}"
  }

  override def get(rawpath: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus] = {
    val parts = rawpath.split("/").toSeq.filterNot(_.isEmpty)
    val path = parts.init.mkString("/", "/", "")
    val valuename = parts.last
    val url = if (kv == "v2") dataUrlV2(path, options) else dataUrlV1(path, options)
    env.Ws.url(url)
      .withHttpHeaders("X-Vault-Token" -> token)
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          if (kv == "v2") {
            response.json.select("data").select("data").select(valuename).asOpt[String] match {
              case None => CachedVaultSecretStatus.SecretValueNotFound
              case Some(value) => CachedVaultSecretStatus.SecretFetchSuccess(value)
            }
          } else {
            response.json.select("data").select(valuename).asOpt[String] match {
              case None => CachedVaultSecretStatus.SecretValueNotFound
              case Some(value) => CachedVaultSecretStatus.SecretFetchSuccess(value)
            }
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretFetchUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretFetchForbidden
        } else {
          CachedVaultSecretStatus.SecretFetchError(response.status + " - " + response.body)
        }
      }.recover {
        case e: Throwable => CachedVaultSecretStatus.SecretFetchError(e.getMessage)
      }
  }
}

class AzureVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-azure-vault")

  private val baseUrl = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.base-url").getOrElse("https://myvault.vault.azure.net/")
  private val apiVersion = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.api-version").getOrElse("7.2")
  private val token = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.token").getOrElse("root") // TODO: get it automatically with client_credential flow

  private def dataUrl(path: String, options: Map[String, String]) = {
    val opts = if (options.nonEmpty) s"?api-version=${apiVersion}&" + options.toSeq.map(v => s"${v._1}=${v._2}").mkString("&") else "?api-version=${apiVersion}"
    s"${baseUrl}/secrets${path}${opts}"
  }

  override def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus] = {
    val url = dataUrl(path, options)
    env.Ws.url(url)
      .withHttpHeaders("Authorization" -> s"Bearer ${token}")
      .withRequestTimeout(1.minute)
      .withFollowRedirects(false)
      .get()
      .map { response =>
        if (response.status == 200) {
          response.json.select("value").asOpt[String] match {
            case None => CachedVaultSecretStatus.SecretValueNotFound
            case Some(value) => CachedVaultSecretStatus.SecretFetchSuccess(value)
          }
        } else if (response.status == 401) {
          CachedVaultSecretStatus.SecretFetchUnauthorized
        } else if (response.status == 403) {
          CachedVaultSecretStatus.SecretFetchForbidden
        } else {
          CachedVaultSecretStatus.SecretFetchError(response.status + " - " + response.body)
        }
      }.recover {
        case e: Throwable => CachedVaultSecretStatus.SecretFetchError(e.getMessage)
      }
  }
}

class KubernetesVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-kubernetes-vault")

  private val kubeConfig = KubernetesConfig.theConfig(env.configurationJson.select(s"otoroshi").select("vaults").select(name).asOpt[JsObject].getOrElse(Json.obj()))(env, env.otoroshiExecutionContext)
  private val client = new KubernetesClient(kubeConfig, env)

  override def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus] = {
    val parts = path.split("/").toSeq.filterNot(_.isEmpty)
    val namespace = parts.head
    val secretName = parts.tail.head
    client.fetchSecret(namespace, secretName)
      .map {
        case None => CachedVaultSecretStatus.SecretNotFound
        case Some(secret) => {
          if (parts.size > 2 && secret.hasStringData) {
            val valueName = parts.tail.tail.head
            secret.stringData.getOrElse(Map.empty).get(valueName) match {
              case None => CachedVaultSecretStatus.SecretValueNotFound
              case Some(value) => CachedVaultSecretStatus.SecretFetchSuccess(value)
            }
          } else if (parts.size > 2) {
            CachedVaultSecretStatus.SecretValueNotFound
          } else {
            CachedVaultSecretStatus.SecretFetchSuccess(secret.data)
          }
        }
      }.recover {
        case e: Throwable => CachedVaultSecretStatus.SecretFetchError(e.getMessage)
      }
  }
}

class AWSVault(name: String, env: Env) extends Vault {

  private val logger = Logger("otoroshi-aws-vault")

  private val accessKey = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.access-key").getOrElse("key")
  private val accessKeySecret = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.access-key-secret").getOrElse("secret")
  private val region = env.configuration.getOptional[String](s"otoroshi.vaults.${name}.region").getOrElse("eu-west-3")

  private val secretsManager = AWSSecretsManagerAsyncClientBuilder.standard()
    .withRegion(region)
    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, accessKeySecret)))
    .build()

  override def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[CachedVaultSecretStatus] = {
    val promise = Promise.apply[CachedVaultSecretStatus]()
    val parts = path.split("/").toSeq.filterNot(_.isEmpty)
    var request = new GetSecretValueRequest()
    request = request.withSecretId(parts.head)
    if (parts.size > 1) {
      request = request.withVersionId(parts.tail.head)
    }
    if (parts.size > 2) {
      request = request.withVersionStage(parts.tail.tail.head)
    }
    val handler = new AsyncHandler[GetSecretValueRequest, GetSecretValueResult]() {
      override def onError(exception: Exception): Unit = promise.trySuccess(CachedVaultSecretStatus.SecretFetchError(exception.getMessage))
      override def onSuccess(request: GetSecretValueRequest, result: GetSecretValueResult): Unit = {
        promise.trySuccess(CachedVaultSecretStatus.SecretFetchSuccess(result.getSecretString))
      }
    }
    secretsManager.getSecretValueAsync(request, handler)
    promise.future
  }
}

// TODO: optimize findAll + fillSecrets (without Await.result)
class Vaults(env: Env) {

  private val logger = Logger("otoroshi-vaults")

  private val secretsTtl = env.configuration.getOptional[Long]("otoroshi.vaults.secrets-ttl").map(_.milliseconds).getOrElse(5.minutes)
  private val cachedSecrets: Long = env.configuration.getOptional[Long]("otoroshi.vaults.cached-secrets").getOrElse(10000L)
  private val cache = Scaffeine().expireAfterWrite(secretsTtl).maximumSize(cachedSecrets).build[String, CachedVaultSecret]()
  private val expressionReplacer = ReplaceAllWith("\\$\\{vault://([^}]*)\\}")
  private val vaults: TrieMap[String, Vault] = TrieMap.newBuilder[String, Vault].+=("env" -> new EnvVault("env", env)).result()
  private val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  env.configurationJson.select("otoroshi").select("vaults").asOpt[JsObject].map { vaultsConfig =>
    vaultsConfig.keys.map { key =>
      vaultsConfig.select(key).asOpt[JsObject].map { vault =>
        val typ = vault.select("type").asOpt[String].getOrElse("env")
        if (typ == "env") {
          vaults.put(key, new EnvVault(key, env))
        } else if (typ == "hashicorp-vault") {
          vaults.put(key, new HashicorpVault(key, env))
        } else if (typ == "azure") {
          vaults.put(key, new AzureVault(key, env))
        } else if (typ == "aws") {
          vaults.put(key, ???)
        } else if (typ == "kubernetes") {
          vaults.put(key, new KubernetesVault(key, env))
        } else {
          logger.error(s"unknown vault type '${typ}'")
        }
      }
    }
  }

  def cacheGet(key: String): Option[CachedVaultSecret] = cache.getIfPresent(key)
  def cachePut(key: String, value: CachedVaultSecret): Unit = cache.put(key, value)
  def cacheDelete(key: String): Unit = cache.invalidate(key)

  def fillSecrets(source: String): String = {
    // TODO: log status when not success
    expressionReplacer.replaceOn(source) { expr =>
      val uri = Uri(expr)
      val name = uri.authority.host.toString()
      val path = uri.path.toString()
      val options = uri.query().toMap
      cache.getIfPresent(expr) match {
        case Some(res) => res.status.value
        case None => {
          vaults.get(name) match {
            case None =>
              cache.put(expr, CachedVaultSecret(expr, DateTime.now(), CachedVaultSecretStatus.VaultNotFound))
              CachedVaultSecretStatus.VaultNotFound.value
            case Some(vault) => {
              // TODO: populate a cache that will be periodically updated !
              val status = Await.result(vault.get(path, options)(env, ec), 1.minute)
              val secret = CachedVaultSecret(expr, DateTime.now(), status)
              cache.put(expr, secret)
              status.value
            }
          }
        }
      }
    }
  }
}
