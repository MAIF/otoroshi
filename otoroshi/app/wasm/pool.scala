package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.otoroshi._
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.models.WasmPlugin
import otoroshi.wasm.CacheableWasmScript.CachedWasmScript
import otoroshi.wasm.proxywasm.VmData
import play.api.libs.json.JsValue

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
        val res = action.parameters.call(instance)
        action.promise.trySuccess(res)
      } catch {
        case t: Throwable => action.promise.tryFailure(t)
      } finally {
        WasmContextSlot.clearCurrentContext()
      }
      ()
    }(WasmUtils.executor)
  }

  def destroy(): Unit = {
    println(s"destroy vm ${index}")
    instance.close()
  }

  def release(): Unit = pool.release(this, index)

  def initialized(): Boolean = initializedRef.get()
  def initialize(f: => Any): Unit = {
    if (initializedRef.compareAndSet(false, true)) {
      println("vm initialize")
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

case class WasmVmPoolAction(promise: Promise[WasmVm], importDefaultHostFunctions: Boolean, addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]) {
  private[wasm] def provideVm(vm: WasmVm): Unit = promise.trySuccess(vm)
}

object WasmVmPool {
  private[wasm] val engine = new OtoroshiEngine()
  private val instances = new TrieMap[String, WasmVmPool]()
  def forPlugin(id: String)(implicit env: Env): WasmVmPool = instances.getOrElseUpdate(id, new WasmVmPool(id, None, env))
  private[wasm] def removePlugin(id: String): Unit = instances.remove(id)
}

class WasmVmPool(pluginId: String, optConfig: Option[WasmConfig], val env: Env) {

  println(s"new WasmVmPool ${pluginId}")

  private val counter = new AtomicInteger(0)
  private val templateRef = new AtomicReference[OtoroshiTemplate](null)
  private val allVms = new TrieMap[Int, WasmVm]()
  private val inUseVms = new TrieMap[Int, WasmVm]()
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
    println("handleAction")
    wasmConfig() match {
      case None =>
        println("handleAction: None")
        destroyCurrent()
        WasmVmPool.removePlugin(pluginId)
        Future.failed(new RuntimeException(s"No more plugin ${pluginId}"))
      case Some(wcfg) => {
        println("handleAction: Some")
        if (hasChanged(wcfg)) {
          println("handleAction: hasChanged")
          destroyCurrent()
          createVm(wcfg, action.importDefaultHostFunctions, action.addHostFunctions)
        }
        if (!hasAvailableVm(wcfg)) {
          println("handleAction: vm available")
          if (isVmCreating()) {
            println("handleAction: is vm creating")
            priorityQueue.offer(action)
            Future.successful(())
          } else {
            println("handleAction: is not vm creating")
            if (atMaxPoolCapacity(wcfg)) {
              println("handleAction: at max")
              priorityQueue.offer(action)
            } else {
              println("handleAction: creating and provide")
              // create on
              createVm(wcfg, action.importDefaultHostFunctions, action.addHostFunctions)
              val vm = acquireVm()
              action.provideVm(vm)
            }
          }
        } else {
          println("handleAction: vm available provide")
          val vm = acquireVm()
          println(s"vm: $vm")
          action.provideVm(vm)
        }
      }
    }
  }

  private def createVm(config: WasmConfig, importDefaultHostFunctions: Boolean, addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]): WasmVm = synchronized {
    println("createVm")
    creating.set(true)
    if (templateRef.get() == null) {
      println("createVm: create template")
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
    println("createVm: create host funcs")
    val functions: Array[OtoroshiHostFunction[_ <: OtoroshiHostUserData]] = if (importDefaultHostFunctions) { // config.importDefaultHostFunctions) { // TODO
      HostFunctions.getFunctions(config, pluginId, None)(env, env.otoroshiExecutionContext) ++ addHostFunctions
    } else {
      addHostFunctions.toArray[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]
    }
    println("createVm: create memories")
    val memories = LinearMemories.getMemories(config)
    val instance = template.instantiate(WasmVmPool.engine, functions, memories, config.wasi)
    val index = counter.incrementAndGet()
    val vm = WasmVm(index, instance, this)
    allVms.put(index, vm)
    // TODO: init
    creating.set(false)
    vm
  }

  private def acquireVm(): WasmVm = synchronized {
    println("acquireVm")
    if (allVms.nonEmpty) {
      val (index, vm) = allVms.head
      allVms.remove(index)
      inUseVms.put(index, vm)
      vm
    } else {
      println("no instances available")
      throw new RuntimeException("no instances available")
    }
  }

  private[wasm] def release(vm: WasmVm, index: Int): Unit = synchronized {
    println("releaseVm")
    allVms.put(index, vm)
    inUseVms.remove(index)
  }

  private def wasmConfig(): Option[WasmConfig] = {
    optConfig.orElse(env.proxyState.wasmPlugin(pluginId).map(_.config))
  }

  private def hasAvailableVm(plugin: WasmConfig): Boolean = allVms.nonEmpty && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creating.get()

  private def atMaxPoolCapacity(plugin: WasmConfig): Boolean = allVms.size >= plugin.instances

  private def destroyCurrent(): Unit = {
    allVms.foreach(_._2.destroy())
    allVms.clear()
    inUseVms.clear()
    counter.set(0)
    creating.set(false)
    lastPluginVersion.set(null)
  }

  private def hasChanged(plugin: WasmConfig): Boolean = {
    // TODO: test plugin hash
    false
  }

  def getVm(importDefaultHostFunctions: Boolean, addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]): Future[WasmVm] = {
    val p = Promise[WasmVm]()
    requestsQueue.offer(WasmVmPoolAction(p, importDefaultHostFunctions, addHostFunctions))
    p.future
  }
}
