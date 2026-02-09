package otoroshi.next.catalogs

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3._
import akka.stream.scaladsl.Sink
import akka.stream.{Attributes, Materializer}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

class CatalogSourceFile extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-file")

  override def sourceKind: String      = "file"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "file source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] =
    Json.obj("error" -> "file source does not support webhooks").leftf

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val path = catalog.sourceConfig.select("path").asOpt[String].getOrElse("")
    Try {
      val file    = new File(path)
      val content = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      val json    = Json.parse(content)
      val allRes  = env.allResources.resources ++ env.adminExtensions.resources()
      Right(RemoteContentParser.parse(json, s"file://$path", allRes)): Either[JsValue, Seq[RemoteEntity]]
    }.recover { case e: Throwable =>
      logger.error(s"Error reading file $path", e)
      Left(Json.obj("error" -> s"Error reading file: ${e.getMessage}")): Either[JsValue, Seq[RemoteEntity]]
    }.get.vfuture
  }
}

class CatalogSourceHttp extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-http")

  override def sourceKind: String      = "http"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "http source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] =
    Json.obj("error" -> "http source does not support webhooks").leftf

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val url     = catalog.sourceConfig.select("url").asOpt[String].getOrElse("")
    val headers = catalog.sourceConfig.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    val method  = catalog.sourceConfig.select("method").asOpt[String].getOrElse("GET")
    val timeout = catalog.sourceConfig.select("timeout").asOpt[Long].getOrElse(30000L)

    if (url.isEmpty) {
      Json.obj("error" -> "No URL configured").leftf
    } else {
      val req = env.Ws
        .url(url)
        .withRequestTimeout(Duration(timeout, TimeUnit.MILLISECONDS))
        .withHttpHeaders(headers.toSeq: _*)

      val futResp = method.toUpperCase match {
        case "POST" =>
          val body = catalog.sourceConfig.select("body").asOpt[String].getOrElse("")
          req.post(body)
        case _      => req.get()
      }

      futResp.map { resp =>
        if (resp.status == 200) {
          val json   = resp.json
          val allRes = env.allResources.resources ++ env.adminExtensions.resources()
          RemoteContentParser.parse(json, s"http://$url", allRes).right
        } else {
          Json.obj("error" -> s"HTTP ${resp.status}: ${resp.body.take(500)}").left
        }
      }.recover { case e: Throwable =>
        logger.error(s"Error fetching from $url", e)
        Json.obj("error" -> s"Error fetching from HTTP: ${e.getMessage}").left
      }
    }
  }
}

class CatalogSourceGithub extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-github")

  override def sourceKind: String      = "github"
  override def supportsWebhook: Boolean = true

  private def parseRepo(repoUrl: String): Option[(String, String)] = {
    val cleaned = repoUrl.stripSuffix(".git")
    val parts   = cleaned.split("/")
    if (parts.length >= 2) {
      Some((parts(parts.length - 2), parts(parts.length - 1)))
    } else {
      None
    }
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val repoFullName = payload.select("repository").select("full_name").asOpt[String].getOrElse("")
    val ref          = payload.select("ref").asOpt[String].getOrElse("")
    val branch       = ref.replace("refs/heads/", "")
    val matched = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == "github" && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        parseRepo(configRepo).exists { case (owner, repo) =>
          s"$owner/$repo" == repoFullName && configBranch == branch
        }
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch  = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path    = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/entities.json").stripPrefix("/")
    val token   = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")

    parseRepo(repoUrl) match {
      case None                =>
        Json.obj("error" -> s"Cannot parse GitHub repo from: $repoUrl").leftf
      case Some((owner, repo)) =>
        val apiUrl  = s"https://api.github.com/repos/$owner/$repo/contents/$path"
        val headers = Seq(
          "Accept"        -> "application/vnd.github.v3.raw",
          "User-Agent"    -> "Otoroshi-Remote-Catalogs"
        ) ++ (if (token.nonEmpty) Seq("Authorization" -> s"token $token") else Seq.empty)

        env.Ws
          .url(apiUrl)
          .withQueryStringParameters("ref" -> branch)
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
          .get()
          .map { resp =>
            if (resp.status == 200) {
              val json   = resp.json
              val allRes = env.allResources.resources ++ env.adminExtensions.resources()
              RemoteContentParser.parse(json, s"github://$owner/$repo/$path@$branch", allRes).right
            } else {
              Json.obj("error" -> s"GitHub API returned ${resp.status}: ${resp.body.take(500)}").left
            }
          }
          .recover { case e: Throwable =>
            logger.error(s"Error fetching from GitHub $owner/$repo", e)
            Json.obj("error" -> s"Error fetching from GitHub: ${e.getMessage}").left
          }
    }
  }
}

class CatalogSourceGitlab extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-gitlab")

  override def sourceKind: String      = "gitlab"
  override def supportsWebhook: Boolean = true

  private def parseProjectPath(repoUrl: String): Option[String] = {
    Try {
      val url     = new java.net.URL(repoUrl.stripSuffix(".git"))
      val path    = url.getPath.stripPrefix("/").stripSuffix("/")
      if (path.nonEmpty) Some(path) else None
    }.getOrElse(None)
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val projectWebUrl = payload.select("project").select("web_url").asOpt[String].getOrElse("")
    val ref           = payload.select("ref").asOpt[String].getOrElse("")
    val branch        = ref.replace("refs/heads/", "")
    val matched = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == "gitlab" && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        configRepo.stripSuffix(".git") == projectWebUrl.stripSuffix(".git") && configBranch == branch
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch  = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path    = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/entities.json").stripPrefix("/")
    val token   = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val baseUrl = catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://gitlab.com")

    parseProjectPath(repoUrl) match {
      case None              =>
        Json.obj("error" -> s"Cannot parse GitLab project path from: $repoUrl").leftf
      case Some(projectPath) =>
        val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
        val encodedFile = java.net.URLEncoder.encode(path, "UTF-8")
        val apiUrl      = s"$baseUrl/api/v4/projects/$encodedPath/repository/files/$encodedFile/raw"
        val headers     = Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++
          (if (token.nonEmpty) Seq("PRIVATE-TOKEN" -> token) else Seq.empty)

        env.Ws
          .url(apiUrl)
          .withQueryStringParameters("ref" -> branch)
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
          .get()
          .map { resp =>
            if (resp.status == 200) {
              val json   = resp.json
              val allRes = env.allResources.resources ++ env.adminExtensions.resources()
              RemoteContentParser.parse(json, s"gitlab://$projectPath/$path@$branch", allRes).right
            } else {
              Json.obj("error" -> s"GitLab API returned ${resp.status}: ${resp.body.take(500)}").left
            }
          }
          .recover { case e: Throwable =>
            logger.error(s"Error fetching from GitLab $projectPath", e)
            Json.obj("error" -> s"Error fetching from GitLab: ${e.getMessage}").left
          }
    }
  }
}

class CatalogSourceS3 extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-s3")

  override def sourceKind: String      = "s3"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "s3 source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] =
    Json.obj("error" -> "s3 source does not support webhooks").leftf

  private def s3ClientSettingsAttrs(config: JsObject): Attributes = {
    val access   = config.select("access").asOpt[String].getOrElse("")
    val secret   = config.select("secret").asOpt[String].getOrElse("")
    val region   = config.select("region").asOpt[String].getOrElse("eu-west-1")
    val endpoint = config.select("endpoint").asOpt[String].getOrElse("https://s3.amazonaws.com")
    val v4auth   = config.select("v4auth").asOpt[Boolean].getOrElse(true)

    val awsCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))
    val settings       = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(endpoint)
    S3Attributes.settings(settings)
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    implicit val mat: Materializer = env.otoroshiMaterializer

    val bucket = catalog.sourceConfig.select("bucket").asOpt[String].getOrElse("")
    val key    = catalog.sourceConfig.select("key").asOpt[String].getOrElse("").stripPrefix("/")

    if (bucket.isEmpty || key.isEmpty) {
      Json.obj("error" -> "S3 bucket and key are required").leftf
    } else {
      S3.download(bucket, key)
        .withAttributes(s3ClientSettingsAttrs(catalog.sourceConfig))
        .runWith(Sink.head)
        .flatMap {
          case None                 =>
            Json.obj("error" -> s"S3 object not found: $bucket/$key").leftf
          case Some((source, _))    =>
            source.runFold(ByteString.empty)(_ ++ _).map { bs =>
              val json   = Json.parse(bs.utf8String)
              val allRes = env.allResources.resources ++ env.adminExtensions.resources()
              RemoteContentParser.parse(json, s"s3://$bucket/$key", allRes).right
            }
        }
        .recover { case e: Throwable =>
          logger.error(s"Error fetching from S3 $bucket/$key", e)
          Json.obj("error" -> s"Error fetching from S3: ${e.getMessage}").left
        }
    }
  }
}
