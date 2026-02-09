package otoroshi.next.catalogs

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3._
import akka.stream.scaladsl.Sink
import akka.stream.{Attributes, Materializer}
import akka.util.ByteString
import otoroshi.api.Resource
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

object SourceUtils {

  private val logger = Logger("otoroshi-remote-catalog-source-utils")

  def parseEntityFile(content: JsValue, sourceName: String, allResources: Seq[Resource]): Seq[RemoteEntity] = {
    RemoteContentParser.parse(content, sourceName, allResources)
  }

  def resolveDeployJson(
      deployContent: JsValue,
      fetchRelativePath: String => Future[Either[JsValue, JsValue]],
      sourceName: String,
      allResources: Seq[Resource]
  )(implicit ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    deployContent match {
      case arr: JsArray =>
        val paths = arr.value.flatMap(_.asOpt[String])
        paths
          .mapAsync { relativePath =>
            fetchRelativePath(relativePath).map {
              case Left(err)      =>
                logger.warn(s"Error fetching $relativePath from $sourceName: ${err.toString}")
                Seq.empty[RemoteEntity]
              case Right(content) =>
                parseEntityFile(content, s"$sourceName/$relativePath", allResources)
            }
          }
          .map(entities => Right(entities.flatten): Either[JsValue, Seq[RemoteEntity]])
      case _            =>
        (Left(Json.obj("error" -> "deploy.json must contain a JSON array of paths")): Either[JsValue, Seq[RemoteEntity]]).vfuture
    }
  }

  def isDeployJson(path: String): Boolean = {
    path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".yml")
  }
}

class CatalogSourceFile extends CatalogSource {

  import scala.sys.process._

  private val logger = Logger("otoroshi-remote-catalog-source-file")

  override def sourceKind: String       = "file"
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

  private def runPreCommand(catalog: RemoteCatalog): Either[String, Unit] = {
    val preCommand = catalog.sourceConfig.select("pre_command").asOpt[Seq[String]].getOrElse(Seq.empty)
    if (preCommand.nonEmpty) {
      Try {
        var stdout = ""
        var stderr = ""
        val processLogger = ProcessLogger(
          out => { stdout = stdout + out + "\n" },
          err => { stderr = stderr + err + "\n" }
        )
        val code = preCommand.!(processLogger)
        if (code != 0) {
          Left(s"Pre-command failed with exit code $code. stderr: $stderr")
        } else {
          Right(())
        }
      }.getOrElse(Left("Pre-command execution failed"))
    } else {
      Right(())
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val path = catalog.sourceConfig.select("path").asOpt[String].getOrElse("")

    runPreCommand(catalog) match {
      case Left(err) =>
        (Left(Json.obj("error" -> err)): Either[JsValue, Seq[RemoteEntity]]).vfuture
      case Right(()) =>
        Try {
          val file      = new File(path)
          val allRes    = env.allResources.resources ++ env.adminExtensions.resources()
          if (file.isDirectory) {
            val entityFiles = file.listFiles().filter(f => f.isFile && f.getName.endsWith(".json")).toSeq
            val entities = entityFiles.flatMap { f =>
              val content = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
              val json    = Json.parse(content)
              SourceUtils.parseEntityFile(json, s"file://${f.getAbsolutePath}", allRes)
            }
            (Right(entities): Either[JsValue, Seq[RemoteEntity]]).vfuture
          } else {
            val content = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
            val json    = Json.parse(content)
            json match {
              case arr: JsArray =>
                val hasOnlyStrings = arr.value.forall(_.isInstanceOf[JsString])
                if (hasOnlyStrings) {
                  val basePath = file.getParentFile.getAbsolutePath
                  SourceUtils.resolveDeployJson(
                    json,
                    relativePath => {
                      Try {
                        val relFile    = new File(basePath, relativePath)
                        val relContent = new String(Files.readAllBytes(relFile.toPath), StandardCharsets.UTF_8)
                        val relJson    = Json.parse(relContent)
                        (Right(relJson): Either[JsValue, JsValue]).vfuture
                      }.getOrElse {
                        (Left(Json.obj("error" -> s"Cannot read file $relativePath")): Either[JsValue, JsValue]).vfuture
                      }
                    },
                    s"file://$path",
                    allRes
                  )
                } else {
                  (Right(SourceUtils.parseEntityFile(json, s"file://$path", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
                }
              case _ =>
                (Right(SourceUtils.parseEntityFile(json, s"file://$path", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
            }
          }
        }.recover { case e: Throwable =>
          logger.error(s"Error reading file $path", e)
          (Left(Json.obj("error" -> s"Error reading file: ${e.getMessage}")): Either[JsValue, Seq[RemoteEntity]]).vfuture
        }.get
    }
  }
}

class CatalogSourceHttp extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-http")

  override def sourceKind: String       = "http"
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
    val timeout = catalog.sourceConfig.select("timeout").asOpt[Long].getOrElse(30000L)

    if (url.isEmpty) {
      Json.obj("error" -> "No URL configured").leftf
    } else {
      fetchUrl(url, headers, timeout, env).flatMap {
        case Left(err)   => err.leftf
        case Right(json) =>
          val allRes = env.allResources.resources ++ env.adminExtensions.resources()
          json match {
            case arr: JsArray =>
              val hasOnlyStrings = arr.value.forall(_.isInstanceOf[JsString])
              if (hasOnlyStrings) {
                val baseUrl = url.substring(0, url.lastIndexOf('/'))
                SourceUtils.resolveDeployJson(
                  json,
                  relativePath => fetchUrl(s"$baseUrl/$relativePath", headers, timeout, env),
                  s"http://$url",
                  allRes
                )
              } else {
                (Right(SourceUtils.parseEntityFile(json, s"http://$url", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
              }
            case _ =>
              (Right(SourceUtils.parseEntityFile(json, s"http://$url", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
          }
      }
    }
  }

  private def fetchUrl(url: String, headers: Map[String, String], timeout: Long, env: Env)(implicit
      ec: ExecutionContext
  ): Future[Either[JsValue, JsValue]] = {
    env.Ws
      .url(url)
      .withRequestTimeout(Duration(timeout, TimeUnit.MILLISECONDS))
      .withHttpHeaders(headers.toSeq: _*)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.json): Either[JsValue, JsValue]
        } else {
          Left(Json.obj("error" -> s"HTTP ${resp.status}: ${resp.body.take(500)}")): Either[JsValue, JsValue]
        }
      }
      .recover { case e: Throwable =>
        logger.error(s"Error fetching from $url", e)
        Left(Json.obj("error" -> s"Error fetching from HTTP: ${e.getMessage}")): Either[JsValue, JsValue]
      }
  }
}

class CatalogSourceGithub extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-github")

  override def sourceKind: String       = "github"
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

  private def githubHeaders(token: String): Seq[(String, String)] = {
    Seq(
      "Accept"     -> "application/vnd.github.v3+json",
      "User-Agent" -> "Otoroshi-Remote-Catalogs"
    ) ++ (if (token.nonEmpty) Seq("Authorization" -> s"token $token") else Seq.empty)
  }

  private def githubRawHeaders(token: String): Seq[(String, String)] = {
    Seq(
      "Accept"     -> "application/vnd.github.v3.raw",
      "User-Agent" -> "Otoroshi-Remote-Catalogs"
    ) ++ (if (token.nonEmpty) Seq("Authorization" -> s"token $token") else Seq.empty)
  }

  private def fetchFileContent(owner: String, repo: String, filePath: String, branch: String, token: String, env: Env)(
      implicit ec: ExecutionContext
  ): Future[Either[JsValue, JsValue]] = {
    val apiUrl = s"https://api.github.com/repos/$owner/$repo/contents/$filePath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(githubRawHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.json): Either[JsValue, JsValue]
        } else {
          Left(Json.obj("error" -> s"GitHub API returned ${resp.status} for $filePath")): Either[JsValue, JsValue]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitHub: ${e.getMessage}")): Either[JsValue, JsValue]
      }
  }

  private def listDirectory(owner: String, repo: String, dirPath: String, branch: String, token: String, env: Env)(
      implicit ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"https://api.github.com/repos/$owner/$repo/contents/$dirPath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(githubHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              val files = arr.value.flatMap { item =>
                val itemType = item.select("type").asOpt[String].getOrElse("")
                val itemName = item.select("name").asOpt[String].getOrElse("")
                val itemPath = item.select("path").asOpt[String].getOrElse("")
                if (itemType == "file" && itemName.endsWith(".json")) Some(itemPath) else None
              }
              Right(files): Either[JsValue, Seq[String]]
            case _ =>
              Left(Json.obj("error" -> "GitHub API did not return an array for directory listing")): Either[JsValue, Seq[String]]
          }
        } else {
          Left(Json.obj("error" -> s"GitHub API returned ${resp.status} for directory listing")): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitHub directory: ${e.getMessage}")): Either[JsValue, Seq[String]]
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
    val path    = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token   = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")

    parseRepo(repoUrl) match {
      case None                =>
        Json.obj("error" -> s"Cannot parse GitHub repo from: $repoUrl").leftf
      case Some((owner, repo)) =>
        val allRes = env.allResources.resources ++ env.adminExtensions.resources()
        if (SourceUtils.isDeployJson(path)) {
          fetchFileContent(owner, repo, path, branch, token, env).flatMap {
            case Left(err)   => err.leftf
            case Right(json) =>
              val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployJson(
                json,
                relativePath => {
                  val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                  fetchFileContent(owner, repo, fullPath, branch, token, env)
                },
                s"github://$owner/$repo/$path@$branch",
                allRes
              )
          }
        } else {
          listDirectory(owner, repo, path, branch, token, env).flatMap {
            case Left(err)    => err.leftf
            case Right(files) =>
              files
                .mapAsync { filePath =>
                  fetchFileContent(owner, repo, filePath, branch, token, env).map {
                    case Left(err)      =>
                      logger.warn(s"Error fetching $filePath: ${err.toString}")
                      Seq.empty[RemoteEntity]
                    case Right(content) =>
                      SourceUtils.parseEntityFile(content, s"github://$owner/$repo/$filePath@$branch", allRes)
                  }
                }
                .map(entities => Right(entities.flatten): Either[JsValue, Seq[RemoteEntity]])
          }
        }
    }
  }
}

class CatalogSourceGitlab extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-gitlab")

  override def sourceKind: String       = "gitlab"
  override def supportsWebhook: Boolean = true

  private def parseProjectPath(repoUrl: String): Option[String] = {
    Try {
      val url  = new java.net.URL(repoUrl.stripSuffix(".git"))
      val path = url.getPath.stripPrefix("/").stripSuffix("/")
      if (path.nonEmpty) Some(path) else None
    }.getOrElse(None)
  }

  private def gitlabHeaders(token: String): Seq[(String, String)] = {
    Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++
      (if (token.nonEmpty) Seq("PRIVATE-TOKEN" -> token) else Seq.empty)
  }

  private def fetchFileContent(
      baseUrl: String,
      encodedProject: String,
      filePath: String,
      branch: String,
      token: String,
      env: Env
  )(implicit ec: ExecutionContext): Future[Either[JsValue, JsValue]] = {
    val encodedFile = java.net.URLEncoder.encode(filePath, "UTF-8")
    val apiUrl      = s"$baseUrl/api/v4/projects/$encodedProject/repository/files/$encodedFile/raw"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(gitlabHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.json): Either[JsValue, JsValue]
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for $filePath")): Either[JsValue, JsValue]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitLab: ${e.getMessage}")): Either[JsValue, JsValue]
      }
  }

  private def listDirectory(
      baseUrl: String,
      encodedProject: String,
      dirPath: String,
      branch: String,
      token: String,
      env: Env
  )(implicit ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val encodedPath = java.net.URLEncoder.encode(dirPath, "UTF-8")
    val apiUrl      = s"$baseUrl/api/v4/projects/$encodedProject/repository/tree"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch, "path" -> dirPath, "per_page" -> "100")
      .withHttpHeaders(gitlabHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              val files = arr.value.flatMap { item =>
                val itemType = item.select("type").asOpt[String].getOrElse("")
                val itemName = item.select("name").asOpt[String].getOrElse("")
                val itemPath = item.select("path").asOpt[String].getOrElse("")
                if (itemType == "blob" && itemName.endsWith(".json")) Some(itemPath) else None
              }
              Right(files): Either[JsValue, Seq[String]]
            case _ =>
              Left(Json.obj("error" -> "GitLab API did not return an array for tree listing")): Either[JsValue, Seq[String]]
          }
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for tree listing")): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitLab tree: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
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
    val path    = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token   = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val baseUrl = catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://gitlab.com")

    parseProjectPath(repoUrl) match {
      case None              =>
        Json.obj("error" -> s"Cannot parse GitLab project path from: $repoUrl").leftf
      case Some(projectPath) =>
        val encodedProject = java.net.URLEncoder.encode(projectPath, "UTF-8")
        val allRes         = env.allResources.resources ++ env.adminExtensions.resources()
        if (SourceUtils.isDeployJson(path)) {
          fetchFileContent(baseUrl, encodedProject, path, branch, token, env).flatMap {
            case Left(err)   => err.leftf
            case Right(json) =>
              val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployJson(
                json,
                relativePath => {
                  val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                  fetchFileContent(baseUrl, encodedProject, fullPath, branch, token, env)
                },
                s"gitlab://$projectPath/$path@$branch",
                allRes
              )
          }
        } else {
          listDirectory(baseUrl, encodedProject, path, branch, token, env).flatMap {
            case Left(err)    => err.leftf
            case Right(files) =>
              files
                .mapAsync { filePath =>
                  fetchFileContent(baseUrl, encodedProject, filePath, branch, token, env).map {
                    case Left(err)      =>
                      logger.warn(s"Error fetching $filePath: ${err.toString}")
                      Seq.empty[RemoteEntity]
                    case Right(content) =>
                      SourceUtils.parseEntityFile(content, s"gitlab://$projectPath/$filePath@$branch", allRes)
                  }
                }
                .map(entities => Right(entities.flatten): Either[JsValue, Seq[RemoteEntity]])
          }
        }
    }
  }
}

class CatalogSourceS3 extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-s3")

  override def sourceKind: String       = "s3"
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

  private def fetchS3Object(bucket: String, key: String, config: JsObject, env: Env)(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[JsValue, JsValue]] = {
    S3.download(bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.head)
      .flatMap {
        case None              =>
          (Left(Json.obj("error" -> s"S3 object not found: $bucket/$key")): Either[JsValue, JsValue]).vfuture
        case Some((source, _)) =>
          source.runFold(ByteString.empty)(_ ++ _).map { bs =>
            Right(Json.parse(bs.utf8String)): Either[JsValue, JsValue]
          }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching S3 object $bucket/$key: ${e.getMessage}")): Either[JsValue, JsValue]
      }
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
      val allRes = env.allResources.resources ++ env.adminExtensions.resources()
      fetchS3Object(bucket, key, catalog.sourceConfig, env).flatMap {
        case Left(err)   => err.leftf
        case Right(json) =>
          json match {
            case arr: JsArray =>
              val hasOnlyStrings = arr.value.forall(_.isInstanceOf[JsString])
              if (hasOnlyStrings) {
                val baseKey = if (key.contains("/")) key.substring(0, key.lastIndexOf('/')) else ""
                SourceUtils.resolveDeployJson(
                  json,
                  relativePath => {
                    val fullKey = if (baseKey.nonEmpty) s"$baseKey/$relativePath" else relativePath
                    fetchS3Object(bucket, fullKey, catalog.sourceConfig, env)
                  },
                  s"s3://$bucket/$key",
                  allRes
                )
              } else {
                (Right(SourceUtils.parseEntityFile(json, s"s3://$bucket/$key", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
              }
            case _ =>
              (Right(SourceUtils.parseEntityFile(json, s"s3://$bucket/$key", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
          }
      }
    }
  }
}
