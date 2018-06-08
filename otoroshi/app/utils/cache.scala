package utils

import java.util.concurrent.ConcurrentHashMap

object SimpleCache {
  def apply[K, V](initialValue: Map[K, (Long, V)] = Map.empty) = new SimpleCache[K, V](initialValue)
}

class SimpleCache[K, V](initialValue: Map[K, (Long, V)] = Map.empty) {

  import collection.JavaConverters._
  import scala.concurrent.duration._

  private val cache: ConcurrentHashMap[K, (Long, V)] = new ConcurrentHashMap[K, (Long, V)](initialValue.asJava)

  def remove(k: K): Option[V] = cleanup { _ =>
    Option(cache.remove(k)).map(_._2)
  }

  def removeAll(keys: Seq[K]): Unit = cleanup { _ =>
    keys.map(k => cache.remove(k))
  }

  def get(k: K): Option[V] = cleanup { _ =>
    Option(cache.get(k)).map(_._2)
  }

  def ttl(k: K): Option[Duration] = cleanup { _ =>
    Option(cache.get(k)).map {
      case (time, _) => (time - System.currentTimeMillis()).millis
    }
  }

  def contains(k: K): Boolean = cleanup { _ =>
    cache.contains(k)
  }

  def put(key: K, value: V, ttl: Duration = Duration.Inf): Unit = cleanup { time =>
    ttl match {
      case Duration.Inf => cache.put(key, (-1, value))
      case _            => cache.put(key, (time + ttl.toMillis, value))
    }
  }

  def putIfAbsent(key: K, value: V, ttl: Duration = Duration.Inf): Unit = cleanup { time =>
    ttl match {
      case Duration.Inf => cache.putIfAbsent(key, (-1, value))
      case _            => cache.putIfAbsent(key, (time + ttl.toMillis, value))
    }
  }

  def asMap: Map[K, V] = cleanup { _ =>
    cache.asScala.mapValues(_._2).toMap
  }

  private def cleanup[A](f: Long => A): A = {
    val time = System.currentTimeMillis()
    val killKeys = cache.asScala.collect {
      case (k, (expiration, _)) if expiration != -1 && expiration < time => k
    }.toSet
    killKeys.map(k => cache.remove(k))
    f(time)
  }
}
