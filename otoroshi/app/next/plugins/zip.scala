package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.enumerationAsScalaIteratorConverter
import scala.util._

case class ZipFileBackendConfig(
    url: String,
    headers: Map[String, String],
    dir: String,
    prefix: Option[String],
    ttl: Long
) extends NgPluginConfig {
  def json: JsValue = ZipFileBackendConfig.format.writes(this)
}

object ZipFileBackendConfig {
  val default = ZipFileBackendConfig(
    "https://github.com/MAIF/otoroshi/releases/download/16.11.2/otoroshi-manual-16.11.2.zip",
    Map.empty,
    "./zips",
    None,
    1.hour.toMillis
  )
  val format  = new Format[ZipFileBackendConfig] {
    override def writes(o: ZipFileBackendConfig): JsValue = Json.obj(
      "url"     -> o.url,
      "headers" -> o.headers,
      "dir"     -> o.dir,
      "prefix"  -> o.prefix,
      "ttl"     -> o.ttl
    )
    override def reads(json: JsValue): JsResult[ZipFileBackendConfig] = {
      Try {
        ZipFileBackendConfig(
          url = json.select("url").asString,
          headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
          dir = json.select("dir").asOpt[String].getOrElse(ZipFileBackendConfig.default.dir),
          prefix = json.select("prefix").asOpt[String].filter(_.nonEmpty),
          ttl = json.select("ttl").asOpt[Long].getOrElse(ZipFileBackendConfig.default.ttl)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class ZipFileBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = true
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(ZipFileBackendConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Zip file backend"
  override def description: Option[String]                 = "Serves content from a zip file".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)

  private val fileCache = new TrieMap[String, (Long, Promise[String])]()

  private def getZipFile(
      config: ZipFileBackendConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, ZipFile]] = fileCache.synchronized {
    val url = config.url
    if (url.startsWith("file://")) {
      Right(new ZipFile(url.replace("file://", ""))).vfuture
    } else {
      val dir      = new File(config.dir)
      if (!dir.exists()) {
        dir.mkdirs()
      }
      val filename = s"${config.dir}/${url.sha256}.zip"
      fileCache.get(filename) match {
        case Some((at, fu)) if at + config.ttl < System.currentTimeMillis() => {
          new File(filename).delete()
          fileCache.remove(filename)
          getZipFile(config)
        }
        case Some((_, fu))                                                  => fu.future.map(s => Right(new ZipFile(s)))
        case None                                                           => {
          val file = new File(filename)
          if (!file.exists()) {
            fileCache.put(filename, (System.currentTimeMillis(), Promise[String]()))
            env.Ws.url(url).withFollowRedirects(true).withRequestTimeout(30.seconds).get().map { resp =>
              if (resp.status == 200) {
                Files.write(file.toPath, resp.bodyAsBytes.toArray[Byte])
                fileCache.get(filename).foreach(_._2.trySuccess(filename))
                Right(new ZipFile(filename))
              } else {
                fileCache.remove(filename)
                println(s"not found: ${url}")
                Left(s"url not found: ${resp.status} - ${resp.headers} - ${resp.body}")
              }
            }
          } else {
            fileCache.put(filename, (System.currentTimeMillis(), Promise[String]()))
            fileCache.get(filename).foreach(_._2.trySuccess(filename))
            Right(new ZipFile(filename)).vfuture
          }
        }
      }
    }
  }

  private def atPath(_path: String, zip: ZipFile, config: ZipFileBackendConfig)(implicit
      env: Env
  ): Option[(String, Source[ByteString, _])] = {
    var path =
      if (_path == "/") "index.html"
      else {
        if (_path.startsWith("/")) {
          _path.substring(1)
        } else {
          _path
        }
      }
    if (path.endsWith("/")) {
      path = path + "index.html"
    }
    if (config.prefix.isDefined) {
      path = config.prefix.get + path
    }
    if (path.startsWith("/")) {
      path = path.substring(1)
    }
    Option(zip.getEntry(path)).flatMap { entry =>
      if (entry.isDirectory) {
        None
      } else {
        val uriPath  = Uri.apply(path).path
        val filename = if (uriPath.length < 2) {
          uriPath.toString()
        } else {
          uriPath.tail.reverse.head.toString
        }
        if (filename.contains(".")) {
          val ext              = filename.split("\\.").toSeq.last
          val mimeType: String = env.devMimetypes.getOrElse(ext, "text/plain")
          Some((mimeType, StreamConverters.fromInputStream(() => zip.getInputStream(entry))))
        } else {
          Some(("text/html", StreamConverters.fromInputStream(() => zip.getInputStream(entry))))
        }
      }
    }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(ZipFileBackendConfig.format).getOrElse(ZipFileBackendConfig.default)
    getZipFile(
      config.copy(url =
        GlobalExpressionLanguage.apply(
          value = config.url,
          req = ctx.rawRequest.some,
          service = None,
          route = ctx.route.some,
          apiKey = ctx.apikey,
          user = ctx.user,
          context = Map.empty,
          attrs = ctx.attrs,
          env = env
        )
      )
    ).map {
      case Left(msg)      =>
        Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> msg))))
      case Right(zipfile) => {
        val path = ctx.request.path
        atPath(path, zipfile, config) match {
          case Some((contentType, body)) => {
            Right(
              BackendCallResponse(
                NgPluginHttpResponse(
                  200,
                  Map("Content-Type" -> contentType, "Transfer-Encoding" -> "chunked"),
                  Seq.empty,
                  body
                ),
                None
              )
            )
          }
          case None                      => {
            val body = "<h1>File not found !</h1>".byteString
            Right(
              BackendCallResponse(
                NgPluginHttpResponse(
                  404,
                  Map("Content-Type" -> "text/html", "Content-Length" -> body.size.toString),
                  Seq.empty,
                  body.chunks(32 * 8)
                ),
                None
              )
            )
          }
        }
      }
    }
  }
}

case class ZipBombBackendConfig(
    predicates: Seq[JsonPathValidator] = Seq.empty,
    or: Boolean = false,
    size: String = "10G",
    status: Option[Int] = None,
    contentType: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = ZipBombBackendConfig.format.writes(this)
}

object ZipBombBackendConfig {
  val configFlow: Seq[String]        = Seq("or", "size", "status", "content_type", "predicates")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "predicates"   -> Json.parse("""{
          |  "label": "Predicates",
          |  "type": "object",
          |  "array": true,
          |  "format": "form",
          |  "schema": {
          |    "path": {
          |      "label": "path",
          |      "type": "string",
          |      "props": {
          |        "subTitle": "Example: $.apikey.metadata.foo"
          |      }
          |    },
          |    "value": {
          |      "type": "code",
          |      "help": "Example: Contains(bar)",
          |      "props": {
          |        "label": "Value",
          |        "type": "json",
          |        "editorOnly": true
          |      }
          |    }
          |  },
          |  "flow": [
          |    "path",
          |    "value"
          |  ]
          |}""".stripMargin),
      "or"           -> Json.obj("type" -> "bool", "label" -> "Use OR operator"),
      "status"       -> Json.obj("type" -> "number", "label" -> "Response status code"),
      "content_type" -> Json.obj("type" -> "string", "label" -> "Response content type"),
      "size"         -> Json.obj(
        "type"  -> "select",
        "label" -> s"Size",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "10M", "value"  -> "10M"),
            Json.obj("label" -> "100M", "value" -> "100M"),
            Json.obj("label" -> "1G", "value"   -> "1G"),
            Json.obj("label" -> "5G", "value"   -> "5G"),
            Json.obj("label" -> "10G", "value"  -> "10G")
          )
        )
      )
    )
  )
  val format                         = new Format[ZipBombBackendConfig] {
    override def writes(o: ZipBombBackendConfig): JsValue             = Json.obj(
      "predicates"   -> JsArray(o.predicates.map(_.json)),
      "or"           -> o.or,
      "size"         -> o.size,
      "status"       -> o.status.map(_.json).getOrElse(JsNull).asValue,
      "content_type" -> o.contentType.map(_.json).getOrElse(JsNull).asValue
    )
    override def reads(json: JsValue): JsResult[ZipBombBackendConfig] = Try {
      ZipBombBackendConfig(
        size = json.select("size").asOptString.getOrElse("10G"),
        or = json.select("or").asOptBoolean.getOrElse(false),
        status = json.select("status").asOptInt,
        contentType = json.select("content_type").asOptString,
        predicates = (json \ "predicates")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

object ZipBombBackend {
  val zipCache = new TrieMap[String, ByteString]()
}

/**
 * create zip bomb: dd if=/dev/zero bs=1G count=10 | gzip -c > 10G.gz
 * testing: curl http://zipbomb.oto.tools:9999\?foo\=bar --compressed > zb.res
 */
class ZipBombBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = true
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Zip Bomb backend"
  override def description: Option[String]                 = "This plugin returns zip bomb responses based on predicates".some
  override def defaultConfigObject: Option[NgPluginConfig] = ZipBombBackendConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = ZipBombBackendConfig.configFlow
  override def configSchema: Option[JsObject]    = ZipBombBackendConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config         = ctx
      .cachedConfig(internalName)(ZipBombBackendConfig.format)
      .getOrElse(ZipBombBackendConfig())
    val acceptEncoding = ctx.request.headers.getIgnoreCase("Accept-Encoding").getOrElse("")
    val gzipAccepted   = acceptEncoding.contains("gzip") || acceptEncoding == "*"
    if (gzipAccepted) {
      val json          = ctx.json
      val shouldTrigger = if (config.or) {
        config.predicates.exists(_.validate(json))
      } else {
        config.predicates.forall(_.validate(json))
      }
      if (shouldTrigger) {
        val zipPath = config.size.toLowerCase() match {
          case "10m"  => "/zips/10M.gz"
          case "100m" => "/zips/100M.gz"
          case "1g"   => "/zips/1G.gz"
          case "5g"   => "/zips/5G.gz"
          case _      => "/zips/10G.gz"
        }
        val bytes   = ZipBombBackend.zipCache
          .getOrElseUpdate(
            zipPath, {
              val zipFile = env.environment.resourceAsStream(zipPath).get
              ByteString(zipFile.readAllBytes())
            }
          )
          .chunks(64 * 1024)
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(
            Results
              .Status(config.status.getOrElse(200))
              .streamed(bytes, None, config.contentType.getOrElse("text/html").some)
              .withHeaders("Content-Encoding" -> "gzip")
          ),
          None
        ).rightf
      } else {
        delegates()
      }
    } else {
      delegates()
    }
  }
}
