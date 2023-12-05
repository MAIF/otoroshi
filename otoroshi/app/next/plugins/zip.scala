package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._

case class ZipFileBackendConfig(url: String, dir: String, prefix: Option[String], ttl: Long) extends NgPluginConfig {
  def json: JsValue = ZipFileBackendConfig.format.writes(this)
}
object ZipFileBackendConfig {
  val default = ZipFileBackendConfig("https://github.com/MAIF/otoroshi/releases/download/16.11.2/otoroshi-manual-16.11.2.zip", "./zips", None, 1.hour.toMillis)
  val format = new Format[ZipFileBackendConfig] {
    override def writes(o: ZipFileBackendConfig): JsValue = Json.obj(
      "url" -> o.url,
      "dir" -> o.dir,
      "prefix" -> o.prefix,
      "ttl" -> o.ttl,
    )
    override def reads(json: JsValue): JsResult[ZipFileBackendConfig] = {
      Try {
        ZipFileBackendConfig(
          url = json.select("url").asString,
          dir = json.select("dir").asOpt[String].getOrElse(ZipFileBackendConfig.default.dir),
          prefix = json.select("prefix").asOpt[String].filter(_.nonEmpty),
          ttl = json.select("ttl").asOpt[Long].getOrElse(ZipFileBackendConfig.default.ttl),
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class ZipFileBackend extends NgBackendCall {

  override def useDelegates: Boolean = true
  override def multiInstance: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(ZipFileBackendConfig.default)
  override def core: Boolean = false
  override def name: String = "Zip file backend"
  override def description: Option[String] = "Serves content from a zip file".some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  private val fileCache = new TrieMap[String, (Long, Promise[String])]()

  private def getZipFile(config: ZipFileBackendConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[String, ZipFile]] = fileCache.synchronized {
    val url = config.url
    if (url.startsWith("file://")) {
      Right(new ZipFile(url.replace("file://", ""))).vfuture
    } else {
      val dir = new File(config.dir)
      if (!dir.exists()) {
        dir.mkdirs()
      }
      val filename = s"${config.dir}/${url.sha256}.zip"
      fileCache.get(filename) match {
        case Some((at, fu)) if (at + config.ttl < System.currentTimeMillis()) => {
          new File(filename).delete()
          getZipFile(config)
        }
        case Some((_, fu)) => fu.future.map(s => Right(new ZipFile(s)))
        case None => {
          val file = new File(filename)
          if (!file.exists()) {
            fileCache.put(filename, (System.currentTimeMillis(), Promise[String]()))
            env.Ws.url(url).get().map { resp =>
              if (resp.status == 200) {
                Files.write(file.toPath, resp.bodyAsBytes.toArray[Byte])
                fileCache.get(filename).foreach(_._2.trySuccess(filename))
                Right(new ZipFile(filename))
              } else {
                fileCache.remove(filename)
                Left(s"url not found: ${resp.status} - ${resp.headers} - ${resp.body}")
              }
            }
          } else {
            fileCache.get(filename).foreach(_._2.trySuccess(filename))
            Right(new ZipFile(filename)).vfuture
          }
        }
      }
    }
  }

  private def atPath(_path: String, zip: ZipFile, config: ZipFileBackendConfig)(implicit env: Env): Option[(String, Source[ByteString, _])] = {
    var path = if (_path == "/") "index.html" else {
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
    Option(zip.getEntry(path)).flatMap { entry =>
      if (entry.isDirectory) {
        None
      } else {
        val filename = Uri.apply(path).path.tail.reverse.head.toString
        if (filename.contains(".")) {
          val ext = filename.split("\\.").toSeq.last
          val mimeType: String = env.devMimetypes.getOrElse(ext, "text/plain")
          Some((mimeType, StreamConverters.fromInputStream(() => zip.getInputStream(entry))))
        } else {
          Some(("text/html", StreamConverters.fromInputStream(() => zip.getInputStream(entry))))
        }
      }
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(ZipFileBackendConfig.format).getOrElse(ZipFileBackendConfig.default)
    getZipFile(config).map {
      case Left(msg) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> msg))))
      case Right(zipfile) => {
        val path = ctx.request.path
        atPath(path, zipfile, config) match {
          case Some((contentType, body)) => {
            Right(BackendCallResponse(NgPluginHttpResponse(
              200,
              Map("Content-Type" -> contentType, "Transfer-Encoding" -> "chunked"),
              Seq.empty,
              body
            ), None))
          }
          case None => {
            val body = "<h1>File not found !</h1>".byteString
            Right(BackendCallResponse(NgPluginHttpResponse(
              404,
              Map("Content-Type" -> "text/html", "Content-Length" -> body.size.toString),
              Seq.empty,
              body.chunks(32 * 8)
            ), None))
          }
        }
      }
    }
  }
}
