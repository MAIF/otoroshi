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

  def parseEntityContent(rawContent: String, sourceName: String, allResources: Seq[Resource]): Seq[RemoteEntity] = {
    RemoteContentParser.parseRawContent(rawContent, sourceName, allResources)
  }

  def isDeployListing(rawContent: String): Option[JsArray] = {
    Try(Json.parse(rawContent)).toOption.flatMap {
      case arr: JsArray if arr.value.nonEmpty && arr.value.forall(_.isInstanceOf[JsString]) => Some(arr)
      case _ => None
    }
  }

  def resolveDeployListing(
      deployArray: JsArray,
      fetchRelativePath: String => Future[Either[JsValue, String]],
      sourceName: String,
      allResources: Seq[Resource]
  )(implicit ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val paths = deployArray.value.flatMap(_.asOpt[String])
    paths
      .mapAsync { relativePath =>
        fetchRelativePath(relativePath).map {
          case Left(err)         =>
            logger.warn(s"Error fetching $relativePath from $sourceName: ${err.toString}")
            Seq.empty[RemoteEntity]
          case Right(rawContent) =>
            parseEntityContent(rawContent, s"$sourceName/$relativePath", allResources)
        }
      }
      .map(entities => Right(entities.flatten): Either[JsValue, Seq[RemoteEntity]])
  }

  def isEntityFile(name: String): Boolean = {
    name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")
  }

  def hasFileExtension(path: String): Boolean = {
    val lastPart = path.split("/").lastOption.getOrElse("")
    lastPart.contains(".")
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
            val entityFiles = file.listFiles().filter(f => f.isFile && SourceUtils.isEntityFile(f.getName)).toSeq
            val entities = entityFiles.flatMap { f =>
              val rawContent = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
              SourceUtils.parseEntityContent(rawContent, s"file://${f.getAbsolutePath}", allRes)
            }
            (Right(entities): Either[JsValue, Seq[RemoteEntity]]).vfuture
          } else {
            val rawContent = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
            SourceUtils.isDeployListing(rawContent) match {
              case Some(arr) =>
                val basePath = file.getParentFile.getAbsolutePath
                SourceUtils.resolveDeployListing(
                  arr,
                  relativePath => {
                    Try {
                      val relFile    = new File(basePath, relativePath)
                      val relContent = new String(Files.readAllBytes(relFile.toPath), StandardCharsets.UTF_8)
                      (Right(relContent): Either[JsValue, String]).vfuture
                    }.getOrElse {
                      (Left(Json.obj("error" -> s"Cannot read file $relativePath")): Either[JsValue, String]).vfuture
                    }
                  },
                  s"file://$path",
                  allRes
                )
              case None =>
                (Right(SourceUtils.parseEntityContent(rawContent, s"file://$path", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
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
        case Left(err)         => err.leftf
        case Right(rawContent) =>
          val allRes = env.allResources.resources ++ env.adminExtensions.resources()
          SourceUtils.isDeployListing(rawContent) match {
            case Some(arr) =>
              val baseUrl = url.substring(0, url.lastIndexOf('/'))
              SourceUtils.resolveDeployListing(
                arr,
                relativePath => fetchUrl(s"$baseUrl/$relativePath", headers, timeout, env),
                s"http://$url",
                allRes
              )
            case None =>
              (Right(SourceUtils.parseEntityContent(rawContent, s"http://$url", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
          }
      }
    }
  }

  private def fetchUrl(url: String, headers: Map[String, String], timeout: Long, env: Env)(implicit
      ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    env.Ws
      .url(url)
      .withRequestTimeout(Duration(timeout, TimeUnit.MILLISECONDS))
      .withHttpHeaders(headers.toSeq: _*)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"HTTP ${resp.status}: ${resp.body.take(500)}")): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        logger.error(s"Error fetching from $url", e)
        Left(Json.obj("error" -> s"Error fetching from HTTP: ${e.getMessage}")): Either[JsValue, String]
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

  private def fetchFileContent(apiBase: String, owner: String, repo: String, filePath: String, branch: String, token: String, env: Env)(
      implicit ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    val apiUrl = s"$apiBase/repos/$owner/$repo/contents/$filePath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(githubRawHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"GitHub API returned ${resp.status} for $filePath")): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitHub: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  private def listDirectory(apiBase: String, owner: String, repo: String, dirPath: String, branch: String, token: String, env: Env)(
      implicit ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$apiBase/repos/$owner/$repo/contents/$dirPath"
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
                if (itemType == "file" && SourceUtils.isEntityFile(itemName)) Some(itemPath) else None
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
    val apiBase = catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://api.github.com").stripSuffix("/")

    parseRepo(repoUrl) match {
      case None                =>
        Json.obj("error" -> s"Cannot parse GitHub repo from: $repoUrl").leftf
      case Some((owner, repo)) =>
        val allRes = env.allResources.resources ++ env.adminExtensions.resources()
        if (SourceUtils.hasFileExtension(path)) {
          fetchFileContent(apiBase, owner, repo, path, branch, token, env).flatMap {
            case Left(err)         => err.leftf
            case Right(rawContent) =>
              SourceUtils.isDeployListing(rawContent) match {
                case Some(arr) =>
                  val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
                  SourceUtils.resolveDeployListing(
                    arr,
                    relativePath => {
                      val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                      fetchFileContent(apiBase, owner, repo, fullPath, branch, token, env)
                    },
                    s"github://$owner/$repo/$path@$branch",
                    allRes
                  )
                case None =>
                  (Right(SourceUtils.parseEntityContent(rawContent, s"github://$owner/$repo/$path@$branch", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
              }
          }
        } else {
          listDirectory(apiBase, owner, repo, path, branch, token, env).flatMap {
            case Left(err)    => err.leftf
            case Right(files) =>
              files
                .mapAsync { filePath =>
                  fetchFileContent(apiBase, owner, repo, filePath, branch, token, env).map {
                    case Left(err)         =>
                      logger.warn(s"Error fetching $filePath: ${err.toString}")
                      Seq.empty[RemoteEntity]
                    case Right(rawContent) =>
                      SourceUtils.parseEntityContent(rawContent, s"github://$owner/$repo/$filePath@$branch", allRes)
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
    //Try {
    //  val url  = new java.net.URL(repoUrl.stripSuffix(".git"))
    //  val path = url.getPath.stripPrefix("/").stripSuffix("/")
    //  if (path.nonEmpty) Some(path) else None
    //}.getOrElse(None)
    repoUrl.some
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
  )(implicit ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val apiUrl      = s"$baseUrl/api/v4/projects/$encodedProject/repository/files/$filePath/raw"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(gitlabHeaders(token): _*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for $filePath")): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitLab: ${e.getMessage}")): Either[JsValue, String]
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
    val apiUrl = s"$baseUrl/api/v4/projects/$encodedProject/repository/tree"
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
                if (itemType == "blob" && SourceUtils.isEntityFile(itemName)) Some(itemPath) else None
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
        if (SourceUtils.hasFileExtension(path)) {
          fetchFileContent(baseUrl, encodedProject, path, branch, token, env).flatMap {
            case Left(err)         => err.leftf
            case Right(rawContent) =>
              SourceUtils.isDeployListing(rawContent) match {
                case Some(arr) =>
                  val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
                  SourceUtils.resolveDeployListing(
                    arr,
                    relativePath => {
                      val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                      fetchFileContent(baseUrl, encodedProject, fullPath, branch, token, env)
                    },
                    s"gitlab://$projectPath/$path@$branch",
                    allRes
                  )
                case None =>
                  (Right(SourceUtils.parseEntityContent(rawContent, s"gitlab://$projectPath/$path@$branch", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
              }
          }
        } else {
          listDirectory(baseUrl, encodedProject, path, branch, token, env).flatMap {
            case Left(err)    => err.leftf
            case Right(files) =>
              files
                .mapAsync { filePath =>
                  fetchFileContent(baseUrl, encodedProject, filePath, branch, token, env).map {
                    case Left(err)         =>
                      logger.warn(s"Error fetching $filePath: ${err.toString}")
                      Seq.empty[RemoteEntity]
                    case Right(rawContent) =>
                      SourceUtils.parseEntityContent(rawContent, s"gitlab://$projectPath/$filePath@$branch", allRes)
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
  ): Future[Either[JsValue, String]] = {
    S3.download(bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.head)
      .flatMap {
        case None              =>
          (Left(Json.obj("error" -> s"S3 object not found: $bucket/$key")): Either[JsValue, String]).vfuture
        case Some((source, _)) =>
          source.runFold(ByteString.empty)(_ ++ _).map { bs =>
            Right(bs.utf8String): Either[JsValue, String]
          }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching S3 object $bucket/$key: ${e.getMessage}")): Either[JsValue, String]
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
        case Left(err)         => err.leftf
        case Right(rawContent) =>
          SourceUtils.isDeployListing(rawContent) match {
            case Some(arr) =>
              val baseKey = if (key.contains("/")) key.substring(0, key.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployListing(
                arr,
                relativePath => {
                  val fullKey = if (baseKey.nonEmpty) s"$baseKey/$relativePath" else relativePath
                  fetchS3Object(bucket, fullKey, catalog.sourceConfig, env)
                },
                s"s3://$bucket/$key",
                allRes
              )
            case None =>
              (Right(SourceUtils.parseEntityContent(rawContent, s"s3://$bucket/$key", allRes)): Either[JsValue, Seq[RemoteEntity]]).vfuture
          }
      }
    }
  }
}
