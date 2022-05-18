package otoroshi.next.plugins

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.{Attributes, Materializer}
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType, S3Attributes, S3Settings}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class FileUtils(env: Env) {

  private lazy val mimetypes: Map[String, String] = env.configuration
    .betterGetOptional[String]("play.http.fileMimeTypes")
    .map { types =>
      types
        .split("\\n")
        .toSeq
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_.split("=").toSeq)
        .filter(_.size == 2)
        .map(v => (v.head, v.tail.head))
        .toMap
    }
    .getOrElse(Map.empty[String, String])

  def normalize(path: String, rootPath: String): String = {
    val filepath = Path.of(rootPath, path).normalize()
    val file = filepath.toFile
    if (file.isDirectory) {
      Path.of(rootPath, path, "index.html").normalize().toString
    } else {
      filepath.toString
    }
  }

  def contentType(file: String): String = {
    val filepath = Path.of(file).normalize().toString
    Option(com.google.common.io.Files.getFileExtension(filepath)).map(_.trim).filter(_.nonEmpty) match {
      case None => "application/octet-stream"
      case Some(ext) => mimetypes.getOrElse(ext, "application/octet-stream")
    }
  }
}

case class StaticBackendConfig(rootPath: String) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "root_path" -> rootPath
  )
}

object StaticBackendConfig {
  val format = new Format[StaticBackendConfig] {
    override def reads(json: JsValue): JsResult[StaticBackendConfig] = Try {
      StaticBackendConfig(
        rootPath = json.select("root_path").asString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: StaticBackendConfig): JsValue = o.json
  }
}

class StaticBackend extends NgBackendCall {

  private val fileCache = Scaffeine().maximumSize(100).expireAfterWrite(2.minutes).build[String, (String, ByteString)]
  private val fileUtilsRef = new AtomicReference[FileUtils]()

  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def name: String                                = "Static backend"
  override def description: Option[String]                 =
    "This plugin is able to serve a static folder with file content".some

  override def useDelegates: Boolean = true
  override def multiInstance: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = StaticBackendConfig("/tmp").some

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.method == "GET") {
      val fileUtils = Option(fileUtilsRef.get()).getOrElse {
        val fu = new FileUtils(env)
        fileUtilsRef.set(fu)
        fu
      }
      val config = ctx.cachedConfig(internalName)(StaticBackendConfig.format).getOrElse(StaticBackendConfig("/tmp"))
      val askedFilePath = ctx.request.path.replace("//", "")
      val filePath = fileUtils.normalize(askedFilePath, config.rootPath)
      fileCache.getIfPresent(filePath) match {
        case Some((contentType, content)) => bodyResponse(200, Map("Content-Type" -> contentType), Source(content.grouped(16 * 1024).toList)).vfuture
        case None => {
          val file = new File(filePath)
          if (!file.exists()) {
            bodyResponse(404, Map("Content-Type" -> "text/plain"), Source.single("resource not found".byteString)).vfuture
          } else {
            val content = ByteString(Files.readAllBytes(Path.of(filePath)))
            val contentType = fileUtils.contentType(filePath)
            fileCache.put(filePath, (contentType, content))
            bodyResponse(200, Map("Content-Type" -> contentType), Source(content.grouped(16 * 1024).toList)).vfuture
          }
        }
      }
    } else {
      bodyResponse(405, Map("Content-Type" -> "text/plain"), Source.single("method not allowed".byteString)).vfuture
    }
  }
}

case class S3BackendConfig(s3: S3Configuration) extends NgPluginConfig {
  def json: JsValue = Json.obj("s3" -> s3.json)
}

class S3Backend extends NgBackendCall {

  private val fileCache = Scaffeine().maximumSize(100).expireAfterWrite(2.minutes).build[String, (String, ByteString)]
  private val fileUtilsRef = new AtomicReference[FileUtils]()

  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def name: String                                = "Static backend"
  override def description: Option[String]                 =
    "This plugin is able to serve a static folder with file content".some

  override def useDelegates: Boolean = true
  override def multiInstance: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = S3Configuration.default.some

  private def s3ClientSettingsAttrs(conf: S3Configuration): Attributes = {
    val awsCredentials = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(conf.access, conf.secret)
    )
    val settings       = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(conf.region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(conf.endpoint)
    S3Attributes.settings(settings)
  }

  private def fileExists(key: String, config: S3Configuration)(implicit ec: ExecutionContext, mat: Materializer): Future[Boolean] = {
    S3.getObjectMetadata(config.bucket, key).withAttributes(s3ClientSettingsAttrs(config)).runWith(Sink.headOption).map(_.flatten).map {
      case None => false
      case Some(_) => true
    }
  }

  private def fileContent(key: String, config: S3Configuration)(implicit ec: ExecutionContext, mat: Materializer): Future[Option[(String, ByteString)]] = {
    S3.download(config.bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.headOption)
      .map(_.flatten)
      .flatMap { opt =>
        opt.map {
          case (source, om) => {
            source.runFold(ByteString.empty)(_ ++ _).map { content =>
              (om.contentType.getOrElse("application/octet-stream"), content).some
            }
          }
        }.getOrElse(None.vfuture)
      }
  }

  private def normalizeKey(key: String, config: S3Configuration)(implicit ec: ExecutionContext, mat: Materializer): Future[String] = {
    val keyWithIndex = s"$key/index.html"
    fileExists(key, config).flatMap {
      case true => key.vfuture
      case false => keyWithIndex.vfuture
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.method == "GET") {
      val config = ctx.cachedConfig(internalName)(S3Configuration.format).getOrElse(S3Configuration.default)
      val askedFilePath = ctx.request.path.replace("//", "")
      val key = s"${config.key}${askedFilePath}"
      val cacheKey =  s"${ctx.route.id}-${key}"
      normalizeKey(key, config).flatMap { filePath =>
        fileCache.getIfPresent(cacheKey) match {
          case Some((contentType, content)) => bodyResponse(200, Map("Content-Type" -> contentType), Source(content.grouped(16 * 1024).toList)).vfuture
          case None => {
            fileExists(filePath, config).flatMap {
              case false => bodyResponse(404, Map("Content-Type" -> "text/plain"), Source.single("resource not found".byteString)).vfuture
              case true => {
                fileContent(key, config).flatMap {
                  case None => bodyResponse(404, Map("Content-Type" -> "text/plain"), Source.single("resource not found".byteString)).vfuture
                  case Some((contentType, content)) => {
                    fileCache.put(cacheKey, (contentType, content))
                    bodyResponse(200, Map("Content-Type" -> contentType), Source(content.grouped(16 * 1024).toList)).vfuture
                  }
                }
              }
            }
          }
        }
      }
    } else {
      bodyResponse(405, Map("Content-Type" -> "text/plain"), Source.single("method not allowed".byteString)).vfuture
    }
  }
}