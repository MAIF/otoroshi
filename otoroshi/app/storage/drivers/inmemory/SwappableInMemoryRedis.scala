package otoroshi.storage.drivers.inmemory

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.storage._
import otoroshi.utils.SchedulerHelper
import play.api.Logger
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

class Memory(
    val store: ConcurrentHashMap[String, Any],
    val expirations: ConcurrentHashMap[String, Long]
)

object Memory {
  def apply(store: ConcurrentHashMap[String, Any], expirations: ConcurrentHashMap[String, Long]) =
    new Memory(store, expirations)
}

object SwappableInMemoryRedis {
  lazy val logger = Logger("otoroshi-atomic-in-memory-datastore")
}

class SwappableInMemoryRedis(_optimized: Boolean, env: Env, actorSystem: ActorSystem)
    extends RedisLike
    with OptimizedRedisLike {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.concurrent.duration._

  val patterns: ConcurrentHashMap[String, Pattern] = new ConcurrentHashMap[String, Pattern]()

  private lazy val _storeHolder = new AtomicReference[Memory](
    Memory(
      store = new ConcurrentHashMap[String, Any],
      expirations = new ConcurrentHashMap[String, Long]
    )
  )

  @inline private def store: ConcurrentHashMap[String, Any]        = _storeHolder.get().store
  @inline private def expirations: ConcurrentHashMap[String, Long] = _storeHolder.get().expirations

  private val cancel = actorSystem.scheduler.scheduleAtFixedRate(0.millis, 100.millis)(SchedulerHelper.runnable {
    try {
      val time = System.currentTimeMillis()
      expirations.entrySet().asScala.foreach { entry =>
        if (entry.getValue < time) {
          store.remove(entry.getKey)
          expirations.remove(entry.getKey)
        }
      }
    } catch {
      case e: Throwable => SwappableInMemoryRedis.logger.error(s"Error while applying expiration", e)
    }
    ()
  })

  def swap(_memory: Memory): Unit = {
    val oldSize = store.keySet.size
    val memory = {
      val newStore = new ConcurrentHashMap[String, Any]()
      _memory.store.keySet.asScala
        .toSeq
        .filterNot(key => Cluster.filteredKey(key, env))
        .map { k =>
          newStore.put(k, _memory.store.get(k))
          k
        }
      Memory(newStore, _memory.expirations)
    }
    //env.metrics.withTimer("memory-swap", true) {
      _storeHolder.updateAndGet(_ => memory)
    //}
    val newSize = store.keySet.size
    SwappableInMemoryRedis.logger.debug(
      s"[${env.clusterConfig.mode.name}] Swapping store instance now ! ($oldSize / $newSize)"
    )
  }

  override def stop(): Unit =
    cancel.cancel()

  override def flushall(): Future[Boolean] = {
    store.clear()
    expirations.clear()
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // optimized stuff
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override val optimized: Boolean = _optimized

  override def findAllOptimized(kind: String, kindKey: String): Future[Seq[JsValue]] = {
    FastFuture.successful(
      store.asScala.toSeq.collect {
        case (key, value) if key.startsWith(kindKey) && value.isInstanceOf[ByteString] =>
          Json.parse(value.asInstanceOf[ByteString].utf8String)
      }
    )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def rawGet(key: String): Future[Option[Any]] = {
    val value = Option(store.get(key))
    FastFuture.successful(value)
  }

  override def get(key: String): Future[Option[ByteString]] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[ByteString])
    FastFuture.successful(value)
  }

  override def set(
      key: String,
      value: String,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] = {
    store.put(key, value)
    if (exSeconds.isDefined) {
      expire(key, exSeconds.get.toInt)
    }
    if (pxMilliseconds.isDefined) {
      pexpire(key, pxMilliseconds.get)
    }
    FastFuture.successful(true)
  }

  override def del(keys: String*): Future[Long] = {
    val value = keys
      .map { k =>
        store.remove(k)
        1L
      }
      .foldLeft(0L)((a, b) => a + b)
    FastFuture.successful(value)
  }

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] = {
    val value: Long    = Option(store.get(key)).map(_.asInstanceOf[ByteString]).map(_.utf8String.toLong).getOrElse(0L)
    val newValue: Long = value + increment
    store.put(key, ByteString(newValue.toString))
    FastFuture.successful(newValue)
  }

  override def exists(key: String): Future[Boolean] = FastFuture.successful(store.containsKey(key))

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    FastFuture.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    FastFuture.successful(
      store
        .keySet()
        .asScala
        .filter { k =>
          pat.matcher(k).find
        }
        .toSeq
    )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = {
    val hash  = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    val value = hash
      .keySet()
      .asScala
      .filter(k => fields.contains(k))
      .map(k => {
        hash.remove(k)
        1L
      })
      .foldLeft(0L)(_ + _)
    FastFuture.successful(value)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    FastFuture.successful(hash.asScala.toMap)
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    hash.put(field, value)
    store.put(key, hash)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySeq(): java.util.List[ByteString] =
    new java.util.concurrent.CopyOnWriteArrayList[ByteString]

  override def llen(key: String): Future[Long] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[Seq[ByteString]]).getOrElse(Seq.empty[ByteString]).size.toLong
    FastFuture.successful(value)
  }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq = store.get(key).asInstanceOf[java.util.List[ByteString]]
    seq.addAll(0, values.asJava)
    FastFuture.successful(values.size.toLong)
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    val seq    = Option(store.get(key)).map(_.asInstanceOf[java.util.List[ByteString]]).getOrElse(emptySeq())
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt)
    FastFuture.successful(result)
  }

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq    = store.get(key).asInstanceOf[java.util.List[ByteString]]
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt).asJava
    store.put(key, new java.util.concurrent.CopyOnWriteArrayList[ByteString](result))
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
    pttl(key).map(t => Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + (seconds * 1000L))
    FastFuture.successful(true)
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + milliseconds)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySet(): java.util.Set[ByteString] =
    new java.util.concurrent.CopyOnWriteArraySet[ByteString]

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    seq.addAll(members.asJava)
    FastFuture.successful(members.size.toLong)
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.contains(member))
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.asScala.toSeq)
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq    = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    val newSeq = seq.asScala.filterNot(b => members.contains(b)).asJava
    // seq.retainAll(newSeq.asJava)
    store.put(key, new java.util.concurrent.CopyOnWriteArraySet[ByteString](newSeq))
    FastFuture.successful(members.size.toLong)
  }

  override def scard(key: String): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    FastFuture.successful(seq.size.toLong)
  }

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)
}
