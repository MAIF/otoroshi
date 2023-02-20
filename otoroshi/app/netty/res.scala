package reactor.netty.resources

import java.lang.reflect.{Field, Modifier}
import scala.collection.concurrent.TrieMap
import scala.util.Try

object DefaultLoopResourcesHelper {

  private val cache = new TrieMap[String, LoopResources]()

  def getDefaultLoop(name: String, workers: Int, daemon: Boolean): LoopResources = {
    val key = s"default-$name-$workers-$daemon"
    cache.getOrElse(key, {
      val res = LoopResources.create(name, workers, daemon)
      cache.put(key, res)
      res
    })
  }

  def getKQueueLoop(name: String, workers: Int, daemon: Boolean): LoopResources = {
    val key = s"kqueue-specific-$name-$workers-$daemon"
    cache.getOrElse(key, {
      val res = LoopResources.create(name, workers, daemon)
      cache.put(key, res)
      res
    })
  }

  def getEpollLoop(name: String, workers: Int, daemon: Boolean): LoopResources = {
    val key = s"epoll-specific-$name-$workers-$daemon"
    cache.getOrElse(key, {
      val c1 = classOf[reactor.netty.resources.DefaultLoopNativeDetector]
      val field = Try(c1.getField("INSTANCE")).toOption.flatMap(Option.apply).orElse(Try(c1.getDeclaredField("INSTANCE")).toOption.flatMap(Option.apply)).get
      field.setAccessible(true)
      val modifiers = classOf[Field].getDeclaredField("modifiers")
      modifiers.setAccessible(true)
      modifiers.setInt(field, field.getModifiers & ~Modifier.FINAL)
      // val old = field.get(null)
      field.set(null, new DefaultLoopEpoll())
      // println("old", old)
      val res = new DefaultLoopResources(name, -1, workers, daemon)
      // println("hasNative", LoopResources.hasNativeSupport())
      res.onClient(true)
      res.onServer(true)
      res.onServerSelect(true)
      // println("server", res.cacheNativeServerLoops.get())
      // println("client", res.cacheNativeClientLoops.get())
      // println("select", res.cacheNativeSelectLoops.get())
      res.serverLoops.set(res.cacheNativeServerLoops.get())
      res.clientLoops.set(res.cacheNativeClientLoops.get())
      res.serverSelectLoops.set(res.cacheNativeSelectLoops.get())
      // field.set(null, old)
      cache.put(key, res)
      res
    })
  }
}
