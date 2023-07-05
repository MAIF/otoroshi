package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.otoroshi._
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.CacheableWasmScript.CachedWasmScript
import otoroshi.wasm.proxywasm.VmData
import play.api.Logger
import play.api.libs.json.JsValue

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

case class WasmVmAction(
  parameters: WasmFunctionParameters,
  context: Option[VmData],
  promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
)

object WasmVm {
  val logger = Logger("otoroshi-wasm-vm")
}

case class WasmVm(index: Int, instance: OtoroshiInstance, pool: WasmVmPool) {

  private val initializedRef: AtomicBoolean = new AtomicBoolean(false)
  private val queue = {
    val env = pool.env
    Source.queue[WasmVmAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
      .mapAsync(1)(handle)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.otoroshiMaterializer)._1
  }

  private def handle(action: WasmVmAction): Future[Unit] = {
    Future.apply {
      try {
        action.context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
        if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"call vm ${index} with method ${action.parameters.functionName} on thread ${Thread.currentThread().getName} on path ${action.context.get.properties.get("request.path").map(v => new String(v))}")
        val res = action.parameters.call(instance)
        instance.reset()
        action.promise.trySuccess(res)
      } catch {
        case t: Throwable => action.promise.tryFailure(t)
      } finally {
        WasmContextSlot.clearCurrentContext()
      }
      ()
    }(WasmUtils.executor)
  }

  def reset(): Unit = {
    instance.reset()
  }

  def destroy(): Unit = {
    instance.close()
  }

  def release(): Unit = pool.release(this, index)

  def initialized(): Boolean = initializedRef.get()

  def initialize(f: => Any): Unit = {
    if (initializedRef.compareAndSet(false, true)) {
      f
    }
  }

  def call(
    parameters: WasmFunctionParameters,
    context: Option[VmData],
  )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise[Either[JsValue, (String, ResultsWrapper)]]()
    queue.offer(WasmVmAction(parameters, context, promise))
    promise.future
  }
}

case class WasmVmPoolAction(promise: Promise[WasmVm], options: WasmVmInitOptions) {
  private[wasm] def provideVm(vm: WasmVm): Unit = promise.trySuccess(vm)
}

object WasmVmPool {
  private[wasm] val engine = new OtoroshiEngine()
  private val instances = new TrieMap[String, WasmVmPool]()
  def forPlugin(id: String)(implicit env: Env): WasmVmPool = instances.getOrElseUpdate(id, new WasmVmPool(id, None, env))
  private[wasm] def removePlugin(id: String): Unit = instances.remove(id)
}

class WasmVmPool(pluginId: String, optConfig: Option[WasmConfig], val env: Env) {

  println("new WasmVmPool")

  private val counter = new AtomicInteger(0)
  private val templateRef = new AtomicReference[OtoroshiTemplate](null)
  //private val allVms = new TrieMap[Int, WasmVm]()
  //private val inUseVms = new TrieMap[Int, WasmVm]()
  private val allVms = new ConcurrentLinkedQueue[WasmVm]()
  private val inUseVms = new ConcurrentLinkedQueue[WasmVm]()
  private val creating = new AtomicBoolean(false)
  private val lastPluginVersion = new AtomicReference[String](null)
  private val requestsSource = Source.queue[WasmVmPoolAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val prioritySource = Source.queue[WasmVmPoolAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val (priorityQueue, requestsQueue) = {
    prioritySource
      .mergePrioritizedMat(requestsSource, 99, 1, false)(Keep.both)
      //.mapAsync(1)(handleAction)
      .map(handleAction)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.otoroshiMaterializer)._1
  }

  private def handleAction(action: WasmVmPoolAction): Unit = {
    wasmConfig() match {
      case None =>
        destroyCurrent()
        WasmVmPool.removePlugin(pluginId)
        Future.failed(new RuntimeException(s"No more plugin ${pluginId}"))
      case Some(wcfg) => {
        if (hasChanged(wcfg)) {
          println("plugin has changed, destroying old instances")
          destroyCurrent()
          createVm(wcfg, action.options)
        }
        if (!hasAvailableVm(wcfg)) {
          if (isVmCreating()) {
            priorityQueue.offer(action)
            Future.successful(())
          } else {
            if (atMaxPoolCapacity(wcfg)) {
              priorityQueue.offer(action)
            } else {
              // create on
              createVm(wcfg, action.options)
              val vm = acquireVm()
              action.provideVm(vm)
            }
          }
        } else {
          val vm = acquireVm()
          action.provideVm(vm)
        }
      }
    }
  }

  private def createVm(config: WasmConfig, options: WasmVmInitOptions): Unit = synchronized {
    if (creating.compareAndSet(false, true)) {
      val index = counter.incrementAndGet()
      println(s"creating vm: ${index}")
      if (templateRef.get() == null) {
        // TODO: fix it
        Await.result(config.source.getWasm()(env, env.otoroshiExecutionContext), 10.seconds)
        val wasm = WasmUtils.scriptCache(env).apply(config.source.cacheKey).asInstanceOf[CachedWasmScript].script
        val hash = java.security.MessageDigest.getInstance("SHA-256")
          .digest(wasm.toArray)
          .map("%02x".format(_))
          .mkString
        val resolver = new WasmSourceResolver()
        val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
        templateRef.set(new OtoroshiTemplate(WasmVmPool.engine, hash, new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava,
          config.allowedPaths.asJava
        )))
      }
      val template = templateRef.get()
      val functions: Array[OtoroshiHostFunction[_ <: OtoroshiHostUserData]] = if (options.importDefaultHostFunctions) {
        HostFunctions.getFunctions(config, pluginId, None)(env, env.otoroshiExecutionContext) ++ options.addHostFunctions
      } else {
        options.addHostFunctions.toArray[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]
      }
      val memories = LinearMemories.getMemories(config)
      val instance = template.instantiate(WasmVmPool.engine, functions, memories, config.wasi)
      val vm = WasmVm(index, instance, this)
      allVms.offer(vm)
      creating.compareAndSet(true, false)
    }
  }

  private def acquireVm(): WasmVm = synchronized {
    if (allVms.size() > 0) {
      allVms.synchronized {
        val vm = allVms.poll()
        allVms.remove(vm)
        inUseVms.offer(vm)
        vm
      }
    } else {
      throw new RuntimeException("no instances available")
    }
  }

  private[wasm] def release(vm: WasmVm, index: Int): Unit = synchronized {
    allVms.synchronized {
      allVms.offer(vm)
      inUseVms.remove(vm)
    }
  }

  private def wasmConfig(): Option[WasmConfig] = {
    optConfig.orElse(env.proxyState.wasmPlugin(pluginId).map(_.config))
  }

  private def hasAvailableVm(plugin: WasmConfig): Boolean = allVms.size() > 0 && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creating.get()

  private def atMaxPoolCapacity(plugin: WasmConfig): Boolean = allVms.size >= plugin.instances

  private def destroyCurrent(): Unit = allVms.synchronized {
    allVms.asScala.foreach(_.destroy())
    allVms.clear()
    inUseVms.clear()
    //counter.set(0)
    creating.set(false)
    lastPluginVersion.set(null)
  }

  private def hasChanged(plugin: WasmConfig): Boolean = {
    var oldHash = lastPluginVersion.get()
    if (oldHash == null) {
      oldHash = plugin.json.stringify.sha512
      lastPluginVersion.set(oldHash)
    }
    val currentHash = plugin.json.stringify.sha512
    oldHash != currentHash
    false
  }

  def getPooledVm(options: WasmVmInitOptions = WasmVmInitOptions.empty()): Future[WasmVm] = {
    val p = Promise[WasmVm]()
    requestsQueue.offer(WasmVmPoolAction(p, options))
    p.future
  }
}

case class WasmVmInitOptions(importDefaultHostFunctions: Boolean, addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]])
object WasmVmInitOptions {
  def empty(): WasmVmInitOptions = WasmVmInitOptions(importDefaultHostFunctions = true, addHostFunctions = Seq.empty)
}