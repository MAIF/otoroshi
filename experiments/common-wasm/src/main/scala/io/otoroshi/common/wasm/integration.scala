package io.otoroshi.common.wasm

import akka.stream.Materializer
import io.otoroshi.common.utils.implicits._
import org.extism.sdk.wasmotoroshi.{WasmOtoroshiHostFunction, WasmOtoroshiHostUserData}
import play.api.Logger
import play.api.libs.json.JsObject
import play.api.libs.ws.WSRequest

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait AbsVmData {
  def properties: Map[String, Array[Byte]]
}

trait WasmIntegrationContext {

  def logger: Logger
  def materializer: Materializer
  def executionContext: ExecutionContext

  def wasmCacheTtl: Long
  def wasmQueueBufferSize: Int

  def url(path: String): WSRequest
  def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest

  def wasmManagerSettings: Future[Option[WasmManagerSettings]]
  def wasmConfig(path: String): Option[WasmConfiguration]
  def wasmConfigs(): Seq[WasmConfiguration]
  def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]

  def wasmScriptCache: TrieMap[String, CacheableWasmScript]
  def wasmExecutor: ExecutionContext
}

object WasmIntegration {
  def apply(ic: WasmIntegrationContext): WasmIntegration = new WasmIntegration(ic)
}

class WasmIntegration(ic: WasmIntegrationContext) {

  implicit val context: WasmIntegrationContext = ic

  private lazy val scheduler = Executors.newScheduledThreadPool(2)
  private lazy val schedRef = new AtomicReference[ScheduledFuture[_]](null)

  /// vm apis

  private[common] def wasmVmById(id: String): Future[Option[(WasmVm, WasmConfiguration)]] = {
    ic.wasmConfig(id).map(cfg => wasmVmFor(cfg)).getOrElse(Future.successful(None))
  }

  def wasmVmFor(config: WasmConfiguration): Future[Option[(WasmVm, WasmConfiguration)]] = {
    implicit val ec = ic.executionContext
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) match {
        case None => None.vfuture
        case Some(localConfig) => {
          localConfig.pool().getPooledVm().map(vm => Some((vm, localConfig)))
        }
      }
    } else {
      config.pool().getPooledVm().map(vm => Some((vm, config)))
    }
  }

  def withPooledVmSync[A](config: WasmConfiguration, options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => A): Future[A] = {
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) match {
        case None => Future.failed(new RuntimeException(s"no wasm config. found with path '${config.source.path}'"))
        case Some(localConfig) => localConfig.pool().withPooledVm(options)(f)
      }
    } else {
      config.pool().withPooledVm(options)(f)
    }
  }

  // borrow a vm for async operations
  def withPooledVm[A](config: WasmConfiguration, options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => Future[A]): Future[A] = {
    if (config.source.kind == WasmSourceKind.Local) {
      ic.wasmConfig(config.source.path) match {
        case None => Future.failed(new RuntimeException(s"no wasm config. found with path '${config.source.path}'"))
        case Some(localConfig) => localConfig.pool().withPooledVmF(options)(f)
      }
    } else {
      config.pool().withPooledVmF(options)(f)
    }
  }

  /// Jobs api

  def start(cleanerJobConfig: JsObject): Unit = {
    schedRef.set(scheduler.scheduleWithFixedDelay(() => {
      runVmLoaderJob()
      runVmCleanerJob(cleanerJobConfig)
    }, 1000, 30000, TimeUnit.MILLISECONDS))
  }

  def stop(): Unit = {
    Option(schedRef.get()).foreach(_.cancel(false))
  }

  def runVmLoaderJob(): Future[Unit] = {
    implicit val ec = ic.executionContext
    ic.wasmConfigs().foreach { plugin =>
      val now = System.currentTimeMillis()
      ic.wasmScriptCache.get(plugin.source.cacheKey) match {
        case None => plugin.source.getWasm()
        case Some(CacheableWasmScript.CachedWasmScript(_, createAt)) if (createAt + ic.wasmCacheTtl) < now =>
          plugin.source.getWasm()
        case Some(CacheableWasmScript.CachedWasmScript(_, createAt))
          if (createAt + ic.wasmCacheTtl) > now && (createAt + ic.wasmCacheTtl + 1000) < now =>
          plugin.source.getWasm()
        case _ => ()
      }
    }
    ().vfuture
  }

  def runVmCleanerJob(config: JsObject): Future[Unit] = {
    val globalNotUsedDuration = config.select("not-used-duration").asOpt[Long].map(v => v.millis).getOrElse(5.minutes)
    WasmVmPool.allInstances().foreach { case (key, pool) =>
      if (pool.inUseVms.isEmpty && pool.availableVms.isEmpty) {
        ic.logger.warn(s"will destroy 1 wasm vms pool")
        pool.destroyCurrentVms()
        pool.close()
        WasmVmPool.removePlugin(key)
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
