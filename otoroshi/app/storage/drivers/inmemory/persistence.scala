package otoroshi.storage.drivers.inmemory

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Framing, Keep, Sink, Source}
import akka.util.ByteString
import com.google.common.base.Charsets
import otoroshi.env.Env
import otoroshi.utils.SchedulerHelper
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.SourceBody
import otoroshi.utils.http.Implicits._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.hashing.MurmurHash3
import otoroshi.utils.syntax.implicits._

sealed trait PersistenceKind
object PersistenceKind {
  case object HttpPersistenceKind extends PersistenceKind
  case object FilePersistenceKind extends PersistenceKind
  case object NoopPersistenceKind extends PersistenceKind
}

trait Persistence {
  def kind: PersistenceKind
  def message: String
  def onStart(): Future[Unit]
  def onStop(): Future[Unit]
}

class NoopPersistence(ds: InMemoryDataStores, env: Env) extends Persistence {

  override def kind: PersistenceKind = PersistenceKind.NoopPersistenceKind

  override def message: String = "Now using InMemory DataStores"

  override def onStart(): Future[Unit] = FastFuture.successful(())

  override def onStop(): Future[Unit] = FastFuture.successful(())
}

class FilePersistence(ds: InMemoryDataStores, env: Env) extends Persistence {

  private val logger         = Logger("otoroshi-file-db-datastores")
  private val dbPath: String = env.configuration.getOptionalWithFileSupport[String]("app.filedb.path").getOrElse("./filedb/state.ndjson")
  private val cancelRef      = new AtomicReference[Cancellable]()
  private val lastHash       = new AtomicReference[Int](0)

  override def kind: PersistenceKind = PersistenceKind.FilePersistenceKind

  override def message: String = s"Now using FileDb DataStores (loading '$dbPath')"

  override def onStart(): Future[Unit] = {
    import collection.JavaConverters._
    val file = new File(dbPath)
    if (!file.exists()) {
      logger.info(s"Creating FileDb file and directory ('$dbPath')")
      file.getParentFile.mkdirs()
      file.createNewFile()
    }
    readStateFromDisk(Files.readAllLines(file.toPath).asScala.toSeq)
    cancelRef.set(ds.actorSystem.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(SchedulerHelper.runnable {
      Await.result(writeStateToDisk()(ds.actorSystem.dispatcher, ds.materializer), 10.seconds)
    })(ds.actorSystem.dispatcher))
    FastFuture.successful(())
  }

  override def onStop(): Future[Unit] = {
    cancelRef.get().cancel()
    Await.result(writeStateToDisk()(ds.actorSystem.dispatcher, ds.materializer), 10.seconds)
    FastFuture.successful(())
  }

  private def readStateFromDisk(source: Seq[String]): Unit = {
    logger.debug("Reading state from disk ...")
    val store       = new ConcurrentHashMap[String, Any]()
    val expirations = new ConcurrentHashMap[String, Long]()
    source.foreach { raw =>
      val item  = Json.parse(raw)
      val key   = (item \ "k").as[String]
      val value = (item \ "v").as[JsValue]
      val what  = (item \ "w").as[String]
      val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
      fromJson(what, value).foreach(v => store.put(key, v))
      if (ttl > -1L) {
        expirations.put(key, ttl)
      }
    }
    ds.redis.swap(Memory(store, expirations))
  }

  private def fromJson(what: String, value: JsValue): Option[Any] = {

    import collection.JavaConverters._

    what match {
      case "counter" => Some(ByteString(value.as[Long].toString))
      case "string" => Some(ByteString(value.as[String]))
      case "set" => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "list" => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "hash" => {
        val map = new java.util.concurrent.ConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        Some(map)
      }
      case _ => None
    }
  }

  private def writeStateToDisk()(implicit ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val file = new File(dbPath)
    Source.futureSource[JsValue, Any](ds.fullNdJsonExport(100, 1, 4))
      .map { item =>
        Json.stringify(item) + "\n"
      }
      .runFold("")(_ + _)
      .map { content =>
        val hash = MurmurHash3.stringHash(content)
        if (hash != lastHash.get()) {
          logger.debug("Writing state to disk ...")
          Files.write(file.toPath, content.getBytes(Charsets.UTF_8))
          lastHash.set(hash)
        }
      }
  }
}

class HttpPersistence(ds: InMemoryDataStores, env: Env) extends Persistence {

  private val logger = Logger("otoroshi-http-db-datastores")

  private val stateUrl: String =
    env.configuration.getOptionalWithFileSupport[String]("app.httpdb.url").getOrElse("http://127.0.0.1:8888/worker-0/state.json")
  private val stateHeaders: Map[String, String] =
    env.configuration.getOptionalWithFileSupport[Map[String, String]]("app.httpdb.headers").getOrElse(Map.empty[String, String])
  private val stateTimeout: FiniteDuration =
    env.configuration.getOptionalWithFileSupport[Long]("app.httpdb.timeout").map(_.millis).getOrElse(10.seconds)
  private val statePoll: FiniteDuration =
    env.configuration.getOptionalWithFileSupport[Long]("app.httpdb.pollEvery").map(_.millis).getOrElse(10.seconds)
  private val cancelRef    = new AtomicReference[Cancellable]()
  private val lastHash     = new AtomicReference[Int](0)

  override def kind: PersistenceKind = PersistenceKind.HttpPersistenceKind

  override def message: String = s"Now using HttpDb DataStores (loading from '$stateUrl')"

  override def onStart(): Future[Unit] = {
    implicit val ec  = ds.actorSystem.dispatcher
    implicit val mat = ds.materializer
    readStateFromHttp().map { _ =>
      cancelRef.set(
        Source
          .tick(1.second, statePoll, ())
          .mapAsync(1)(_ => writeStateToHttp())
          .recover {
            case t => logger.error(s"Error while scheduling writeStateToHttp: $t")
          }
          .toMat(Sink.ignore)(Keep.left)
          .run()
      )
    }
  }

  override def onStop(): Future[Unit] = {
    cancelRef.get().cancel()
    writeStateToHttp()
  }

  private def readStateFromHttp(): Future[Unit] = {
    logger.debug("Reading state from http db ...")
    implicit val ec  = ds.actorSystem.dispatcher
    implicit val mat = ds.materializer
    val store        = new ConcurrentHashMap[String, Any]()
    val expirations  = new ConcurrentHashMap[String, Long]()
    val headers = stateHeaders.toSeq ++ Seq(
      "Accept" -> "application/x-ndjson"
    )
    env.Ws // no need for mtls here
      .url(stateUrl)
      .withRequestTimeout(stateTimeout)
      .withHttpHeaders(headers: _*)
      .withMethod("GET")
      .stream()
      .flatMap {
        case resp if resp.status != 200 =>
          logger.error("Error while reading data with http db, will retry later")
          resp.ignore()
          FastFuture.successful(())
        case resp if resp.status == 200 =>
          val source = resp.bodyAsSource.via(Framing.delimiter(ByteString("\n"), 32 * 1024 * 1024, false))
          source
            .runForeach { raw =>
              val item  = Json.parse(raw.utf8String)
              val key   = (item \ "k").as[String]
              val value = (item \ "v").as[JsValue]
              val what  = (item \ "w").as[String]
              val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
              fromJson(what, value).foreach(v => store.put(key, v))
              if (ttl > -1L) {
                expirations.put(key, ttl)
              }
            }
            .map { _ =>
              ds.redis.swap(Memory(store, expirations))
            }
      }
  }

  private def fromJson(what: String, value: JsValue): Option[Any] = {

    import collection.JavaConverters._

    what match {
      case "counter" => Some(ByteString(value.as[Long].toString))
      case "string" => Some(ByteString(value.as[String]))
      case "set" => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "list" => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "hash" => {
        val map = new java.util.concurrent.ConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        Some(map)
      }
      case _ => None
    }
  }

  private def writeStateToHttp(): Future[Unit] = {
    implicit val ec  = ds.actorSystem.dispatcher
    implicit val mat = ds.materializer
    val source = Source.futureSource[JsValue, Any](ds.fullNdJsonExport(100, 1, 4)).map { item =>
      ByteString(Json.stringify(item) + "\n")
    }
    ds.logger.debug("Writing state to http db ...")
    val headers = stateHeaders.toSeq ++ Seq(
      "Content-Type" -> "application/x-ndjson"
    )
    env.Ws // no need for mtls here
      .url(stateUrl)
      .withRequestTimeout(stateTimeout)
      .withHttpHeaders(headers: _*)
      .withMethod("POST")
      .withBody(SourceBody(source))
      .stream()
      .map {
        case resp if resp.status != 200 =>
          logger.error("Error while syncing data with http db, will retry later")
          resp.ignore()
        case resp if resp.status == 200 =>
          resp.ignore()
      }
  }
}