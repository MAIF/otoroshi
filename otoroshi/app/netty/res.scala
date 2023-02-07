package reactor.netty.resources

import java.lang.reflect.{Field, Modifier}
import scala.util.Try

object DefaultLoopResourcesHelper {
  def createEpollLoop(name: String, workers: Int, daemon: Boolean): LoopResources = {
    val c1 = classOf[reactor.netty.resources.DefaultLoopNativeDetector]
    val field = Try(c1.getField("INSTANCE")).toOption.flatMap(Option.apply).orElse(Try(c1.getDeclaredField("INSTANCE")).toOption.flatMap(Option.apply)).get
    field.setAccessible(true)
    val modifiers = classOf[Field].getDeclaredField("modifiers")
    modifiers.setAccessible(true)
    modifiers.setInt(field, field.getModifiers & ~Modifier.FINAL)
    val old = field.get(null)
    field.set(null, new DefaultLoopEpoll())
    println(old)
    val res = new DefaultLoopResources(name, -1, workers, daemon)
    res.onClient(true)
    res.onServer(true)
    println(res.cacheNativeServerLoops.get())
    field.set(null, old)
    res
  }
}
