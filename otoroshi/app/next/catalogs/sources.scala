package otoroshi.next.catalogs

import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import play.api.libs.ws.WSBodyReadables.given
import org.apache.pekko.stream.connectors.s3.*
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.{Attributes, Materializer}
import org.apache.pekko.util.ByteString
import otoroshi.api.Resource
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import otoroshi.utils.yaml.Yaml
import play.api.Logger
import play.api.libs.json.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

object SourceUtils {

  private val logger = Logger("otoroshi-remote-catalog-source-utils")

  def parseEntityContent(
      rawContent: String,
      sourceName: String,
      allResources: Seq[Resource]
  ): Either[JsValue, Seq[RemoteEntity]] = {
    RemoteContentParser.parseRawContent(rawContent, sourceName, allResources)
  }

  // a catalog is fetched entirely or not at all: a missing part would look like deleted entities to the deploy
  def sequence(results: Seq[Either[JsValue, Seq[RemoteEntity]]]): Either[JsValue, Seq[RemoteEntity]] = {
    val (errors, entities) = results.partitionMap(identity)
    if (errors.nonEmpty) Left(errors.head) else Right(entities.flatten)
  }

  // when scanning an organization, a repository without the catalog path is skipped
  def isNotFound(error: JsValue): Boolean = error.select("status").asOpt[Int].contains(404)

  private def isStringArray(value: JsValue): Option[JsArray] = value match {
    case arr: JsArray if arr.value.nonEmpty && arr.value.toSeq.forall(_.isInstanceOf[JsString]) => Some(arr)
    case _                                                                                => None
  }

  private def extractDeployListing(json: JsValue): Option[JsArray] = {
    isStringArray(json).orElse {
      json match {
        case obj: JsObject =>
          val hasApiVersion = obj.select("apiVersion").asOpt[String].contains("proxy.otoroshi.io/v1")
          val hasKind       = obj.select("kind").asOpt[String].contains("RemoteCatalogListing")
          if (hasApiVersion && hasKind) {
            obj.select("spec").select("catalog_listing").asOpt[JsArray].flatMap(isStringArray)
          } else {
            None
          }
        case _             => None
      }
    }
  }

  def isDeployListing(rawContent: String): Option[JsArray] = {
    Try(Json.parse(rawContent)).toOption.flatMap(extractDeployListing).orElse {
      Yaml.parse(rawContent).flatMap(extractDeployListing)
    }
  }

  def resolveDeployListing(
      deployArray: JsArray,
      fetchRelativePath: String => Future[Either[JsValue, String]],
      sourceName: String,
      allResources: Seq[Resource],
      resolveGlob: Option[String => Future[Either[JsValue, Seq[String]]]] = None
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val rawPaths = deployArray.value.flatMap(_.asOpt[String]).toSeq
    rawPaths
      .mapAsync { path =>
        if (isGlobPattern(path) && resolveGlob.isDefined) {
          resolveGlob.get(path).flatMap {
            case Left(err)            => err.leftf
            case Right(resolvedPaths) =>
              resolvedPaths
                .mapAsync { relativePath =>
                  fetchRelativePath(relativePath).map(
                    _.flatMap(rawContent => parseEntityContent(rawContent, s"$sourceName/$relativePath", allResources))
                  )
                }
                .map(sequence)
          }
        } else {
          fetchRelativePath(path).map(
            _.flatMap(rawContent => parseEntityContent(rawContent, s"$sourceName/$path", allResources))
          )
        }
      }
      .map(sequence)
  }

  def isGlobPattern(path: String): Boolean = {
    path.contains("*") || path.contains("?") || (path.contains("[") && path.contains("]"))
  }

  def globToRegex(glob: String): String = {
    val clean  = glob.stripPrefix("./")
    val result = new StringBuilder("^")
    var i      = 0
    while (i < clean.length) {
      if (i < clean.length - 1 && clean(i) == '*' && clean(i + 1) == '*') {
        result.append(".*")
        i += 2
        if (i < clean.length && clean(i) == '/') i += 1
      } else {
        clean(i) match {
          case '*' => result.append("[^/]*")
          case '?' => result.append("[^/]")
          case '.' => result.append("\\.")
          case c   => result.append(java.util.regex.Pattern.quote(c.toString))
        }
        i += 1
      }
    }
    result.append("$").toString()
  }

  def matchesGlob(path: String, pattern: String): Boolean = {
    path.matches(globToRegex(pattern))
  }

  def resolveLocalGlob(baseDir: File, globPattern: String): Seq[String] = {
    val clean   = globPattern.stripPrefix("./")
    val matcher = FileSystems.getDefault.getPathMatcher("glob:" + clean)
    Try {
      Files
        .walk(baseDir.toPath)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p))
        .map(p => baseDir.toPath.relativize(p))
        .filter(p => matcher.matches(p) && isEntityFile(p.getFileName.toString))
        .map(_.toString)
        .toSeq
    }.getOrElse(Seq.empty).toSeq
  }

  def resolveRemoteGlob(allFiles: Seq[String], basePath: String, globPattern: String): Seq[String] = {
    allFiles.flatMap { file =>
      val relativeOpt =
        if (basePath.isEmpty) Some(file)
        else if (file.startsWith(basePath + "/")) Some(file.stripPrefix(basePath + "/"))
        else None
      relativeOpt.filter(r => matchesGlob(r, globPattern))
    }
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

  import scala.sys.process.*

  private val logger = Logger("otoroshi-remote-catalog-source-file")

  override def sourceKind: String       = "file"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "file source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]           =
    Json.obj("error" -> "file source does not support webhooks").leftf

  private def runPreCommand(catalog: RemoteCatalog): Either[String, Unit] = {
    val preCommand = catalog.sourceConfig.select("pre_command").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    if (preCommand.nonEmpty) {
      Try {
        var stdout        = ""
        var stderr        = ""
        val processLogger = ProcessLogger(
          out => { stdout = stdout + out + "\n" },
          err => { stderr = stderr + err + "\n" }
        )
        val code          = preCommand.!(processLogger)
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

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val path = catalog.sourceConfig.select("path").asOpt[String].getOrElse("")

    runPreCommand(catalog) match {
      case Left(err) =>
        (Left(Json.obj("error" -> err)): Either[JsValue, Seq[RemoteEntity]]).vfuture
      case Right(()) =>
        Try {
          val file   = new File(path)
          val allRes = env.allResources.resources ++ env.adminExtensions.resources()
          if (file.isDirectory) {
            val entityFiles = file.listFiles().filter(f => f.isFile && SourceUtils.isEntityFile(f.getName)).toSeq
            SourceUtils
              .sequence(entityFiles.map { f =>
                val rawContent = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
                SourceUtils.parseEntityContent(rawContent, s"file://${f.getAbsolutePath}", allRes)
              })
              .vfuture
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
                  allRes,
                  resolveGlob = Some(glob =>
                    (Right(SourceUtils.resolveLocalGlob(new File(basePath), glob)): Either[
                      JsValue,
                      Seq[String]
                    ]).vfuture
                  )
                )
              case None      =>
                SourceUtils.parseEntityContent(rawContent, s"file://$path", allRes).vfuture
            }
          }
        }.recover { case e: Throwable =>
          logger.error(s"Error reading file $path", e)
          (Left(Json.obj("error" -> s"Error reading file: ${e.getMessage}")): Either[
            JsValue,
            Seq[RemoteEntity]
          ]).vfuture
        }.get
    }
  }
}

class CatalogSourceHttp extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-http")

  override def sourceKind: String       = "http"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "http source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]           =
    Json.obj("error" -> "http source does not support webhooks").leftf

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
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
            case None      =>
              SourceUtils.parseEntityContent(rawContent, s"http://$url", allRes).vfuture
          }
      }
    }
  }

  private def fetchUrl(url: String, headers: Map[String, String], timeout: Long, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    env.Ws
      .url(url)
      .withRequestTimeout(Duration(timeout, TimeUnit.MILLISECONDS))
      .withHttpHeaders(headers.toSeq*)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"HTTP ${resp.status}: ${resp.body[String].take(500)}")): Either[JsValue, String]
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

  private def fetchFileContent(
      apiBase: String,
      owner: String,
      repo: String,
      filePath: String,
      branch: String,
      token: String,
      env: Env
  )(using
      ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    val apiUrl = s"$apiBase/repos/$owner/$repo/contents/$filePath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(githubRawHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"GitHub API returned ${resp.status} for $filePath", "status" -> resp.status)): Either[
            JsValue,
            String
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitHub: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  private def listAllFilesRecursive(
      apiBase: String,
      owner: String,
      repo: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$apiBase/repos/$owner/$repo/git/trees/$branch"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("recursive" -> "1")
      .withHttpHeaders(githubHeaders(token)*)
      .withRequestTimeout(Duration(60000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val tree  = resp.json.select("tree").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq
          val files = tree.flatMap { item =>
            val itemType = item.select("type").asOpt[String].getOrElse("")
            val itemPath = item.select("path").asOpt[String].getOrElse("")
            if (itemType == "blob") Some(itemPath) else None
          }
          Right(files.toSeq): Either[JsValue, Seq[String]]
        } else {
          Left(Json.obj("error" -> s"GitHub API returned ${resp.status} for recursive tree listing")): Either[
            JsValue,
            Seq[String]
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitHub tree: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def listDirectory(
      apiBase: String,
      owner: String,
      repo: String,
      dirPath: String,
      branch: String,
      token: String,
      env: Env
  )(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$apiBase/repos/$owner/$repo/contents/$dirPath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(githubHeaders(token)*)
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
              Right(files.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "GitHub API did not return an array for directory listing")): Either[
                JsValue,
                Seq[String]
              ]
          }
        } else {
          Left(
            Json.obj("error" -> s"GitHub API returned ${resp.status} for directory listing", "status" -> resp.status)
          ): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitHub directory: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val repoFullName = payload.select("repository").select("full_name").asOpt[String].getOrElse("")
    val ref          = payload.select("ref").asOpt[String].getOrElse("")
    val branch       = ref.replace("refs/heads/", "")
    val matched      = possibleCatalogs.filter { catalog =>
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

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  private def parseOrg(repoUrl: String): Option[String] = {
    val cleaned = repoUrl.stripSuffix(".git").stripSuffix("/")
    val path    = if (cleaned.contains("://")) {
      cleaned.split("://", 2).last.split("/").drop(1).mkString("/")
    } else cleaned
    val parts   = path.split("/").filter(_.nonEmpty)
    if (parts.length == 1) Some(parts(0)) else None
  }

  private def listOrgRepos(apiBase: String, org: String, token: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val orgUrl = s"$apiBase/orgs/$org/repos"
    env.Ws
      .url(orgUrl)
      .withQueryStringParameters("per_page" -> "100", "type" -> "all")
      .withHttpHeaders(githubHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .flatMap { resp =>
        if (resp.status == 200) {
          val repos = resp.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap(_.select("name").asOpt[String])
          (Right(repos.toSeq): Either[JsValue, Seq[String]]).vfuture
        } else {
          val userUrl = s"$apiBase/users/$org/repos"
          env.Ws
            .url(userUrl)
            .withQueryStringParameters("per_page" -> "100", "type" -> "all")
            .withHttpHeaders(githubHeaders(token)*)
            .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
            .get()
            .map { resp2 =>
              if (resp2.status == 200) {
                Right(
                  resp2.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap(_.select("name").asOpt[String])
                ): Either[JsValue, Seq[String]]
              } else {
                Left(Json.obj("error" -> s"Cannot list repos for '$org'")): Either[JsValue, Seq[String]]
              }
            }
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing repos for '$org': ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def fetchFromSingleRepo(
      apiBase: String,
      owner: String,
      repo: String,
      branch: String,
      path: String,
      token: String,
      allRes: Seq[Resource],
      env: Env,
      skipIfMissing: Boolean = false
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    if (SourceUtils.hasFileExtension(path)) {
      fetchFileContent(apiBase, owner, repo, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(rawContent)                                         =>
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
                allRes,
                resolveGlob = Some(glob =>
                  listAllFilesRecursive(apiBase, owner, repo, branch, token, env).map {
                    case Left(err)    => Left(err)
                    case Right(files) => Right(SourceUtils.resolveRemoteGlob(files, basePath, glob))
                  }
                )
              )
            case None      =>
              SourceUtils.parseEntityContent(rawContent, s"github://$owner/$repo/$path@$branch", allRes).vfuture
          }
      }
    } else {
      listDirectory(apiBase, owner, repo, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(files)                                              =>
          files
            .mapAsync { filePath =>
              fetchFileContent(apiBase, owner, repo, filePath, branch, token, env).map(
                _.flatMap(rawContent =>
                  SourceUtils.parseEntityContent(rawContent, s"github://$owner/$repo/$filePath@$branch", allRes)
                )
              )
            }
            .map(SourceUtils.sequence)
      }
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl      = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch       = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path         = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token        = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val apiBase      =
      catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://api.github.com").stripSuffix("/")
    val repoPatterns =
      catalog.sourceConfig.select("repo_patterns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val allRes       = env.allResources.resources ++ env.adminExtensions.resources()

    parseRepo(repoUrl) match {
      case Some((owner, repo)) =>
        fetchFromSingleRepo(apiBase, owner, repo, branch, path, token, allRes, env)
      case None                =>
        parseOrg(repoUrl) match {
          case Some(org) =>
            listOrgRepos(apiBase, org, token, env).flatMap {
              case Left(err)    => err.leftf
              case Right(repos) =>
                val filtered =
                  if (repoPatterns.nonEmpty)
                    repos.filter(name => repoPatterns.exists(p => SourceUtils.matchesGlob(name, p)))
                  else repos
                logger.info(s"Scanning ${filtered.size} repos in org '$org' for path '$path'")
                filtered
                  .mapAsync { repoName =>
                    fetchFromSingleRepo(apiBase, org, repoName, branch, path, token, allRes, env, skipIfMissing = true)
                  }
                  .map(SourceUtils.sequence)
            }
          case None      =>
            Json.obj("error" -> s"Cannot parse GitHub repo or organization from: $repoUrl").leftf
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
  )(using ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val apiUrl = s"$baseUrl/api/v4/projects/$encodedProject/repository/files/$filePath/raw"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(gitlabHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for $filePath", "status" -> resp.status)): Either[
            JsValue,
            String
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from GitLab: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  private def listAllFilesRecursive(
      baseUrl: String,
      encodedProject: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$baseUrl/api/v4/projects/$encodedProject/repository/tree"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch, "recursive" -> "true", "per_page" -> "100")
      .withHttpHeaders(gitlabHeaders(token)*)
      .withRequestTimeout(Duration(60000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              val files = arr.value.flatMap { item =>
                val itemType = item.select("type").asOpt[String].getOrElse("")
                val itemPath = item.select("path").asOpt[String].getOrElse("")
                if (itemType == "blob") Some(itemPath) else None
              }
              Right(files.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "GitLab API did not return an array for recursive tree listing")): Either[
                JsValue,
                Seq[String]
              ]
          }
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for recursive tree listing")): Either[
            JsValue,
            Seq[String]
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitLab tree: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def listDirectory(
      baseUrl: String,
      encodedProject: String,
      dirPath: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$baseUrl/api/v4/projects/$encodedProject/repository/tree"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch, "path" -> dirPath, "per_page" -> "100")
      .withHttpHeaders(gitlabHeaders(token)*)
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
              Right(files.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "GitLab API did not return an array for tree listing")): Either[JsValue, Seq[
                String
              ]]
          }
        } else {
          Left(
            Json.obj("error" -> s"GitLab API returned ${resp.status} for tree listing", "status" -> resp.status)
          ): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitLab tree: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val projectWebUrl = payload.select("project").select("web_url").asOpt[String].getOrElse("")
    val ref           = payload.select("ref").asOpt[String].getOrElse("")
    val branch        = ref.replace("refs/heads/", "")
    val matched       = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == "gitlab" && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        configRepo.stripSuffix(".git") == projectWebUrl.stripSuffix(".git") && configBranch == branch
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  private def isGroup(repoUrl: String): Boolean = {
    val cleaned = repoUrl.stripSuffix("/")
    !cleaned.contains("/")
  }

  private def listGroupProjects(baseUrl: String, group: String, token: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val encodedGroup = java.net.URLEncoder.encode(group, "UTF-8")
    val apiUrl       = s"$baseUrl/api/v4/groups/$encodedGroup/projects"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("per_page" -> "100", "include_subgroups" -> "true")
      .withHttpHeaders(gitlabHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              val projects = arr.value.flatMap(_.select("path_with_namespace").asOpt[String])
              Right(projects.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "GitLab API did not return an array for group projects")): Either[
                JsValue,
                Seq[String]
              ]
          }
        } else {
          Left(Json.obj("error" -> s"GitLab API returned ${resp.status} for group projects")): Either[
            JsValue,
            Seq[String]
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing GitLab group projects: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def fetchFromSingleProject(
      baseUrl: String,
      projectPath: String,
      branch: String,
      path: String,
      token: String,
      allRes: Seq[Resource],
      env: Env,
      skipIfMissing: Boolean = false
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val encodedProject = java.net.URLEncoder.encode(projectPath, "UTF-8")
    if (SourceUtils.hasFileExtension(path)) {
      fetchFileContent(baseUrl, encodedProject, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(rawContent)                                         =>
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
                allRes,
                resolveGlob = Some(glob =>
                  listAllFilesRecursive(baseUrl, encodedProject, branch, token, env).map {
                    case Left(err)    => Left(err)
                    case Right(files) => Right(SourceUtils.resolveRemoteGlob(files, basePath, glob))
                  }
                )
              )
            case None      =>
              SourceUtils.parseEntityContent(rawContent, s"gitlab://$projectPath/$path@$branch", allRes).vfuture
          }
      }
    } else {
      listDirectory(baseUrl, encodedProject, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(files)                                              =>
          files
            .mapAsync { filePath =>
              fetchFileContent(baseUrl, encodedProject, filePath, branch, token, env).map(
                _.flatMap(rawContent =>
                  SourceUtils.parseEntityContent(rawContent, s"gitlab://$projectPath/$filePath@$branch", allRes)
                )
              )
            }
            .map(SourceUtils.sequence)
      }
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl      = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch       = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path         = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token        = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val baseUrl      = catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://gitlab.com")
    val repoPatterns =
      catalog.sourceConfig.select("repo_patterns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val allRes       = env.allResources.resources ++ env.adminExtensions.resources()

    if (isGroup(repoUrl)) {
      listGroupProjects(baseUrl, repoUrl, token, env).flatMap {
        case Left(err)       => err.leftf
        case Right(projects) =>
          val filtered = if (repoPatterns.nonEmpty) {
            projects.filter { p =>
              val name = p.split("/").lastOption.getOrElse(p)
              repoPatterns.exists(pat => SourceUtils.matchesGlob(name, pat))
            }
          } else projects
          logger.info(s"Scanning ${filtered.size} projects in group '$repoUrl' for path '$path'")
          filtered
            .mapAsync { projectPath =>
              fetchFromSingleProject(baseUrl, projectPath, branch, path, token, allRes, env, skipIfMissing = true)
            }
            .map(SourceUtils.sequence)
      }
    } else {
      parseProjectPath(repoUrl) match {
        case None              =>
          Json.obj("error" -> s"Cannot parse GitLab project path from: $repoUrl").leftf
        case Some(projectPath) =>
          fetchFromSingleProject(baseUrl, projectPath, branch, path, token, allRes, env)
      }
    }
  }
}

class CatalogSourceS3 extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-s3")

  override def sourceKind: String       = "s3"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "s3 source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]           =
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

  private def listAllKeys(bucket: String, prefix: String, config: JsObject, env: Env)(using
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[JsValue, Seq[String]]] = {
    S3.listBucket(bucket, Some(prefix))
      .withAttributes(s3ClientSettingsAttrs(config))
      .map(_.key)
      .runWith(Sink.seq)
      .map(keys => Right(keys.toSeq): Either[JsValue, Seq[String]])
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing S3 objects: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  // private def fetchS3Object(bucket: String, key: String, config: JsObject, env: Env)(using
  //     ec: ExecutionContext,
  //     mat: Materializer
  // ): Future[Either[JsValue, String]] = {
  //   S3.download(bucket, key)
  //     .withAttributes(s3ClientSettingsAttrs(config))
  //     .runWith(Sink.head)
  //     .flatMap {
  //       case None              =>
  //         (Left(Json.obj("error" -> s"S3 object not found: $bucket/$key")): Either[JsValue, String]).vfuture
  //       case Some((source, _)) =>
  //         source.runFold(ByteString.empty)(_ ++ _).map { bs =>
  //           Right(bs.utf8String): Either[JsValue, String]
  //         }
  //     }
  //     .recover { case e: Throwable =>
  //       Left(Json.obj("error" -> s"Error fetching S3 object $bucket/$key: ${e.getMessage}")): Either[JsValue, String]
  //     }
  // }

  private def fetchS3Object(bucket: String, key: String, config: JsObject, env: Env)(using
    ec: ExecutionContext,
    mat: Materializer
  ): Future[Either[JsValue, String]] = {
    S3.getObject(bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runFold(ByteString.empty)(_ ++ _)
      .map { bs =>
        Right(bs.utf8String): Either[JsValue, String]
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching S3 object $bucket/$key: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
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
                allRes,
                resolveGlob = Some(glob =>
                  listAllKeys(bucket, if (baseKey.nonEmpty) baseKey + "/" else "", catalog.sourceConfig, env).map {
                    case Left(err)   => Left(err)
                    case Right(keys) => Right(SourceUtils.resolveRemoteGlob(keys, baseKey, glob))
                  }
                )
              )
            case None      =>
              SourceUtils.parseEntityContent(rawContent, s"s3://$bucket/$key", allRes).vfuture
          }
      }
    }
  }
}

class CatalogSourceConsulKv extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-consulkv")

  override def sourceKind: String       = "consulkv"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "consulkv source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]           =
    Json.obj("error" -> "consulkv source does not support webhooks").leftf

  private def consulHeaders(token: String): Seq[(String, String)] = {
    Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++
    (if (token.nonEmpty) Seq("X-Consul-Token" -> token) else Seq.empty)
  }

  private def fetchRawKey(endpoint: String, key: String, token: String, dc: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    val params = Seq("raw" -> "") ++ (if (dc.nonEmpty) Seq("dc" -> dc) else Seq.empty)
    env.Ws
      .url(s"$endpoint/v1/kv/$key")
      .withQueryStringParameters(params*)
      .withHttpHeaders(consulHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(Json.obj("error" -> s"Consul KV returned ${resp.status} for key $key")): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching key $key from Consul: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  private def listAllKeys(endpoint: String, prefix: String, token: String, dc: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val cleanPrefix = prefix.stripSuffix("/") + "/"
    val params      = Seq("keys" -> "") ++ (if (dc.nonEmpty) Seq("dc" -> dc) else Seq.empty)
    env.Ws
      .url(s"$endpoint/v1/kv/$cleanPrefix")
      .withQueryStringParameters(params*)
      .withHttpHeaders(consulHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              Right(arr.value.flatMap(_.asOpt[String]).toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "Consul KV did not return an array for key listing")): Either[JsValue, Seq[
                String
              ]]
          }
        } else if (resp.status == 404) {
          Right(Seq.empty[String]): Either[JsValue, Seq[String]]
        } else {
          Left(Json.obj("error" -> s"Consul KV returned ${resp.status} for key listing")): Either[JsValue, Seq[
            String
          ]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing keys from Consul: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def listKeys(endpoint: String, prefix: String, token: String, dc: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val cleanPrefix = prefix.stripSuffix("/") + "/"
    val params      = Seq("keys" -> "") ++ (if (dc.nonEmpty) Seq("dc" -> dc) else Seq.empty)
    env.Ws
      .url(s"$endpoint/v1/kv/$cleanPrefix")
      .withQueryStringParameters(params*)
      .withHttpHeaders(consulHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case arr: JsArray =>
              val keys = arr.value.flatMap(_.asOpt[String]).filter { key =>
                val name = key.split("/").lastOption.getOrElse("")
                name.nonEmpty && SourceUtils.isEntityFile(name)
              }
              Right(keys.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> "Consul KV did not return an array for key listing")): Either[JsValue, Seq[
                String
              ]]
          }
        } else if (resp.status == 404) {
          Right(Seq.empty[String]): Either[JsValue, Seq[String]]
        } else {
          Left(Json.obj("error" -> s"Consul KV returned ${resp.status} for prefix listing")): Either[JsValue, Seq[
            String
          ]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing keys from Consul: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val endpoint =
      catalog.sourceConfig.select("endpoint").asOpt[String].getOrElse("http://localhost:8500").stripSuffix("/")
    val prefix   = catalog.sourceConfig.select("prefix").asOpt[String].getOrElse("").stripPrefix("/")
    val token    = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val dc       = catalog.sourceConfig.select("dc").asOpt[String].getOrElse("")

    if (prefix.isEmpty) {
      Json.obj("error" -> "Consul KV prefix is required").leftf
    } else {
      val allRes = env.allResources.resources ++ env.adminExtensions.resources()
      if (SourceUtils.hasFileExtension(prefix)) {
        fetchRawKey(endpoint, prefix, token, dc, env).flatMap {
          case Left(err)         => err.leftf
          case Right(rawContent) =>
            SourceUtils.isDeployListing(rawContent) match {
              case Some(arr) =>
                val basePrefix = if (prefix.contains("/")) prefix.substring(0, prefix.lastIndexOf('/')) else ""
                SourceUtils.resolveDeployListing(
                  arr,
                  relativePath => {
                    val fullKey = if (basePrefix.nonEmpty) s"$basePrefix/$relativePath" else relativePath
                    fetchRawKey(endpoint, fullKey, token, dc, env)
                  },
                  s"consul://$endpoint/$prefix",
                  allRes,
                  resolveGlob = Some(glob =>
                    listAllKeys(endpoint, if (basePrefix.nonEmpty) basePrefix else prefix, token, dc, env).map {
                      case Left(err)   => Left(err)
                      case Right(keys) => Right(SourceUtils.resolveRemoteGlob(keys, basePrefix, glob))
                    }
                  )
                )
              case None      =>
                SourceUtils.parseEntityContent(rawContent, s"consul://$endpoint/$prefix", allRes).vfuture
            }
        }
      } else {
        listKeys(endpoint, prefix, token, dc, env).flatMap {
          case Left(err)   => err.leftf
          case Right(keys) =>
            keys
              .mapAsync { key =>
                fetchRawKey(endpoint, key, token, dc, env).map(
                  _.flatMap(rawContent => SourceUtils.parseEntityContent(rawContent, s"consul://$endpoint/$key", allRes))
                )
              }
              .map(SourceUtils.sequence)
        }
      }
    }
  }
}

class CatalogSourceBitbucket extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-bitbucket")

  override def sourceKind: String       = "bitbucket"
  override def supportsWebhook: Boolean = true

  private def parseRepo(repoUrl: String): Option[(String, String)] = {
    val cleaned = repoUrl.stripSuffix(".git").stripSuffix("/")
    val parts   = cleaned.split("/")
    if (parts.length >= 2) {
      Some((parts(parts.length - 2), parts(parts.length - 1)))
    } else {
      None
    }
  }

  private def bitbucketHeaders(token: String, username: String): Seq[(String, String)] = {
    val auth = if (token.nonEmpty) {
      if (username.nonEmpty) {
        val encoded = java.util.Base64.getEncoder.encodeToString(s"$username:$token".getBytes(StandardCharsets.UTF_8))
        Seq("Authorization" -> s"Basic $encoded")
      } else {
        Seq("Authorization" -> s"Bearer $token")
      }
    } else {
      Seq.empty
    }
    Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++ auth
  }

  private def fetchFileContent(
      apiBase: String,
      workspace: String,
      repo: String,
      filePath: String,
      branch: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val apiUrl = s"$apiBase/2.0/repositories/$workspace/$repo/src/$branch/$filePath"
    env.Ws
      .url(apiUrl)
      .withHttpHeaders(bitbucketHeaders(token, username)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(
            Json.obj("error" -> s"Bitbucket API returned ${resp.status} for $filePath", "status" -> resp.status)
          ): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from Bitbucket: ${e.getMessage}")): Either[JsValue, String]
      }
  }

  private def listDirectory(
      apiBase: String,
      workspace: String,
      repo: String,
      dirPath: String,
      branch: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val path   = if (dirPath.isEmpty || dirPath == "/") "" else dirPath.stripSuffix("/")
    val apiUrl = s"$apiBase/2.0/repositories/$workspace/$repo/src/$branch/$path"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("pagelen" -> "100")
      .withHttpHeaders(bitbucketHeaders(token, username)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val json  = resp.json
          val files = json.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap { item =>
            val itemType = item.select("type").asOpt[String].getOrElse("")
            val itemPath = item.select("path").asOpt[String].getOrElse("")
            val itemName = itemPath.split("/").lastOption.getOrElse("")
            if (itemType == "commit_file" && SourceUtils.isEntityFile(itemName)) Some(itemPath) else None
          }
          Right(files.toSeq): Either[JsValue, Seq[String]]
        } else {
          Left(
            Json.obj("error" -> s"Bitbucket API returned ${resp.status} for directory listing", "status" -> resp.status)
          ): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing Bitbucket directory: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val repoFullName = payload.select("repository").select("full_name").asOpt[String].getOrElse("")
    val changes      = payload.select("push").select("changes").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq
    val branches     = changes.flatMap(c => c.select("new").select("name").asOpt[String]).toSet
    val matched      = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == "bitbucket" && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        parseRepo(configRepo).exists { case (ws, repo) =>
          s"$ws/$repo" == repoFullName && branches.contains(configBranch)
        }
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  private def parseWorkspace(repoUrl: String): Option[String] = {
    val cleaned = repoUrl.stripSuffix(".git").stripSuffix("/")
    val path    = if (cleaned.contains("://")) {
      cleaned.split("://", 2).last.split("/").drop(1).mkString("/")
    } else cleaned
    val parts   = path.split("/").filter(_.nonEmpty)
    if (parts.length == 1) Some(parts(0)) else None
  }

  private def listWorkspaceRepos(apiBase: String, workspace: String, token: String, username: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$apiBase/2.0/repositories/$workspace"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("pagelen" -> "100")
      .withHttpHeaders(bitbucketHeaders(token, username)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val repos = resp.json
            .select("values")
            .asOpt[Seq[JsObject]]
            .getOrElse(Seq.empty).toSeq
            .flatMap(_.select("slug").asOpt[String])
          Right(repos.toSeq): Either[JsValue, Seq[String]]
        } else {
          Left(Json.obj("error" -> s"Bitbucket API returned ${resp.status} for workspace repos")): Either[
            JsValue,
            Seq[String]
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing Bitbucket workspace repos: ${e.getMessage}")): Either[
          JsValue,
          Seq[String]
        ]
      }
  }

  private def fetchFromSingleRepo(
      apiBase: String,
      workspace: String,
      repo: String,
      branch: String,
      path: String,
      token: String,
      username: String,
      allRes: Seq[Resource],
      env: Env,
      skipIfMissing: Boolean = false
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    if (SourceUtils.hasFileExtension(path)) {
      fetchFileContent(apiBase, workspace, repo, path, branch, token, username, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(rawContent)                                         =>
          SourceUtils.isDeployListing(rawContent) match {
            case Some(arr) =>
              val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployListing(
                arr,
                relativePath => {
                  val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                  fetchFileContent(apiBase, workspace, repo, fullPath, branch, token, username, env)
                },
                s"bitbucket://$workspace/$repo/$path@$branch",
                allRes
              )
            case None      =>
              SourceUtils
                .parseEntityContent(rawContent, s"bitbucket://$workspace/$repo/$path@$branch", allRes)
                .vfuture
          }
      }
    } else {
      listDirectory(apiBase, workspace, repo, path, branch, token, username, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(files)                                              =>
          files
            .mapAsync { filePath =>
              fetchFileContent(apiBase, workspace, repo, filePath, branch, token, username, env).map(
                _.flatMap(rawContent =>
                  SourceUtils.parseEntityContent(
                    rawContent,
                    s"bitbucket://$workspace/$repo/$filePath@$branch",
                    allRes
                  )
                )
              )
            }
            .map(SourceUtils.sequence)
      }
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl      = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch       = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path         = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token        = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val username     = catalog.sourceConfig.select("username").asOpt[String].getOrElse("")
    val apiBase      =
      catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("https://api.bitbucket.org").stripSuffix("/")
    val repoPatterns =
      catalog.sourceConfig.select("repo_patterns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val allRes       = env.allResources.resources ++ env.adminExtensions.resources()

    parseRepo(repoUrl) match {
      case Some((workspace, repo)) =>
        fetchFromSingleRepo(apiBase, workspace, repo, branch, path, token, username, allRes, env)
      case None                    =>
        parseWorkspace(repoUrl) match {
          case Some(workspace) =>
            listWorkspaceRepos(apiBase, workspace, token, username, env).flatMap {
              case Left(err)    => err.leftf
              case Right(repos) =>
                val filtered =
                  if (repoPatterns.nonEmpty)
                    repos.filter(name => repoPatterns.exists(p => SourceUtils.matchesGlob(name, p)))
                  else repos
                logger.info(s"Scanning ${filtered.size} repos in workspace '$workspace' for path '$path'")
                filtered
                  .mapAsync { repoName =>
                    fetchFromSingleRepo(
                      apiBase,
                      workspace,
                      repoName,
                      branch,
                      path,
                      token,
                      username,
                      allRes,
                      env,
                      skipIfMissing = true
                    )
                  }
                  .map(SourceUtils.sequence)
            }
          case None            =>
            Json.obj("error" -> s"Cannot parse Bitbucket repo or workspace from: $repoUrl").leftf
        }
    }
  }
}

class CatalogSourceBitbucketServer extends CatalogSource {

  private val logger = Logger("otoroshi-remote-catalog-source-bitbucketserver")

  override def sourceKind: String       = "bitbucketserver"
  override def supportsWebhook: Boolean = true

  private def parseRepo(repoUrl: String): Option[(String, String)] = {
    val cleaned    = repoUrl.stripSuffix(".git").stripSuffix("/")
    val parts      = cleaned.split("/").filter(_.nonEmpty).toSeq
    val projectIdx = parts.indexOf("projects")
    val repoIdx    = parts.indexOf("repos")
    if (projectIdx >= 0 && repoIdx == projectIdx + 2 && parts.length > repoIdx + 1) {
      Some((parts(projectIdx + 1), parts(repoIdx + 1)))
    } else if (parts.length >= 2) {
      Some((parts(parts.length - 2), parts(parts.length - 1)))
    } else {
      None
    }
  }

  private def parseProject(repoUrl: String): Option[String] = {
    val cleaned    = repoUrl.stripSuffix(".git").stripSuffix("/")
    val path       = if (cleaned.contains("://")) {
      cleaned.split("://", 2).last.split("/").drop(1).mkString("/")
    } else cleaned
    val parts      = path.split("/").filter(_.nonEmpty).toSeq
    val projectIdx = parts.indexOf("projects")
    if (projectIdx >= 0 && !parts.contains("repos") && parts.length > projectIdx + 1) {
      Some(parts(projectIdx + 1))
    } else if (parts.length == 1) {
      Some(parts(0))
    } else {
      None
    }
  }

  private def bitbucketHeaders(token: String, username: String): Seq[(String, String)] = {
    val auth = if (token.nonEmpty) {
      if (username.nonEmpty) {
        val encoded = java.util.Base64.getEncoder.encodeToString(s"$username:$token".getBytes(StandardCharsets.UTF_8))
        Seq("Authorization" -> s"Basic $encoded")
      } else {
        Seq("Authorization" -> s"Bearer $token")
      }
    } else {
      Seq.empty
    }
    Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++ auth
  }

  // Bitbucket Server pages every collection with start/limit/isLastPage/nextPageStart and defaults to 25 items,
  // so every listing endpoint must be followed to the last page.
  private def fetchPaged(
      url: String,
      params: Seq[(String, String)],
      pageOf: JsValue => JsObject,
      extract: JsObject => Seq[String],
      token: String,
      username: String,
      env: Env,
      context: String
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    def go(start: Int, acc: Seq[String]): Future[Either[JsValue, Seq[String]]] = {
      env.Ws
        .url(url)
        .withQueryStringParameters((params ++ Seq("limit" -> "1000", "start" -> start.toString))*)
        .withHttpHeaders(bitbucketHeaders(token, username)*)
        .withRequestTimeout(Duration(60000L, TimeUnit.MILLISECONDS))
        .get()
        .flatMap { resp =>
          if (resp.status == 200) {
            val page       = pageOf(resp.json)
            val items      = acc ++ extract(page)
            val isLastPage = page.select("isLastPage").asOpt[Boolean].getOrElse(true)
            val nextStart  = page.select("nextPageStart").asOpt[Int]
            (isLastPage, nextStart) match {
              case (false, Some(next)) if next > start => go(next, items)
              case _                                   => (Right(items): Either[JsValue, Seq[String]]).vfuture
            }
          } else {
            (Left(
              Json.obj("error" -> s"Bitbucket Server API returned ${resp.status} for $context", "status" -> resp.status)
            ): Either[JsValue, Seq[String]]).vfuture
          }
        }
        .recover { case e: Throwable =>
          Left(Json.obj("error" -> s"Error fetching $context from Bitbucket Server: ${e.getMessage}")): Either[
            JsValue,
            Seq[String]
          ]
        }
    }
    go(0, Seq.empty)
  }

  private def fetchFileContent(
      apiBase: String,
      project: String,
      repo: String,
      filePath: String,
      branch: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val apiUrl = s"$apiBase/projects/$project/repos/$repo/raw/$filePath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("at" -> s"refs/heads/$branch")
      .withHttpHeaders(bitbucketHeaders(token, username)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(
            Json.obj("error" -> s"Bitbucket Server API returned ${resp.status} for $filePath", "status" -> resp.status)
          ): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from Bitbucket Server: ${e.getMessage}")): Either[
          JsValue,
          String
        ]
      }
  }

  private def listDirectory(
      apiBase: String,
      project: String,
      repo: String,
      dirPath: String,
      branch: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val path = if (dirPath.isEmpty || dirPath == "/") "" else dirPath.stripSuffix("/")
    val url  =
      if (path.isEmpty) s"$apiBase/projects/$project/repos/$repo/browse"
      else s"$apiBase/projects/$project/repos/$repo/browse/$path"
    fetchPaged(
      url,
      Seq("at" -> s"refs/heads/$branch"),
      json => json.select("children").asOpt[JsObject].getOrElse(Json.obj()),
      page =>
        page.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap { item =>
          val itemType = item.select("type").asOpt[String].getOrElse("")
          val itemName = item.select("path").select("toString").asOpt[String].getOrElse("")
          if (itemType == "FILE" && SourceUtils.isEntityFile(itemName)) {
            if (path.isEmpty) Some(itemName) else Some(s"$path/$itemName")
          } else None
        },
      token,
      username,
      env,
      s"directory listing of '$path'"
    )
  }

  // /files lists every file of the repository recursively, which the Bitbucket Cloud API does not offer.
  private def listAllFilesRecursive(
      apiBase: String,
      project: String,
      repo: String,
      branch: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    fetchPaged(
      s"$apiBase/projects/$project/repos/$repo/files",
      Seq("at" -> s"refs/heads/$branch"),
      json => json.asOpt[JsObject].getOrElse(Json.obj()),
      page => page.select("values").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
      token,
      username,
      env,
      "recursive file listing"
    )
  }

  private def listProjectRepos(
      apiBase: String,
      project: String,
      token: String,
      username: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    fetchPaged(
      s"$apiBase/projects/$project/repos",
      Seq.empty,
      json => json.asOpt[JsObject].getOrElse(Json.obj()),
      page =>
        page.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap(_.select("slug").asOpt[String]),
      token,
      username,
      env,
      s"repos of project '$project'"
    )
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val projectKey = payload.select("repository").select("project").select("key").asOpt[String].getOrElse("")
    val slug       = payload.select("repository").select("slug").asOpt[String].getOrElse("")
    val branches   = payload
      .select("changes")
      .asOpt[Seq[JsObject]]
      .getOrElse(Seq.empty)
      .toSeq
      .flatMap(c => c.select("ref").select("displayId").asOpt[String])
      .toSet
    val matched    = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == "bitbucketserver" && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        parseRepo(configRepo).exists { case (project, repo) =>
          // project keys are case insensitive on Bitbucket Server
          project.equalsIgnoreCase(projectKey) && repo == slug && branches.contains(configBranch)
        }
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  private def fetchFromSingleRepo(
      apiBase: String,
      project: String,
      repo: String,
      branch: String,
      path: String,
      token: String,
      username: String,
      allRes: Seq[Resource],
      env: Env,
      skipIfMissing: Boolean = false
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    if (SourceUtils.hasFileExtension(path)) {
      fetchFileContent(apiBase, project, repo, path, branch, token, username, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(rawContent)                                         =>
          SourceUtils.isDeployListing(rawContent) match {
            case Some(arr) =>
              val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployListing(
                arr,
                relativePath => {
                  val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                  fetchFileContent(apiBase, project, repo, fullPath, branch, token, username, env)
                },
                s"bitbucketserver://$project/$repo/$path@$branch",
                allRes,
                resolveGlob = Some(glob =>
                  listAllFilesRecursive(apiBase, project, repo, branch, token, username, env).map {
                    case Left(err)    => Left(err)
                    case Right(files) => Right(SourceUtils.resolveRemoteGlob(files, basePath, glob))
                  }
                )
              )
            case None      =>
              SourceUtils
                .parseEntityContent(rawContent, s"bitbucketserver://$project/$repo/$path@$branch", allRes)
                .vfuture
          }
      }
    } else {
      listDirectory(apiBase, project, repo, path, branch, token, username, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(files)                                              =>
          files
            .mapAsync { filePath =>
              fetchFileContent(apiBase, project, repo, filePath, branch, token, username, env).map(
                _.flatMap(rawContent =>
                  SourceUtils.parseEntityContent(
                    rawContent,
                    s"bitbucketserver://$project/$repo/$filePath@$branch",
                    allRes
                  )
                )
              )
            }
            .map(SourceUtils.sequence)
      }
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl      = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch       = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path         = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token        = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val username     = catalog.sourceConfig.select("username").asOpt[String].getOrElse("")
    val rootUrl      =
      catalog.sourceConfig.select("base_url").asOpt[String].getOrElse("http://localhost:7990").stripSuffix("/")
    val apiBase      = if (rootUrl.endsWith("/rest/api/1.0")) rootUrl else s"$rootUrl/rest/api/1.0"
    val repoPatterns =
      catalog.sourceConfig.select("repo_patterns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val allRes       = env.allResources.resources ++ env.adminExtensions.resources()

    parseProject(repoUrl) match {
      case Some(project) =>
        listProjectRepos(apiBase, project, token, username, env).flatMap {
          case Left(err)    => err.leftf
          case Right(repos) =>
            val filtered =
              if (repoPatterns.nonEmpty)
                repos.filter(name => repoPatterns.exists(p => SourceUtils.matchesGlob(name, p)))
              else repos
            logger.info(s"Scanning ${filtered.size} repos in project '$project' for path '$path'")
            filtered
              .mapAsync { repoName =>
                fetchFromSingleRepo(
                  apiBase,
                  project,
                  repoName,
                  branch,
                  path,
                  token,
                  username,
                  allRes,
                  env,
                  skipIfMissing = true
                )
              }
              .map(SourceUtils.sequence)
        }
      case None          =>
        parseRepo(repoUrl) match {
          case Some((project, repo)) =>
            fetchFromSingleRepo(apiBase, project, repo, branch, path, token, username, allRes, env)
          case None                  =>
            Json.obj("error" -> s"Cannot parse Bitbucket Server repo or project from: $repoUrl").leftf
        }
    }
  }
}

class CatalogSourceGiteaCompat(
    override val sourceKind: String,
    defaultBaseUrl: String
) extends CatalogSource {

  private val logger = Logger(s"otoroshi-remote-catalog-source-$sourceKind")

  override def supportsWebhook: Boolean = true

  private def parseRepo(repoUrl: String): Option[(String, String)] = {
    val cleaned = repoUrl.stripSuffix(".git").stripSuffix("/")
    val parts   = cleaned.split("/")
    if (parts.length >= 2) {
      Some((parts(parts.length - 2), parts(parts.length - 1)))
    } else {
      None
    }
  }

  private def parseOrg(repoUrl: String): Option[String] = {
    val cleaned = repoUrl.stripSuffix(".git").stripSuffix("/")
    val path    = if (cleaned.contains("://")) {
      cleaned.split("://", 2).last.split("/").drop(1).mkString("/")
    } else cleaned
    val parts   = path.split("/").filter(_.nonEmpty)
    if (parts.length == 1) Some(parts(0)) else None
  }

  private def giteaHeaders(token: String): Seq[(String, String)] = {
    Seq("User-Agent" -> "Otoroshi-Remote-Catalogs") ++
    (if (token.nonEmpty) Seq("Authorization" -> s"token $token") else Seq.empty)
  }

  private def fetchFileContent(
      baseUrl: String,
      owner: String,
      repo: String,
      filePath: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val apiUrl = s"$baseUrl/api/v1/repos/$owner/$repo/raw/$filePath"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(giteaHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.body[String]): Either[JsValue, String]
        } else {
          Left(
            Json.obj("error" -> s"$sourceKind API returned ${resp.status} for $filePath", "status" -> resp.status)
          ): Either[JsValue, String]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error fetching $filePath from $sourceKind: ${e.getMessage}")): Either[
          JsValue,
          String
        ]
      }
  }

  private def listAllFilesRecursive(
      baseUrl: String,
      owner: String,
      repo: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val apiUrl = s"$baseUrl/api/v1/repos/$owner/$repo/git/trees/$branch"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("recursive" -> "true")
      .withHttpHeaders(giteaHeaders(token)*)
      .withRequestTimeout(Duration(60000L, TimeUnit.MILLISECONDS))
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val tree  = resp.json.select("tree").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq
          val files = tree.flatMap { item =>
            val itemType = item.select("type").asOpt[String].getOrElse("")
            val itemPath = item.select("path").asOpt[String].getOrElse("")
            if (itemType == "blob") Some(itemPath) else None
          }
          Right(files.toSeq): Either[JsValue, Seq[String]]
        } else {
          Left(Json.obj("error" -> s"$sourceKind API returned ${resp.status} for recursive tree listing")): Either[
            JsValue,
            Seq[String]
          ]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing $sourceKind tree: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  private def listDirectory(
      baseUrl: String,
      owner: String,
      repo: String,
      dirPath: String,
      branch: String,
      token: String,
      env: Env
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[String]]] = {
    val path   = if (dirPath.isEmpty || dirPath == "/") "" else dirPath.stripSuffix("/")
    val apiUrl = s"$baseUrl/api/v1/repos/$owner/$repo/contents/$path"
    env.Ws
      .url(apiUrl)
      .withQueryStringParameters("ref" -> branch)
      .withHttpHeaders(giteaHeaders(token)*)
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
              Right(files.toSeq): Either[JsValue, Seq[String]]
            case _            =>
              Left(Json.obj("error" -> s"$sourceKind API did not return an array for directory listing")): Either[
                JsValue,
                Seq[String]
              ]
          }
        } else {
          Left(
            Json.obj("error" -> s"$sourceKind API returned ${resp.status} for directory listing", "status" -> resp.status)
          ): Either[JsValue, Seq[String]]
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing $sourceKind directory: ${e.getMessage}")): Either[JsValue, Seq[String]]
      }
  }

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] = {
    val repoFullName = payload.select("repository").select("full_name").asOpt[String].getOrElse("")
    val ref          = payload.select("ref").asOpt[String].getOrElse("")
    val branch       = ref.replace("refs/heads/", "")
    val matched      = possibleCatalogs.filter { catalog =>
      catalog.sourceKind == sourceKind && {
        val configRepo   = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
        val configBranch = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
        parseRepo(configRepo).exists { case (owner, repo) =>
          s"$owner/$repo" == repoFullName && configBranch == branch
        }
      }
    }
    matched.rightf
  }

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]] = Json.obj().rightf

  private def listOrgRepos(baseUrl: String, org: String, token: String, env: Env)(using
      ec: ExecutionContext
  ): Future[Either[JsValue, Seq[String]]] = {
    val orgUrl = s"$baseUrl/api/v1/orgs/$org/repos"
    env.Ws
      .url(orgUrl)
      .withQueryStringParameters("limit" -> "50")
      .withHttpHeaders(giteaHeaders(token)*)
      .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
      .get()
      .flatMap { resp =>
        if (resp.status == 200) {
          val repos = resp.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap(_.select("name").asOpt[String])
          (Right(repos.toSeq): Either[JsValue, Seq[String]]).vfuture
        } else {
          val userUrl = s"$baseUrl/api/v1/users/$org/repos"
          env.Ws
            .url(userUrl)
            .withQueryStringParameters("limit" -> "50")
            .withHttpHeaders(giteaHeaders(token)*)
            .withRequestTimeout(Duration(30000L, TimeUnit.MILLISECONDS))
            .get()
            .map { resp2 =>
              if (resp2.status == 200) {
                Right(
                  resp2.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.flatMap(_.select("name").asOpt[String])
                ): Either[JsValue, Seq[String]]
              } else {
                Left(Json.obj("error" -> s"Cannot list repos for '$org' on $sourceKind")): Either[JsValue, Seq[String]]
              }
            }
        }
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Error listing repos for '$org' on $sourceKind: ${e.getMessage}")): Either[
          JsValue,
          Seq[String]
        ]
      }
  }

  private def fetchFromSingleRepo(
      baseUrl: String,
      owner: String,
      repo: String,
      branch: String,
      path: String,
      token: String,
      allRes: Seq[Resource],
      env: Env,
      skipIfMissing: Boolean = false
  )(using ec: ExecutionContext): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    if (SourceUtils.hasFileExtension(path)) {
      fetchFileContent(baseUrl, owner, repo, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(rawContent)                                         =>
          SourceUtils.isDeployListing(rawContent) match {
            case Some(arr) =>
              val basePath = if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""
              SourceUtils.resolveDeployListing(
                arr,
                relativePath => {
                  val fullPath = if (basePath.nonEmpty) s"$basePath/$relativePath" else relativePath
                  fetchFileContent(baseUrl, owner, repo, fullPath, branch, token, env)
                },
                s"$sourceKind://$owner/$repo/$path@$branch",
                allRes,
                resolveGlob = Some(glob =>
                  listAllFilesRecursive(baseUrl, owner, repo, branch, token, env).map {
                    case Left(err)    => Left(err)
                    case Right(files) => Right(SourceUtils.resolveRemoteGlob(files, basePath, glob))
                  }
                )
              )
            case None      =>
              SourceUtils.parseEntityContent(rawContent, s"$sourceKind://$owner/$repo/$path@$branch", allRes).vfuture
          }
      }
    } else {
      listDirectory(baseUrl, owner, repo, path, branch, token, env).flatMap {
        case Left(err) if skipIfMissing && SourceUtils.isNotFound(err) => Seq.empty[RemoteEntity].rightf
        case Left(err)                                                 => err.leftf
        case Right(files)                                              =>
          files
            .mapAsync { filePath =>
              fetchFileContent(baseUrl, owner, repo, filePath, branch, token, env).map(
                _.flatMap(rawContent =>
                  SourceUtils.parseEntityContent(rawContent, s"$sourceKind://$owner/$repo/$filePath@$branch", allRes)
                )
              )
            }
            .map(SourceUtils.sequence)
      }
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val repoUrl      = catalog.sourceConfig.select("repo").asOpt[String].getOrElse("")
    val branch       = catalog.sourceConfig.select("branch").asOpt[String].getOrElse("main")
    val path         = catalog.sourceConfig.select("path").asOpt[String].getOrElse("/").stripPrefix("/")
    val token        = catalog.sourceConfig.select("token").asOpt[String].getOrElse("")
    val baseUrl      =
      catalog.sourceConfig.select("base_url").asOpt[String].getOrElse(defaultBaseUrl).stripSuffix("/")
    val repoPatterns =
      catalog.sourceConfig.select("repo_patterns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val allRes       = env.allResources.resources ++ env.adminExtensions.resources()

    parseRepo(repoUrl) match {
      case Some((owner, repo)) =>
        fetchFromSingleRepo(baseUrl, owner, repo, branch, path, token, allRes, env)
      case None                =>
        parseOrg(repoUrl) match {
          case Some(org) =>
            listOrgRepos(baseUrl, org, token, env).flatMap {
              case Left(err)    => err.leftf
              case Right(repos) =>
                val filtered =
                  if (repoPatterns.nonEmpty)
                    repos.filter(name => repoPatterns.exists(p => SourceUtils.matchesGlob(name, p)))
                  else repos
                logger.info(s"Scanning ${filtered.size} repos in org '$org' on $sourceKind for path '$path'")
                filtered
                  .mapAsync { repoName =>
                    fetchFromSingleRepo(baseUrl, org, repoName, branch, path, token, allRes, env, skipIfMissing = true)
                  }
                  .map(SourceUtils.sequence)
            }
          case None      =>
            Json.obj("error" -> s"Cannot parse $sourceKind repo or organization from: $repoUrl").leftf
        }
    }
  }
}

class CatalogSourceGitea    extends CatalogSourceGiteaCompat("gitea", "http://localhost:3000")
class CatalogSourceForgejo  extends CatalogSourceGiteaCompat("forgejo", "http://localhost:3000")
class CatalogSourceCodeberg extends CatalogSourceGiteaCompat("codeberg", "https://codeberg.org")

class CatalogSourceGit extends CatalogSource {

  import scala.sys.process.*

  private val logger = Logger("otoroshi-remote-catalog-source-git")

  override def sourceKind: String       = "git"
  override def supportsWebhook: Boolean = false

  override def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]] =
    Json.obj("error" -> "git source does not support webhooks").leftf

  override def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]           =
    Json.obj("error" -> "git source does not support webhooks").leftf

  private def repoDir(catalog: RemoteCatalog): File = {
    val base = new File(System.getProperty("java.io.tmpdir"), "otoroshi-remote-catalogs-git")
    base.mkdirs()
    new File(base, catalog.id.replace("/", "_").replace(":", "_"))
  }

  private def buildRepoUrl(config: JsObject): String = {
    val repo     = config.select("repo").asOpt[String].getOrElse("")
    val token    = config.select("token").asOpt[String].getOrElse("")
    val username = config.select("username").asOpt[String].getOrElse("")
    if (token.nonEmpty && repo.startsWith("https://")) {
      val afterScheme = repo.stripPrefix("https://")
      if (username.nonEmpty) {
        s"https://$username:$token@$afterScheme"
      } else {
        s"https://$token@$afterScheme"
      }
    } else {
      repo
    }
  }

  private def sshEnv(config: JsObject): Seq[(String, String)] = {
    val sshPrivateKeyPath = config.select("ssh_private_key_path").asOpt[String].getOrElse("")
    if (sshPrivateKeyPath.nonEmpty) {
      Seq("GIT_SSH_COMMAND" -> s"ssh -i $sshPrivateKeyPath -o StrictHostKeyChecking=no")
    } else {
      Seq.empty
    }
  }

  private def runGit(args: Seq[String], cwd: Option[File], config: JsObject): Either[String, String] = {
    Try {
      var stdout        = ""
      var stderr        = ""
      val processLogger = ProcessLogger(
        out => { stdout = stdout + out + "\n" },
        err => { stderr = stderr + err + "\n" }
      )
      val envVars       = sshEnv(config)
      val cmd           = Process(Seq("git") ++ args, cwd, envVars*)
      val code          = cmd.!(processLogger)
      if (code != 0) {
        Left(s"git ${args.head} failed (exit $code): ${stderr.take(500)}")
      } else {
        Right(stdout)
      }
    }.getOrElse(Left(s"git ${args.head} execution failed"))
  }

  private def cloneOrPull(catalog: RemoteCatalog): Either[String, File] = {
    val config = catalog.sourceConfig
    val branch = config.select("branch").asOpt[String].getOrElse("main")
    val dir    = repoDir(catalog)
    val gitDir = new File(dir, ".git")

    if (gitDir.isDirectory) {
      for {
        _ <- runGit(Seq("fetch", "--all"), Some(dir), config)
        _ <- runGit(Seq("checkout", branch), Some(dir), config)
        _ <- runGit(Seq("reset", "--hard", s"origin/$branch"), Some(dir), config)
      } yield dir
    } else {
      val repoUrl = buildRepoUrl(config)
      runGit(
        Seq("clone", "--branch", branch, "--single-branch", "--depth", "1", repoUrl, dir.getAbsolutePath),
        None,
        config
      ).map(_ => dir)
    }
  }

  private def readLocalEntities(
      baseDir: File,
      path: String,
      allRes: Seq[Resource]
  ): Either[JsValue, Seq[RemoteEntity]] = {
    Try {
      val target = if (path.isEmpty || path == "/" || path == ".") baseDir else new File(baseDir, path)
      if (target.isDirectory) {
        val entityFiles = target.listFiles().filter(f => f.isFile && SourceUtils.isEntityFile(f.getName)).toSeq
        SourceUtils.sequence(entityFiles.map { f =>
          val rawContent = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
          SourceUtils.parseEntityContent(rawContent, s"git://${f.getPath}", allRes)
        })
      } else if (target.isFile) {
        val rawContent = new String(Files.readAllBytes(target.toPath), StandardCharsets.UTF_8)
        SourceUtils.isDeployListing(rawContent) match {
          case Some(arr) =>
            val basePath                    = target.getParentFile.getAbsolutePath
            val baseDir_                    = new File(basePath)
            val rawPaths                    = arr.value.flatMap(_.asOpt[String])
            val resolved                    = rawPaths.flatMap { relativePath =>
              if (SourceUtils.isGlobPattern(relativePath)) {
                SourceUtils.resolveLocalGlob(baseDir_, relativePath)
              } else {
                Seq(relativePath)
              }
            }
            SourceUtils.sequence(resolved.toSeq.map { relativePath =>
              Try {
                val relFile    = new File(basePath, relativePath)
                val relContent = new String(Files.readAllBytes(relFile.toPath), StandardCharsets.UTF_8)
                SourceUtils.parseEntityContent(relContent, s"git://${relFile.getPath}", allRes)
              }.getOrElse {
                Left(Json.obj("error" -> s"Cannot read file $relativePath from git repo"))
              }
            })
          case None      =>
            SourceUtils.parseEntityContent(rawContent, s"git://${target.getPath}", allRes)
        }
      } else {
        Left(Json.obj("error" -> s"Path not found in repo: $path")): Either[JsValue, Seq[RemoteEntity]]
      }
    }.getOrElse {
      Left(Json.obj("error" -> s"Error reading path $path from git repo")): Either[JsValue, Seq[RemoteEntity]]
    }
  }

  override def fetch(catalog: RemoteCatalog, args: JsObject)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]] = {
    val path   = catalog.sourceConfig.select("path").asOpt[String].getOrElse("").stripPrefix("/")
    val allRes = env.allResources.resources ++ env.adminExtensions.resources()

    cloneOrPull(catalog) match {
      case Left(err)  =>
        (Left(Json.obj("error" -> err)): Either[JsValue, Seq[RemoteEntity]]).vfuture
      case Right(dir) =>
        readLocalEntities(dir, path, allRes).vfuture
    }
  }
}
