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
import scala.util.Try

sealed trait WasmVmAction

object WasmVmAction {
  case class WasmVmCallAction(
    parameters: WasmFunctionParameters,
    context: Option[VmData],
    promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
  ) extends WasmVmAction
  case object WasmVmKillAction extends WasmVmAction
}



object WasmVm {
  val logger = Logger("otoroshi-wasm-vm")
}

case class WasmVm(index: Int, maxCalls: Int, instance: OtoroshiInstance, memories: Array[OtoroshiLinearMemory], functions: Array[OtoroshiHostFunction[_ <: OtoroshiHostUserData]], pool: WasmVmPool) {

  private val initializedRef: AtomicBoolean = new AtomicBoolean(false)
  private val killAtRelease: AtomicBoolean = new AtomicBoolean(false)
  private val inFlight = new AtomicInteger(0)
  private val callCounter = new AtomicInteger(0)
  private val queue = {
    val env = pool.env
    Source.queue[WasmVmAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
      .mapAsync(1)(handle)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.otoroshiMaterializer)._1
  }

  def calls: Int = callCounter.get()
  def current: Int = inFlight.get()

  private def handle(act: WasmVmAction): Future[Unit] = {
    Future.apply {
      act match {
        case WasmVmAction.WasmVmKillAction => destroy()
        case action: WasmVmAction.WasmVmCallAction => {
          try {
            inFlight.decrementAndGet()
            action.context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
            if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"call vm ${index} with method ${action.parameters.functionName} on thread ${Thread.currentThread().getName} on path ${action.context.get.properties.get("request.path").map(v => new String(v))}")
            val res = action.parameters.call(instance)
            if (res.isRight && res.right.get._2.results.getValues() != null) {
              val ret = res.right.get._2.results.getValues()(0).v.i32
              if (ret > 7 || ret < 0) { // weird multi thread issues
                ignore()
                killAtRelease.set(true)
              }
            }
            action.promise.trySuccess(res)
          } catch {
            case t: Throwable => action.promise.tryFailure(t)
          } finally {
            instance.reset()
            WasmVm.logger.debug(s"functions: ${functions.size}")
            WasmVm.logger.debug(s"memories: ${memories.size}")
            WasmContextSlot.clearCurrentContext()
            val count = callCounter.incrementAndGet()
            if (count >= maxCalls) {
              callCounter.set(0)
              if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"killing vm ${index} with remaining ${inFlight.get()} calls (${count})")
              ignore()
              killAtRelease.set(true)
            }
          }
        }
      }
      ()
    }(WasmUtils.executor)
  }

  def reset(): Unit = instance.reset()

  def destroy(): Unit = {
    if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"destroy vm: ${index}")
    instance.close()
  }

  def release(): Unit = {
    if (killAtRelease.get()) {
      queue.offer(WasmVmAction.WasmVmKillAction)
    } else {
      pool.release(this, index)
    }
  }

  def ignore(): Unit = pool.ignore(this)

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
    inFlight.incrementAndGet()
    queue.offer(WasmVmAction.WasmVmCallAction(parameters, context, promise))
    promise.future
  }
}

case class WasmVmPoolAction(promise: Promise[WasmVm], options: WasmVmInitOptions) {
  private[wasm] def provideVm(vm: WasmVm): Unit = promise.trySuccess(vm)
}

object WasmVmPool {
  private[wasm] val logger = Logger("otoroshi-wasm-vm-pool")
  private[wasm] val engine = new OtoroshiEngine()
  private val instances = new TrieMap[String, WasmVmPool]()
  def forPlugin(id: String)(implicit env: Env): WasmVmPool = {
    // TODO: change hardcoded
    instances.getOrElseUpdate(id, new WasmVmPool(id, None, 100, env))
  }
  private[wasm] def removePlugin(id: String): Unit = instances.remove(id)
}

class WasmVmPool(pluginId: String, optConfig: Option[WasmConfig], maxCalls: Int, val env: Env) {

  WasmVmPool.logger.debug("new WasmVmPool")

  private val engine = new OtoroshiEngine()
  private val counter = new AtomicInteger(-1)
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
        val changed = hasChanged(wcfg)
        val available = hasAvailableVm(wcfg)
        val create = isVmCreating()
        val atMax = atMaxPoolCapacity(wcfg)
        if (changed) {
          WasmVmPool.logger.debug("plugin has changed, destroying old instances")
          destroyCurrent()
          createVm(wcfg, action.options, "has changed")
        }
        if (!available) {
          if (create) {
            priorityQueue.offer(action)
            Future.successful(())
          } else {
            if (atMax) {
              priorityQueue.offer(action)
            } else {
              // create on
              createVm(wcfg, action.options, s"create - changed: ${changed} - available: ${available} - create: ${create} - atMax: $atMax - ${wcfg.instances} - ${inUseVms.size()}")
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

  private def createVm(config: WasmConfig, options: WasmVmInitOptions, from: String): Unit = synchronized {
    if (creating.compareAndSet(false, true)) {
      val index = counter.incrementAndGet()
      WasmVmPool.logger.debug(s"creating vm: ${index} - $from")
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
        templateRef.set(new OtoroshiTemplate(engine, hash, new Manifest(
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
      val instance = template.instantiate(engine, functions, memories, config.wasi)
      val vm = WasmVm(index, maxCalls, instance, memories, functions, this)
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

  private[wasm] def ignore(vm: WasmVm): Unit = synchronized {
    allVms.synchronized {
      inUseVms.remove(vm)
    }
  }

  private def wasmConfig(): Option[WasmConfig] = {
    optConfig.orElse(env.proxyState.wasmPlugin(pluginId).map(_.config))
  }

  private def hasAvailableVm(plugin: WasmConfig): Boolean = allVms.size() > 0 && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creating.get()

  private def atMaxPoolCapacity(plugin: WasmConfig): Boolean = (allVms.size + inUseVms.size) >= plugin.instances

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
    oldHash != currentHash // TODO: fix it
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