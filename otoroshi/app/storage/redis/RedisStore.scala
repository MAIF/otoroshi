package storage.redis

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import play.api.Logger
import play.api.libs.json._
import redis.{RedisClientMasterSlaves, RedisCluster, RedisCommands}
import storage._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

trait RedisStore[T] extends BasicStore[T] {
  def fmt: Format[T]
  private lazy val name                          = this.getClass.getSimpleName.replace("$", "")
  def _findAllCached(implicit env: Env): Boolean = env.useCache
  def _redis(implicit env: Env): RedisClientMasterSlaves
  def reader: Reads[T]          = fmt
  def writer: Writes[T]         = fmt
  def toJson(value: T): JsValue = writer.writes(value)
  def fromJsons(value: JsValue): T =
    try {
      reader.reads(value).get
    } catch {
      case e: Throwable => {
        Logger("otoroshi-redis-store").error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[T] = reader.reads(value)

  private def findKeysWithScan(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] = {
    implicit val mat = env.otoroshiMaterializer
    Source
      .unfoldAsync(0) { cursor =>
        _redis
          .scan(cursor, matchGlob = Some(pattern))
          .fast
          .map { curs =>
            if (cursor == -1) {
              None
            } else if (curs.index == 0) {
              Some(-1, curs.data)
            } else {
              Some(curs.index, curs.data)
            }
          }
      }
      .mapConcat(_.toList)
      .runWith(Sink.seq[String])
  }

  private def findKeysWithKeys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] =
    _redis.keys(pattern)

  private def findKeys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] =
    if (env.useRedisScan) {
      findKeysWithScan(pattern)
    } else {
      findKeysWithKeys(pattern)
    }

  private val findAllCache     = new java.util.concurrent.atomic.AtomicReference[Seq[T]](null)
  private val lastFindAllCache = new java.util.concurrent.atomic.AtomicLong(0L)

  def clearFromCache(id: String)(implicit env: Env): Unit = {
    if (_findAllCached) {
      val values = findAllCache.get
      if (values != null) {
        findAllCache.set(values.filterNot(s => extractId(s) == id))
      }
    }
  }

  def clearCache(id: String)(implicit env: Env): Unit = {
    if (_findAllCached) {
      findAllCache.set(null)
    }
  }

  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = {
    val cachekey = s"$name.findAll()"

    def actualFindAll() =
      findKeys(key("*").key).fast
        .flatMap(
          keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else _redis.mget(keys: _*)
        )
        .map(
          seq =>
            seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
              case JsSuccess(i, _) => i
          }
        )

    if (_findAllCached) {
      val time = System.currentTimeMillis
      val ref  = findAllCache.get()
      if (ref == null) {
        lastFindAllCache.set(time)
        actualFindAll().andThen {
          case Success(services) => findAllCache.set(services)
        }
      } else {
        if (force || (lastFindAllCache.get() + 2000) < time) {
          lastFindAllCache.set(time)
          actualFindAll().andThen {
            case Success(services) => findAllCache.set(services)
          }
        } else if ((lastFindAllCache.get() + 1000) < time) {
          lastFindAllCache.set(time)
          actualFindAll().andThen {
            case Success(services) => findAllCache.set(services)
          }
          FastFuture.successful(ref)
        } else {
          FastFuture.successful(ref)
        }
      }
    } else {
      actualFindAll()
      // Redis.showSlowCommandWarning(cachekey)
      // findKeys(key("*").key)
      //   .flatMap(keys => if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]]) else _redis.mget(keys: _*))
      //   .map(
      //     seq =>
      //       seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
      //         case JsSuccess(i, _) => i
      //     }
      //   )
    }
  }
  def findAllById(ids: Seq[String], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = ids match {
    case keys if keys.isEmpty => FastFuture.successful(Seq.empty[T])
    case keys if _findAllCached && findAllCache.get() != null => {
      // TODO: was true, but high impact on perfs, so ...
      findAll(force) // TODO : update findAllCache ??? FIXME ???
      FastFuture.successful(findAllCache.get().filter(s => keys.contains(extractId(s))))
    }
    case keys => {
      _redis.mget(keys.map(keyStr): _*).fast.map { values: Seq[Option[ByteString]] =>
        values.flatMap { opt =>
          opt.flatMap(bs => fromJsonSafe(Json.parse(bs.utf8String)).asOpt)
        }
      }
    }
  }
  def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    findKeys(key("*").key).flatMap { keys =>
      _redis.del(keys: _*)
    }
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]] =
    _redis.get(key(id).key).fast.map(_.flatMap(v => fromJsonSafe(Json.parse(v.utf8String)).asOpt))
  def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    _redis.del(key(id).key).fast.map(_ > 0)
  def delete(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = delete(extractId(value))
  def set(value: T, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    _redis.set(key(extractId(value)).key,
               Json.stringify(toJson(value)),
               pxMilliseconds = pxMilliseconds.map(d => d.toMillis))
  def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = _redis.exists(key(id).key)
  def exists(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]   = exists(extractId(value))

  // Streamed
  def streamedFind(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Source[T, NotUsed] = {
    val position = (page - 1) * pageSize
    Source
      .fromFuture(
        findKeys(key("*").key)
      )
      .mapConcat(_.toList)
      .grouped(fetchSize)
      .mapAsync(1) {
        case keys if keys.isEmpty => FastFuture.successful(Seq.empty[Option[ByteString]])
        case keys                 => _redis.mget(keys: _*)
      }
      .map { items =>
        items
          .filter(_.isDefined)
          .map(_.get)
          .map(v => fromJsonSafe(Json.parse(v.utf8String)))
          .collect { case JsSuccess(i, _) => i }
      }
      .mapConcat(_.toList)
      .drop(position)
      .take(pageSize)
  }

  def streamedFindAndMat(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Future[Seq[T]] =
    streamedFind(predicate, fetchSize, page, pageSize).runWith(Sink.seq[T])
}

object Redis {
  lazy val logger = Logger("otoroshi-redis")
  def showSlowCommandWarning(command: String) =
    logger.debug(
      s"$command can be very slow as it's performing in n calls to redis for n keys. You might not want to use $command for good performances"
    )
}
