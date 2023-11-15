package io.otoroshi.common.wasm.scaladsl

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.otoroshi.common.wasm.scaladsl.implicits._
import io.otoroshi.common.wasm.scaladsl.security.TlsConfig
import org.extism.sdk.wasmotoroshi.{WasmOtoroshiHostFunction, WasmOtoroshiHostUserData}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSRequest

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait WasmVmData {
  def properties: Map[String, Array[Byte]]
}

trait WasmIntegrationContext {

  def logger: Logger
  def materializer: Materializer
  def executionContext: ExecutionContext

  def wasmCacheTtl: Long
  def wasmQueueBufferSize: Int
  def selfRefreshingPools: Boolean

  def url(path: String, tlsConfigOpt: Option[TlsConfig] = None): WSRequest

  def wasmManagerSettings: Future[Option[WasmManagerSettings]]
  def wasmConfigSync(path: String): Option[WasmConfiguration] = None
  def wasmConfig(path: String): Future[Option[WasmConfiguration]] = wasmConfigSync(path).vfuture
  def wasmConfigs(): Future[Seq[WasmConfiguration]]
  def inlineWasmSources(): Future[Seq[WasmSource]] = Seq.empty.vfuture
  def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]

  def wasmScriptCache: TrieMap[String, CacheableWasmScript]
  def wasmExecutor: ExecutionContext
}

object BasicWasmIntegrationContextWithNoHttpClient {
  def apply[A <: WasmConfiguration](name: String, store: InMemoryWasmConfigurationStore[A]): BasicWasmIntegrationContextWithNoHttpClient[A] = new BasicWasmIntegrationContextWithNoHttpClient[A](name, store)
}

class BasicWasmIntegrationContextWithNoHttpClient[A <: WasmConfiguration](name: String, store: InMemoryWasmConfigurationStore[A]) extends WasmIntegrationContext {
  val system = ActorSystem(name)
  val materializer: Materializer = Materializer(system)
  val executionContext: ExecutionContext = system.dispatcher
  val logger: Logger = Logger(name)
  val wasmCacheTtl: Long = 2000
  val wasmQueueBufferSize: Int = 100
  val selfRefreshingPools: Boolean = false
  val wasmManagerSettings: Future[Option[WasmManagerSettings]] = Future.successful(None)
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  override def url(path: String, tlsConfigOpt: Option[TlsConfig] = None): WSRequest = ???

  override def wasmConfigSync(path: String): Option[WasmConfiguration] = store.wasmConfiguration(path)
  override def wasmConfigs(): Future[Seq[WasmConfiguration]] = store.wasmConfigurations().vfuture
  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = Array.empty
}

object WasmIntegration {
  def apply(ic: WasmIntegrationContext): WasmIntegration = new WasmIntegration(ic)
}

class WasmIntegration(ic: WasmIntegrationContext) {

  implicit val context: WasmIntegrationContext = ic
  implicit val executionContext: ExecutionContext = ic.executionContext

  private lazy val scheduler = Executors.newScheduledThreadPool(2)
  private lazy val schedRef = new AtomicReference[ScheduledFuture[_]](null)

  /// vm apis

  private[common] def wasmVmById(id: String, maxCallsBetweenUpdates: Int = 100000): Future[Option[(WasmVm, WasmConfiguration)]] = {
    ic.wasmConfig(id).flatMap(_.map(cfg => wasmVmFor(cfg, maxCallsBetweenUpdates)).getOrElse(Future.successful(None)))
  }

  def wasmVmFor(config: WasmConfiguration, maxCallsBetweenUpdates: Int = 100000): Future[Option[(WasmVm, WasmConfiguration)]] = {
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) flatMap {
        case None => None.vfuture
        case Some(localConfig) => {
          localConfig.pool(maxCallsBetweenUpdates).getPooledVm().map(vm => Some((vm, localConfig)))
        }
      }
    } else {
      config.pool(maxCallsBetweenUpdates).getPooledVm().map(vm => Some((vm, config)))
    }
  }

  def withPooledVmSync[A](config: WasmConfiguration, maxCallsBetweenUpdates: Int = 100000, options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => A): Future[A] = {
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) flatMap {
        case None => Future.failed(new RuntimeException(s"no wasm config. found with path '${config.source.path}'"))
        case Some(localConfig) => localConfig.pool(maxCallsBetweenUpdates).withPooledVm(options)(f)
      }
    } else {
      config.pool(maxCallsBetweenUpdates).withPooledVm(options)(f)
    }
  }

  // borrow a vm for async operations
  def withPooledVm[A](config: WasmConfiguration, maxCallsBetweenUpdates: Int = 100000, options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => Future[A]): Future[A] = {
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) flatMap {
        case None => Future.failed(new RuntimeException(s"no wasm config. found with path '${config.source.path}'"))
        case Some(localConfig) => localConfig.pool(maxCallsBetweenUpdates).withPooledVmF(options)(f)
      }
    } else {
      config.pool(maxCallsBetweenUpdates).withPooledVmF(options)(f)
    }
  }

  /// Jobs api

  def startF(cleanerJobConfig: JsObject = Json.obj()): Future[Unit] = Future(start(cleanerJobConfig))

  def start(cleanerJobConfig: JsObject): Unit = {
    schedRef.set(scheduler.scheduleWithFixedDelay(() => {
      runVmLoaderJob()
      runVmCleanerJob(cleanerJobConfig)
    }, 1000, context.wasmCacheTtl, TimeUnit.MILLISECONDS))
  }

  def stopF(): Future[Unit] = Future(stop())

  def stop(): Unit = {
    Option(schedRef.get()).foreach(_.cancel(false))
  }

  def runVmLoaderJob(): Future[Unit] = {
    for {
      inlineSources <- ic.inlineWasmSources()
      pluginSources <- ic.wasmConfigs().map(_.map(_.source))
    } yield {
      val sources = (pluginSources ++ inlineSources).distinct
      sources.foreach { source =>
        val now = System.currentTimeMillis()
        ic.wasmScriptCache.get(source.cacheKey) match {
          case None => source.getWasm()
          case Some(CacheableWasmScript.CachedWasmScript(_, createAt)) if (createAt + ic.wasmCacheTtl) < now =>
            source.getWasm()
          case Some(CacheableWasmScript.CachedWasmScript(_, createAt))
            if (createAt + ic.wasmCacheTtl) > now && (createAt + ic.wasmCacheTtl + 1000) < now =>
            source.getWasm()
          case _ => ()
        }
      }
    }
  }

  def runVmCleanerJob(config: JsObject): Future[Unit] = {
    val globalNotUsedDuration = config.select("not-used-duration").asOpt[Long].map(v => v.millis).getOrElse(5.minutes)
    io.otoroshi.common.wasm.impl.WasmVmPoolImpl.allInstances().foreach { case (key, pool) =>
      if (pool.inUseVms.isEmpty && pool.availableVms.isEmpty) {
        ic.logger.warn(s"will destroy 1 wasm vms pool")
        pool.destroyCurrentVms()
        pool.close()
        io.otoroshi.common.wasm.impl.WasmVmPoolImpl.removePlugin(key)
      } else {
        val options = pool.wasmConfig().map(_.killOptions)
        if (!options.exists(_.immortal)) {
          val maxDur = options.map(_.maxUnusedDuration).getOrElse(globalNotUsedDuration)
          val unusedVms = pool.availableVms.asScala.filter(_.hasNotBeenUsedInTheLast(maxDur))
          val tooMuchMemoryVms = (pool.availableVms.asScala ++ pool.inUseVms.asScala)
            .filter(_.consumesMoreThanMemoryPercent(options.map(_.maxMemoryUsage).getOrElse(0.9)))
          val tooSlowVms = (pool.availableVms.asScala ++ pool.inUseVms.asScala)
            .filter(_.tooSlow(options.map(_.maxAvgCallDuration.toNanos).getOrElse(1.day.toNanos)))
          val allVms = unusedVms ++ tooMuchMemoryVms ++ tooSlowVms
          if (allVms.nonEmpty) {
            ic.logger.warn(s"will destroy ${allVms.size} wasm vms")
            if (unusedVms.nonEmpty) ic.logger.warn(s" - ${unusedVms.size} because unused for more than ${maxDur.toHours}")
            if (tooMuchMemoryVms.nonEmpty) ic.logger.warn(s" - ${tooMuchMemoryVms.size} because of too much memory used")
            if (tooSlowVms.nonEmpty) ic.logger.warn(s" - ${tooSlowVms.size} because of avg call duration too long")
          }
          allVms.foreach { vm =>
            if (vm.isBusy()) {
              vm.destroyAtRelease()
            } else {
              vm.ignore()
              vm.destroy()
            }
          }
        }
      }
    }
    ().vfuture
  }
}
