package otoroshi.storage.drivers.inmemory

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.storage._
import otoroshi.utils.SchedulerHelper
import otoroshi.utils.cache.types.{UnboundedConcurrentHashMap, UnboundedTrieMap}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger
import play.api.libs.json.{JsValue, Json}

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

sealed trait SwapStrategy {
  def name: String
}
object SwapStrategy       {
  case object Replace extends SwapStrategy { def name: String = "replace" }
  case object Merge   extends SwapStrategy { def name: String = "merge"   }
}

object ModernMemory {
  def apply(
      store: TrieMap[String, Any] = new UnboundedTrieMap[String, Any](),
      expirations: TrieMap[String, Long] = new UnboundedTrieMap[String, Long]()
  ): ModernMemory = new ModernMemory(store, expirations)
}

class ModernMemory(
    store: TrieMap[String, Any] = new UnboundedTrieMap[String, Any](),
    expirations: TrieMap[String, Long] = new UnboundedTrieMap[String, Long]()
) {
  def size: Int                                                              = store.size
  def get(key: String): Option[Any]                                          = store.get(key)
  def getTyped[A](key: String)(implicit c: ClassTag[A]): Option[A]           = store.get(key).map(_.asInstanceOf[A])
  def getTypedOrUpdate[A](key: String, up: => A)(implicit c: ClassTag[A]): A =
    store.getOrElseUpdate(key, up).asInstanceOf[A]
  def put(key: String, value: Any): Option[Any]                              = store.put(key, value)
  def putIfAbsent(key: String, value: Any): Option[Any]                      = store.putIfAbsent(key, value)
  def putAll(all: Map[String, Any]): Unit                                    = store.++=(all)
  def remove(key: String): Unit                                              = store.remove(key)
  def removeAll(keys: Seq[String]): Int = {
    store.--=(keys)
    keys.size
  }
  def keys: scala.collection.Set[String]                                     = store.keySet
  def entries: Seq[(String, Any)]                                            = store.toSeq
  def containsKey(key: String): Boolean                                      = store.contains(key)
  def clear(): Unit = {
    store.clear()
    expirations.clear()
  }
  def expirationContainsKey(key: String): Boolean                            = expirations.contains(key)
  def expirationKeys: scala.collection.Set[String]                           = expirations.keySet
  def expirationEntries: Seq[(String, Long)]                                 = expirations.toSeq
  def getExpiration(key: String): Option[Long]                               = expirations.get(key)
  def putExpiration(key: String, exp: Long): Option[Long]                    = expirations.put(key, exp)
  def putExpirations(all: Map[String, Long]): Unit                           = expirations.++=(all)
  def removeExpiration(key: String): Unit                                    = expirations.remove(key)
  def removeExpirations(keys: Seq[String]): Unit                             = expirations.--=(keys)
  def swap(
      nstore: scala.collection.Map[String, Any],
      nexpirations: scala.collection.Map[String, Long]
  ): ModernMemory = {
    store.++=(nstore).--=(store.keySet.diff(nstore.keySet))
    expirations.++=(nexpirations).--=(expirations.keySet.diff(nexpirations.keySet))
    this
  }
}

class Memory(
    val store: ConcurrentHashMap[String, Any],
    val expirations: ConcurrentHashMap[String, Long],
    val newStore: TrieMap[String, Any] = new UnboundedTrieMap[String, Any]()
)

object Memory {
  def apply(store: ConcurrentHashMap[String, Any], expirations: ConcurrentHashMap[String, Long]) =
    new Memory(store, expirations)
}

trait SwappableRedis {
  def swap(memory: Memory, strategy: SwapStrategy): Unit
}

object SwappableInMemoryRedis {
  lazy val logger = Logger("otoroshi-atomic-in-memory-datastore")
}

class SwappableInMemoryRedis(_optimized: Boolean, env: Env, actorSystem: ActorSystem)
    extends RedisLike
    with SwappableRedis
    with OptimizedRedisLike {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.concurrent.duration._

  val patterns: ConcurrentHashMap[String, Pattern] = new UnboundedConcurrentHashMap[String, Pattern]()

  private lazy val _storeHolder = new AtomicReference[Memory](
    Memory(
      store = new UnboundedConcurrentHashMap[String, Any],
      expirations = new UnboundedConcurrentHashMap[String, Long]
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

  def swap(_memory: Memory, strategy: SwapStrategy): Unit = {
    env.metrics.withTimer(s"memory-swap-${strategy.name}") {
      val oldSize = store.keySet.size
      _storeHolder.updateAndGet { oldMemory =>
        if (env.clusterConfig.mode.isWorker) {
          strategy match {
            case SwapStrategy.Replace => {
              val newStore = new UnboundedConcurrentHashMap[String, Any]()
              _memory.store.keySet.asScala
                .filterNot(key => Cluster.filteredKey(key, env))
                .map { k =>
                  newStore.put(k, _memory.store.get(k))
                  k
                }
              Memory(newStore, _memory.expirations)
            }
            case SwapStrategy.Merge   =>
              env.metrics.withTimer(s"memory-swap-merge-compute") {
                val newStore     = _memory.store.asScala
                val newKeys      = newStore.keySet
                val oldKeys      = store.keySet.asScala
                val keysToDelete = oldKeys.filterNot(k => Cluster.filteredKey(k, env)).diff(newKeys)
                keysToDelete.map(k => oldMemory.store.remove(k))
                newStore.filterNot(t => Cluster.filteredKey(t._1, env)).foreach { case (key, value) =>
                  oldMemory.store.put(key, value)
                }
                oldMemory
              }
          }
        } else {
          _memory
        }
      }
      val newSize = store.keySet.size
      if (SwappableInMemoryRedis.logger.isDebugEnabled)
        SwappableInMemoryRedis.logger.debug(
          s"[${env.clusterConfig.mode.name}] Swapping store instance now ! ($oldSize / $newSize)"
        )
    }
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
      new UnboundedConcurrentHashMap[String, ByteString]()
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
      new UnboundedConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    FastFuture.successful(hash.asScala.toMap)
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash = if (!store.containsKey(key)) {
      new UnboundedConcurrentHashMap[String, ByteString]()
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

  override def ttl(key: String): Future[Long] = {
    pttl(key).map {
      case -1L => -1L
      case t   => Duration(t, TimeUnit.MILLISECONDS).toSeconds
    }
  }

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

class ModernSwappableInMemoryRedis(_optimized: Boolean, env: Env, actorSystem: ActorSystem)
    extends RedisLike
    with SwappableRedis
    with OptimizedRedisLike {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.concurrent.duration._

  lazy val logger = Logger("otoroshi-datastores")

  val patterns: TrieMap[String, Pattern] = new UnboundedTrieMap[String, Pattern]()

  // private lazy val _storeHolder = new AtomicReference[ModernMemory](ModernMemory())

  //@inline private def memory: ModernMemory = _storeHolder.get()

  val memory = ModernMemory()

  private val cancel = actorSystem.scheduler.scheduleAtFixedRate(0.millis, 100.millis)(SchedulerHelper.runnable {
    try {
      val time = System.currentTimeMillis()
      memory.expirationEntries.foreach { case (key, value) =>
        if (value < time) {
          memory.remove(key)
          memory.removeExpiration(key)
        }
      }
    } catch {
      case e: Throwable => SwappableInMemoryRedis.logger.error(s"Error while applying expiration", e)
    }
    ()
  })

  def swap(memory: Memory, strategy: SwapStrategy): Unit = rawSwap(memory.store.asScala, memory.expirations.asScala)

  def rawSwap(nstore: scala.collection.Map[String, Any], nexpirations: scala.collection.Map[String, Long]): Unit = {
    env.metrics.withTimer(s"memory-swap-modern") {
      val oldSize = memory.size
      memory.swap(nstore, nexpirations)
      val newSize = memory.size
      if (SwappableInMemoryRedis.logger.isDebugEnabled)
        SwappableInMemoryRedis.logger.debug(
          s"[${env.clusterConfig.mode.name}] Swapping modern store instance now ! ($oldSize / $newSize)"
        )
    }
  }

  override def start(): Unit = {
    logger.info(s"using the modern implementation")
  }

  override def stop(): Unit =
    cancel.cancel()

  override def flushall(): Future[Boolean] = {
    memory.clear()
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // optimized stuff
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override val optimized: Boolean = _optimized

  override def findAllOptimized(kind: String, kindKey: String): Future[Seq[JsValue]] = {
    FastFuture.successful(
      memory.entries.collect {
        case (key, value) if key.startsWith(kindKey) && value.isInstanceOf[ByteString] =>
          Json.parse(value.asInstanceOf[ByteString].utf8String)
      }
    )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def rawGet(key: String): Future[Option[Any]] = memory.get(key).future

  override def get(key: String): Future[Option[ByteString]] = memory.getTyped[ByteString](key).future

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
    memory.put(key, value)
    if (exSeconds.isDefined) {
      expire(key, exSeconds.get.toInt)
    }
    if (pxMilliseconds.isDefined) {
      pexpire(key, pxMilliseconds.get)
    }
    true.future
  }

  override def del(keys: String*): Future[Long] = {
    memory.removeAll(keys).toLong.future
  }

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] = {
    val value: Long    = memory.getTyped[ByteString](key).map(_.utf8String.toLong).getOrElse(0L)
    val newValue: Long = value + increment
    memory.put(key, ByteString(newValue.toString))
    newValue.future
  }

  override def exists(key: String): Future[Boolean] = memory.containsKey(key).future

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = FastFuture.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.getOrElseUpdate(pattern, Pattern.compile(pattern.replaceAll("\\*", ".*")))
    memory.keys.filter(k => pat.matcher(k).find).toSeq.future
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = {
    val hash = memory.getTypedOrUpdate[TrieMap[String, ByteString]](key, new UnboundedTrieMap[String, ByteString]())
    hash.keySet
      .filter(k => fields.contains(k))
      .map(k => {
        hash.remove(k)
        1L
      })
      .foldLeft(0L)(_ + _)
      .future
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    val hash = memory.getTypedOrUpdate[TrieMap[String, ByteString]](key, new UnboundedTrieMap[String, ByteString]())
    hash.toMap.future
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash = memory.getTypedOrUpdate[TrieMap[String, ByteString]](key, new UnboundedTrieMap[String, ByteString]())
    hash.put(field, value)
    memory.put(key, hash)
    true.future
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  type MutableSeq[A] = scala.collection.mutable.MutableList[A]

  private def emptySeq(): MutableSeq[ByteString] = scala.collection.mutable.MutableList.empty[ByteString]

  override def llen(key: String): Future[Long] = {
    memory.getTypedOrUpdate[MutableSeq[ByteString]](key, emptySeq()).size.toLong.future
  }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    val seq: MutableSeq[ByteString] = memory.getTypedOrUpdate[MutableSeq[ByteString]](key, emptySeq())
    seq.++=(values)
    values.size.toLong.future
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    val seq: MutableSeq[ByteString] = memory.getTypedOrUpdate[MutableSeq[ByteString]](key, emptySeq())
    seq.slice(start.toInt, stop.toInt - start.toInt).future
  }

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    val seq: MutableSeq[ByteString] = memory.getTypedOrUpdate[MutableSeq[ByteString]](key, emptySeq())
    val result                      = seq.slice(start.toInt, stop.toInt - start.toInt)
    memory.put(key, result)
    true.future
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = {
    memory
      .getExpiration(key)
      .map { e =>
        val ttlValue: Long = e - System.currentTimeMillis()
        if (ttlValue < 0) -1L else ttlValue
      }
      .getOrElse(-1L)
      .future
  }

  override def ttl(key: String): Future[Long] = {
    pttl(key).map {
      case -1L => -1L
      case t   => Duration(t, TimeUnit.MILLISECONDS).toSeconds
    }
  }

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    memory.putExpiration(key, System.currentTimeMillis() + (seconds * 1000L))
    true.future
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    memory.putExpiration(key, System.currentTimeMillis() + milliseconds)
    true.future
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  type MutableSet[A] = scala.collection.mutable.HashSet[A]

  private def emptySet(): MutableSet[ByteString] = scala.collection.mutable.HashSet.empty[ByteString]

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    val seq: MutableSet[ByteString] = memory.getTypedOrUpdate[MutableSet[ByteString]](key, emptySet())
    seq.++=(members)
    members.size.toLong.future
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    val seq: MutableSet[ByteString] = memory.getTypedOrUpdate[MutableSet[ByteString]](key, emptySet())
    seq.contains(member).future
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    val seq: MutableSet[ByteString] = memory.getTypedOrUpdate[MutableSet[ByteString]](key, emptySet())
    seq.toSeq.future
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    val seq: MutableSet[ByteString] = memory.getTypedOrUpdate[MutableSet[ByteString]](key, emptySet())
    val newSeq                      = seq.filterNot(b => members.contains(b))
    memory.put(key, newSeq)
    members.size.toLong.future
  }

  override def scard(key: String): Future[Long] = {
    val seq: MutableSet[ByteString] = memory.getTypedOrUpdate[MutableSet[ByteString]](key, emptySet())
    seq.size.toLong.future
  }

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = Healthy.future
}
