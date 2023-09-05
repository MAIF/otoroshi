package io.otoroshi.common.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.codahale.metrics.UniformReservoir
import io.otoroshi.common.utils.implicits._
import io.otoroshi.common.wasm.CacheableWasmScript.CachedWasmScript
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import org.extism.sdk.wasmotoroshi._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

sealed trait WasmVmAction

object WasmVmAction {
  case object WasmVmKillAction extends WasmVmAction
  case class WasmVmCallAction(
      parameters: WasmFunctionParameters,
      context: Option[AbsVmData],
      promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
  )                            extends WasmVmAction
}

case class OPAWasmVm(opaDataAddr: Int, opaBaseHeapPtr: Int)

case class WasmVm(
    index: Int,
    maxCalls: Int,
    maxMemory: Long,
    resetMemory: Boolean,
    instance: WasmOtoroshiInstance,
    vmDataRef: AtomicReference[AbsVmData],
    memories: Array[WasmOtoroshiLinearMemory],
    functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]],
    pool: WasmVmPool,
    var opaPointers: Option[OPAWasmVm] = None
) {

  private val callDurationReservoirNs       = new UniformReservoir()
  private val lastUsage: AtomicLong         = new AtomicLong(System.currentTimeMillis())
  private val initializedRef: AtomicBoolean = new AtomicBoolean(false)
  private val killAtRelease: AtomicBoolean  = new AtomicBoolean(false)
  private val inFlight                      = new AtomicInteger(0)
  private val callCounter                   = new AtomicInteger(0)
  private val queue = {
    val env = pool.ic
    Source
      .queue[WasmVmAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
      .mapAsync(1)(handle)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.materializer)
      ._1
  }

  def calls: Int   = callCounter.get()
  def current: Int = inFlight.get()

  private def handle(act: WasmVmAction): Future[Unit] = {
    Future.apply {
      lastUsage.set(System.currentTimeMillis())
      act match {
        case WasmVmAction.WasmVmKillAction         => destroy()
        case action: WasmVmAction.WasmVmCallAction => {
          try {
            inFlight.decrementAndGet()
            // action.context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
            action.context.foreach(ctx => vmDataRef.set(ctx))
            if (pool.ic.logger.isDebugEnabled)
              pool.ic.logger.debug(s"call vm ${index} with method ${action.parameters.functionName} on thread ${Thread
                .currentThread()
                .getName} on path ${action.context.map(_.properties.get("request.path").map(v => new String(v))).getOrElse("--")}")
            val start = System.nanoTime()
            val res   = action.parameters.call(instance)
            callDurationReservoirNs.update(System.nanoTime() - start)
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
            if (resetMemory) {
              instance.reset()
            }
            pool.ic.logger.debug(s"functions: ${functions.size}")
            pool.ic.logger.debug(s"memories: ${memories.size}")
            // WasmContextSlot.clearCurrentContext()
            // vmDataRef.set(null)
            val count = callCounter.incrementAndGet()
            if (count >= maxCalls) {
              callCounter.set(0)
              if (pool.ic.logger.isDebugEnabled)
                pool.ic.logger.debug(s"killing vm ${index} with remaining ${inFlight.get()} calls (${count})")
              destroyAtRelease()
            }
          }
        }
      }
      ()
    }(pool.ic.wasmExecutor)
  }

  def reset(): Unit = instance.reset()

  def destroy(): Unit = {
    if (pool.ic.logger.isDebugEnabled) pool.ic.logger.debug(s"destroy vm: ${index}")
    pool.ic.logger.debug(s"destroy vm: ${index}")
    pool.clear(this)
    instance.close()
  }

  def isBusy(): Boolean = {
    inFlight.get() > 0
  }

  def destroyAtRelease(): Unit = {
    ignore()
    killAtRelease.set(true)
  }

  def release(): Unit = {
    if (killAtRelease.get()) {
      queue.offer(WasmVmAction.WasmVmKillAction)
    } else {
      pool.release(this)
    }
  }

  def lastUsedAt(): Long = lastUsage.get()

  def hasNotBeenUsedInTheLast(duration: FiniteDuration): Boolean =
    if (duration.toNanos == 0L) false else !hasBeenUsedInTheLast(duration)

  def consumesMoreThanMemoryPercent(percent: Double): Boolean = if (percent == 0.0) {
    false
  } else {
    val consumed: Double = instance.getMemorySize.toDouble / maxMemory.toDouble
    val res              = consumed > percent
    if (pool.ic.logger.isDebugEnabled)
      pool.ic.logger.debug(
        s"consumesMoreThanMemoryPercent($percent) = (${instance.getMemorySize} / $maxMemory) > $percent : $res : (${consumed * 100.0}%)"
      )
    res
  }

  def tooSlow(max: Long): Boolean = {
    if (max == 0L) {
      false
    } else {
      callDurationReservoirNs.getSnapshot.getMean.toLong > max
    }
  }

  def hasBeenUsedInTheLast(duration: FiniteDuration): Boolean = {
    val now   = System.currentTimeMillis()
    val limit = lastUsage.get() + duration.toMillis
    now < limit
  }

  def ignore(): Unit = pool.ignore(this)

  def initialized(): Boolean = initializedRef.get()

  def initialize(f: => Any): Unit = {
    if (initializedRef.compareAndSet(false, true)) {
      f
    }
  }

  def finitialize[A](f: => Future[A]): Future[Unit] = {
    if (initializedRef.compareAndSet(false, true)) {
      f.map(_ => ())(pool.ic.executionContext)
    } else {
      ().vfuture
    }
  }

  def call(
      parameters: WasmFunctionParameters,
      context: Option[AbsVmData]
  )(implicit ic: WasmIntegrationContext, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise[Either[JsValue, (String, ResultsWrapper)]]()
    inFlight.incrementAndGet()
    lastUsage.set(System.currentTimeMillis())
    queue.offer(WasmVmAction.WasmVmCallAction(parameters, context, promise))
    promise.future
  }
}

case class WasmVmPoolAction(promise: Promise[WasmVm], options: WasmVmInitOptions) {
  private[wasm] def provideVm(vm: WasmVm): Unit = promise.trySuccess(vm)
  private[wasm] def fail(e: Throwable): Unit    = promise.tryFailure(e)
}

object WasmVmPool {

  private[wasm] val logger = Logger("otoroshi-wasm-vm-pool")
  private[wasm] val engine = new WasmOtoroshiEngine()
  private val instances    = new TrieMap[String, WasmVmPool]()

  def allInstances(): Map[String, WasmVmPool] = instances.synchronized {
    instances.toMap
  }

  def forConfig(config: => WasmConfiguration)(implicit ic: WasmIntegrationContext): WasmVmPool = instances.synchronized {
    val key = s"${config.source.cacheKey}?cfg=${config.json.stringify.sha512}"
    instances.getOrUpdate(key) {
      new WasmVmPool(key, config.some, ic)
    }
  }

  private[wasm] def removePlugin(id: String): Unit = instances.synchronized {
    instances.remove(id)
  }
}

class WasmVmPool(stableId: => String, optConfig: => Option[WasmConfiguration], val ic: WasmIntegrationContext) {

  WasmVmPool.logger.debug("new WasmVmPool")

  private val engine             = new WasmOtoroshiEngine()
  private val counter            = new AtomicInteger(-1)
  private val templateRef        = new AtomicReference[WasmOtoroshiTemplate](null)
  private[wasm] val availableVms = new ConcurrentLinkedQueue[WasmVm]()
  private[wasm] val inUseVms     = new ConcurrentLinkedQueue[WasmVm]()
  private val creatingRef        = new AtomicBoolean(false)
  private val lastPluginVersion  = new AtomicReference[String](null)
  private val requestsSource     = Source.queue[WasmVmPoolAction](ic.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val prioritySource     = Source.queue[WasmVmPoolAction](ic.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val (priorityQueue, requestsQueue) = {
    prioritySource
      .mergePrioritizedMat(requestsSource, 99, 1, false)(Keep.both)
      .map(handleAction)
      .toMat(Sink.ignore)(Keep.both)
      .run()(ic.materializer)
      ._1
  }

  // unqueue actions from the action queue
  private def handleAction(action: WasmVmPoolAction): Unit = try {
    wasmConfig() match {
      case None       =>
        // if we cannot find the current wasm config, something is wrong, we destroy the pool
        destroyCurrentVms()
        WasmVmPool.removePlugin(stableId)
        action.fail(new RuntimeException(s"No more plugin ${stableId}"))
      case Some(wcfg) => {
        // first we ensure the wasm source has been fetched
        if (!wcfg.source.isCached()(ic)) {
          wcfg.source
            .getWasm()(ic, ic.executionContext)
            .andThen { case _ =>
              priorityQueue.offer(action)
            }(ic.executionContext)
        } else {
          val changed   = hasChanged(wcfg)
          val available = hasAvailableVm(wcfg)
          val creating  = isVmCreating()
          val atMax     = atMaxPoolCapacity(wcfg)
          // then we check if the underlying wasmcode + config has not changed since last time
          if (changed) {
            // if so, we destroy all current vms and recreate a new one
            WasmVmPool.logger.warn("plugin has changed, destroying old instances")
            destroyCurrentVms()
            createVm(wcfg, action.options)
          }
          // check if a vm is available
          if (!available) {
            // if not, but a new one is creating, just wait a little bit more
            if (creating) {
              priorityQueue.offer(action)
            } else {
              // check if we hit the max possible instances
              if (atMax) {
                // if so, just wait
                priorityQueue.offer(action)
              } else {
                // if not, create a new instance because we need one
                createVm(wcfg, action.options)
                priorityQueue.offer(action)
              }
            }
          } else {
            // if so, acquire one
            val vm = acquireVm()
            action.provideVm(vm)
          }
        }
      }
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      action.fail(t)
  }

  // create a new vm for the pool
  // we try to create vm one by one and to not create more than needed
  private def createVm(config: WasmConfiguration, options: WasmVmInitOptions): Unit = synchronized {
    if (creatingRef.compareAndSet(false, true)) {
      val index                                                                     = counter.incrementAndGet()
      WasmVmPool.logger.debug(s"creating vm: ${index}")
      if (templateRef.get() == null) {
        if (!config.source.isCached()(ic)) {
          // this part should never happen anymore, but just in case
          WasmVmPool.logger.warn("fetching missing source")
          Await.result(config.source.getWasm()(ic, ic.executionContext), 30.seconds)
        }
        lastPluginVersion.set(computeHash(config, config.source.cacheKey, ic.wasmScriptCache))
        val cache    = ic.wasmScriptCache
        val key      = config.source.cacheKey
        val wasm     = cache(key).asInstanceOf[CachedWasmScript].script
        val hash     = wasm.sha256
        val resolver = new WasmSourceResolver()
        val source   = resolver.resolve("wasm", wasm.toByteBuffer.array())
        templateRef.set(
          new WasmOtoroshiTemplate(
            engine,
            hash,
            new Manifest(
              Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
              new MemoryOptions(config.memoryPages),
              config.config.asJava,
              config.allowedHosts.asJava,
              config.allowedPaths.asJava
            )
          )
        )
      }
      val template                                                                  = templateRef.get()
      val vmDataRef                                                                 = new AtomicReference[AbsVmData](null)
      val addedFunctions                                                            = options.addHostFunctions(vmDataRef)
      val functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] =
        if (options.importDefaultHostFunctions) {
          ic.hostFunctions(config, stableId) ++ addedFunctions
        } else {
          addedFunctions.toArray[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
        }
      val memories                                                                  = LinearMemories.getMemories(config)
      val instance                                                                  = template.instantiate(engine, functions, memories, config.wasi)
      val vm                                                                        = WasmVm(
        index,
        config.killOptions.maxCalls,
        config.memoryPages * (64L * 1024L),
        options.resetMemory,
        instance,
        vmDataRef,
        memories,
        functions,
        this
      )
      availableVms.offer(vm)
      creatingRef.compareAndSet(true, false)
    }
  }

  // acquire an available vm for work
  private def acquireVm(): WasmVm = synchronized {
    if (availableVms.size() > 0) {
      availableVms.synchronized {
        val vm = availableVms.poll()
        availableVms.remove(vm)
        inUseVms.offer(vm)
        vm
      }
    } else {
      throw new RuntimeException("no instances available")
    }
  }

  // release the vm to be available for other tasks
  private[wasm] def release(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      availableVms.offer(vm)
      inUseVms.remove(vm)
    }
  }

  // do not consider the vm anymore for more work (the vm is being dropped for some reason)
  private[wasm] def ignore(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      inUseVms.remove(vm)
    }
  }

  // do not consider the vm anymore for more work (the vm is being dropped for some reason)
  private[wasm] def clear(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      availableVms.remove(vm)
    }
  }

  private[wasm] def wasmConfig(): Option[WasmConfiguration] = {
    optConfig.orElse(ic.wasmConfig(stableId))
  }

  private def hasAvailableVm(plugin: WasmConfiguration): Boolean =
    availableVms.size() > 0 && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creatingRef.get()

  private def atMaxPoolCapacity(plugin: WasmConfiguration): Boolean = (availableVms.size + inUseVms.size) >= plugin.instances

  // close the current pool
  private[wasm] def close(): Unit = availableVms.synchronized {
    engine.close()
  }

  // destroy all vms and clear everything in order to destroy the current pool
  private[wasm] def destroyCurrentVms(): Unit = availableVms.synchronized {
    WasmVmPool.logger.info("destroying all vms")
    availableVms.asScala.foreach(_.destroy())
    availableVms.clear()
    inUseVms.clear()
    //counter.set(0)
    templateRef.set(null)
    creatingRef.set(false)
    lastPluginVersion.set(null)
  }

  // compute the current hash for a tuple (wasmcode + config)
  private def computeHash(
      config: WasmConfiguration,
      key: String,
      cache: TrieMap[String, CacheableWasmScript]
  ): String = {
    config.json.stringify.sha512 + "#" + cache
      .get(key)
      .map {
        case CacheableWasmScript.CachedWasmScript(wasm, _) => wasm.sha512
        case _                                             => "fetching"
      }
      .getOrElse("null")
  }

  // compute if the source (wasm code + config) is the same than current
  private def hasChanged(config: WasmConfiguration): Boolean = availableVms.synchronized {
    val key     = config.source.cacheKey
    val cache   = ic.wasmScriptCache
    var oldHash = lastPluginVersion.get()
    if (oldHash == null) {
      oldHash = computeHash(config, key, cache)
      lastPluginVersion.set(oldHash)
    }
    cache.get(key) match {
      case Some(CacheableWasmScript.CachedWasmScript(_, _)) => {
        val currentHash = computeHash(config, key, cache)
        oldHash != currentHash
      }
      case _                                                => false
    }
  }

  // get a pooled vm when one available.
  // Do not forget to release it after usage
  def getPooledVm(options: WasmVmInitOptions = WasmVmInitOptions.empty()): Future[WasmVm] = {
    val p = Promise[WasmVm]()
    requestsQueue.offer(WasmVmPoolAction(p, options))
    p.future
  }

  // borrow a vm for sync operations
  def withPooledVm[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => A): Future[A] = {
    implicit val ev = ic
    implicit val ec = ic.executionContext
    getPooledVm(options).flatMap { vm =>
      val p = Promise[A]()
      try {
        val ret = f(vm)
        p.trySuccess(ret)
      } catch {
        case e: Throwable =>
          p.tryFailure(e)
      } finally {
        vm.release()
      }
      p.future
    }
  }

  // borrow a vm for async operations
  def withPooledVmF[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => Future[A]): Future[A] = {
    implicit val ev = ic
    implicit val ec = ic.executionContext
    getPooledVm(options).flatMap { vm =>
      f(vm).andThen { case _ =>
        vm.release()
      }
    }
  }
}

case class WasmVmInitOptions(
    importDefaultHostFunctions: Boolean = true,
    resetMemory: Boolean = true,
    addHostFunctions: (AtomicReference[AbsVmData]) => Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = _ =>
      Seq.empty
)

object WasmVmInitOptions {
  def empty(): WasmVmInitOptions = WasmVmInitOptions(
    importDefaultHostFunctions = true,
    resetMemory = true,
    addHostFunctions = _ => Seq.empty
  )
}

case class WasmVmKillOptions(
    immortal: Boolean = false,
    maxCalls: Int = Int.MaxValue,
    maxMemoryUsage: Double = 0.0,
    maxAvgCallDuration: FiniteDuration = 0.nano,
    maxUnusedDuration: FiniteDuration = 5.minutes
) {
  def json: JsValue = WasmVmKillOptions.format.writes(this)
}

object WasmVmKillOptions {
  val default = WasmVmKillOptions()
  val format  = new Format[WasmVmKillOptions] {
    override def writes(o: WasmVmKillOptions): JsValue             = Json.obj(
      "immortal"              -> o.immortal,
      "max_calls"             -> o.maxCalls,
      "max_memory_usage"      -> o.maxMemoryUsage,
      "max_avg_call_duration" -> o.maxAvgCallDuration.toMillis,
      "max_unused_duration"   -> o.maxUnusedDuration.toMillis
    )
    override def reads(json: JsValue): JsResult[WasmVmKillOptions] = Try {
      WasmVmKillOptions(
        immortal = json.select("immortal").asOpt[Boolean].getOrElse(false),
        maxCalls = json.select("max_calls").asOpt[Int].getOrElse(Int.MaxValue),
        maxMemoryUsage = json.select("max_memory_usage").asOpt[Double].getOrElse(0.0),
        maxAvgCallDuration = json.select("max_avg_call_duration").asOpt[Long].map(_.millis).getOrElse(0.nano),
        maxUnusedDuration = json.select("max_unused_duration").asOpt[Long].map(_.millis).getOrElse(5.minutes)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}
