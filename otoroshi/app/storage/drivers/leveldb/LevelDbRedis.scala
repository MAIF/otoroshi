package otoroshi.storage.drivers.leveldb

import java.io.File
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import otoroshi.storage.{DataStoreHealth, Healthy, RedisLike}
import otoroshi.utils.SchedulerHelper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@deprecated(message = "Use FileDb instead", since = "1.5.0")
class LevelDbRedis(actorSystem: ActorSystem, dbPath: String) extends RedisLike {

  import actorSystem.dispatcher
  import org.iq80.leveldb._
  import org.iq80.leveldb.impl.Iq80DBFactory._

  import collection.JavaConverters._
  import scala.concurrent.duration._

  private val options     = new Options().createIfMissing(true)
  private val db          = factory.open(new File(dbPath), options)
  private val expirations = new ConcurrentHashMap[String, Long]()
  private val patterns    = new ConcurrentHashMap[String, Pattern]()

  private val cancel = actorSystem.scheduler.scheduleAtFixedRate(0.millis, 10.millis)(SchedulerHelper.runnable {
    val time = System.currentTimeMillis()
    expirations.entrySet().asScala.foreach { entry =>
      if (entry.getValue < time) {
        db.delete(bytes(entry.getKey))
        expirations.remove(entry.getKey)
      }
    }

    ()
  })

  private def getAllKeys(): Seq[String] = {
    var keys     = Seq.empty[String]
    val iterator = db.iterator()
    iterator.seekToFirst()
    while (iterator.hasNext) {
      val key = asString(iterator.peekNext.getKey)
      keys = keys :+ key
      iterator.next
    }
    keys
  }

  private def getValueAt(key: String): Option[String] =
    Try(db.get(bytes(key))).toOption.flatMap(s => Option(asString(s)))

  override def stop(): Unit = {
    cancel.cancel()
    db.close()
  }

  override def flushall(): Future[Boolean] = {
    getAllKeys().foreach(k => db.delete(bytes(k)))
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def rawGet(key: String): Future[Option[Any]] = Future.successful {
    getValueAt(key).map(ByteString.apply)
  }

  override def get(key: String): Future[Option[ByteString]] = Future.successful {
    getValueAt(key).map(ByteString.apply)
  }

  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    db.put(bytes(key), value.toArray[Byte])
    if (exSeconds.isDefined) {
      expire(key, exSeconds.get.toInt)
    }
    if (pxMilliseconds.isDefined) {
      pexpire(key, pxMilliseconds.get)
    }
    FastFuture.successful(true)
  }

  override def del(keys: String*): Future[Long] =
    Future.successful {
      keys
        .map { k =>
          db.delete(bytes(k))
          1L
        }
        .foldLeft(0L)((a, b) => a + b)
    }

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] = {
    val value    = getValueAt(key).map(_.toLong).getOrElse(0L)
    val newValue = value + increment
    db.put(bytes(key), bytes(newValue.toString))
    FastFuture.successful(newValue)
  }

  override def exists(key: String): Future[Boolean] = FastFuture.successful(getValueAt(key).isDefined)

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    Future.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    FastFuture.successful(getAllKeys().filter { k =>
      pat.matcher(k).find
    })
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getMapAt(key: String): Map[String, ByteString] =
    getValueAt(key)
      .map(_.split(";;;").map { v =>
        val parts = v.split("<#>")
        (parts.head, ByteString(parts.last))
      }.toMap)
      .getOrElse(Map.empty[String, ByteString])

  private def setMapAt(key: String, set: Map[String, ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(t => s"${t._1}<#>${t._2.utf8String}").mkString(";;;")))

  override def hdel(key: String, fields: String*): Future[Long] = {
    val hash    = getMapAt(key)
    val newHash = hash.filterNot(t => fields.contains(t._1))
    setMapAt(key, newHash)
    FastFuture.successful(fields.size)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    val hash = getMapAt(key)
    FastFuture.successful(hash)
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash    = getMapAt(key)
    val newHash = hash + ((field, value))
    setMapAt(key, newHash)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getListAt(key: String): Seq[ByteString] =
    getValueAt(key).map(_.split(";;;").toSeq.map(ByteString.apply)).getOrElse(Seq.empty[ByteString])

  private def setListAt(key: String, set: Seq[ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(_.utf8String).mkString(";;;")))

  override def llen(key: String): Future[Long] =
    Future.successful {
      getListAt(key).size
    }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    val seq    = getListAt(key)
    val newSeq = values ++ seq
    setListAt(key, newSeq)
    FastFuture.successful(values.size)
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    val seq    = getListAt(key)
    val result = seq.slice(start.toInt, stop.toInt - start.toInt)
    FastFuture.successful(result)
  }

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    val seq    = getListAt(key)
    val result = seq.slice(start.toInt, stop.toInt - start.toInt)
    setListAt(key, result)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] =
    FastFuture.successful(
      Option(expirations.get(key))
        .map(e => {
          val ttlValue = e - System.currentTimeMillis()
          if (ttlValue < 0) -1L else ttlValue
        })
        .getOrElse(-1L)
    )

  override def ttl(key: String): Future[Long] =
    pttl(key).fast.map(t => Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + (seconds * 1000L))
    FastFuture.successful(true)
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + milliseconds)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getSetAt(key: String): Set[ByteString] =
    getValueAt(key)
      .map { set =>
        if (set.contains(";;;")) {
          set.split(";;;").toSet.map((s: String) => ByteString(s))
        } else {
          set.split(";;>").toSet.map((s: String) => ByteString(s))
        }
      }
      .getOrElse(Set.empty[ByteString])

  private def setSetAt(key: String, set: Set[ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(_.utf8String).mkString(";;>")))

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    val seq    = getSetAt(key)
    val newSeq = seq ++ members
    setSetAt(key, newSeq)
    FastFuture.successful(members.size)
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    val seq = getSetAt(key)
    FastFuture.successful(seq.contains(member))
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    val seq = getSetAt(key)
    FastFuture.successful(seq.toSeq)
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    val seq    = getSetAt(key)
    val newSeq = seq.filterNot(b => members.contains(b))
    setSetAt(key, newSeq)
    FastFuture.successful(members.size)
  }

  override def scard(key: String): Future[Long] = {
    val seq = getSetAt(key)
    FastFuture.successful(seq.size.toLong)
  }

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)
}
