package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasmotoroshi._
import org.extism.sdk.wasm.WasmSourceResolver
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.proxywasm.VmData
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.Cookie

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

sealed trait WasmAction

object WasmAction {
  case class WasmOpaInvocation(call: () => Either[JsValue, String], promise: Promise[Either[JsValue, String]])
      extends WasmAction
  case class WasmInvocation(
      call: () => Either[JsValue, (String, ResultsWrapper)],
      promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
  )                                       extends WasmAction
  case class WasmUpdate(call: () => Unit) extends WasmAction
}

class WasmContextSlot(
    id: String,
    instance: Int,
    plugin: WasmOtoroshiInstance,
    cfg: WasmConfig,
    wsm: ByteString,
    closed: AtomicBoolean,
    updating: AtomicBoolean,
    instanceId: String,
    functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
) {

  def callSync(
      wasmFunctionParameters: WasmFunctionParameters,
      context: Option[VmData]
  )(implicit env: Env, ec: ExecutionContext): Either[JsValue, (String, ResultsWrapper)] = {
    if (closed.get()) {
      val plug = WasmUtils.pluginCache.apply(s"$id-$instance")
      plug.callSync(wasmFunctionParameters, context)
    } else {
      try {
        // context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
        if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling instance $id-$instance")
        WasmUtils.debugLog.debug(s"calling '${wasmFunctionParameters.functionName}' on instance '$id-$instance'")
        val res: Either[JsValue, (String, ResultsWrapper)] = env.metrics.withTimer("otoroshi.wasm.core.call") {
          wasmFunctionParameters.call(plugin)
        }
        env.metrics.withTimer("otoroshi.wasm.core.reset") {
          plugin.reset()
        }
        env.metrics.withTimer("otoroshi.wasm.core.count-thunks") {
          WasmUtils.logger.debug(s"thunks: ${functions.size}")
        }
        res
      } catch {
        case e: Throwable if e.getMessage.contains("wasm backtrace") =>
          WasmUtils.logger.error(s"error while invoking wasm function '${wasmFunctionParameters.functionName}'", e)
          Json
            .obj(
              "error"             -> "wasm_error",
              "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
            )
            .left
        case e: Throwable                                            =>
          WasmUtils.logger.error(s"error while invoking wasm function '${wasmFunctionParameters.functionName}'", e)
          Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
      } finally {
        // context.foreach(ctx => WasmContextSlot.clearCurrentContext())
      }
    }
  }

  def callOpaSync(input: String)(implicit env: Env, ec: ExecutionContext): Either[JsValue, String] = {
    if (closed.get()) {
      val plug = WasmUtils.pluginCache.apply(s"$id-$instance")
      plug.callOpaSync(input)
    } else {
      try {
        val res = env.metrics.withTimer("otoroshi.wasm.core.call-opa") {
          val result = OPA.initialize(plugin).right
          val str    = result.get._1
          val parts  = str.split("@")
          OPA
            .evaluate(plugin, parts(0).toInt, parts(1).toInt, input)
            .map(r => r._1)
        }
        res
      } catch {
        case e: Throwable if e.getMessage.contains("wasm backtrace") =>
          WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
          Json
            .obj(
              "error"             -> "wasm_error",
              "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
            )
            .left
        case e: Throwable                                            =>
          WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
          Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
      }
    }
  }

  def call(
      wasmFunctionParameters: WasmFunctionParameters,
      context: Option[VmData]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise.apply[Either[JsValue, (String, ResultsWrapper)]]()
    WasmUtils
      .getInvocationQueueFor(id, instance)
      .offer(WasmAction.WasmInvocation(() => callSync(wasmFunctionParameters, context), promise))
    promise.future
  }

  def callOpa(input: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val promise = Promise.apply[Either[JsValue, String]]()
    WasmUtils.getInvocationQueueFor(id, instance).offer(WasmAction.WasmOpaInvocation(() => callOpaSync(input), promise))
    promise.future
  }

  def close(lifetime: WasmVmLifetime): Unit = {
    if (lifetime == WasmVmLifetime.Invocation) {
      if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling close on WasmContextSlot of ${id}")
      forceClose()
    }
  }

  def forceClose(): Unit = {
    if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling forceClose on WasmContextSlot of ${id}")
    if (closed.compareAndSet(false, true)) {
      try {
        plugin.close()
      } catch {
        case e: Throwable => e.printStackTrace()
      }
    }
  }

  def needsUpdate(wasmConfig: WasmConfig, wasm: ByteString): Boolean = {
    val configHasChanged = wasmConfig != cfg
    val wasmHasChanged   = wasm != wsm
    if (WasmUtils.logger.isDebugEnabled && configHasChanged)
      WasmUtils.logger.debug(s"plugin ${id} needs update because of config change")
    if (WasmUtils.logger.isDebugEnabled && wasmHasChanged)
      WasmUtils.logger.debug(s"plugin ${id} needs update because of wasm change")
    configHasChanged || wasmHasChanged
  }

  def updateIfNeeded(
      pluginId: String,
      config: WasmConfig,
      wasm: ByteString,
      attrsOpt: Option[TypedMap],
      addHostFunctions: Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
  )(implicit env: Env, ec: ExecutionContext): WasmContextSlot = {
    if (needsUpdate(config, wasm) && updating.compareAndSet(false, true)) {

      if (config.instances < cfg.instances) {
        env.otoroshiActorSystem.scheduler.scheduleOnce(20.seconds) { // TODO: config ?
          if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"trying to kill unused instances of ${pluginId}")
          (config.instances to cfg.instances).map { idx =>
            WasmUtils.pluginCache.get(s"${pluginId}-${instance}").foreach(p => p.forceClose())
            WasmUtils.queues.remove(s"${pluginId}-${instance}")
            WasmUtils.pluginCache.remove(s"$pluginId-$instance")
          }
        }
      }
      if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"scheduling update ${instanceId}")
      WasmUtils
        .getInvocationQueueFor(id, instance)
        .offer(WasmAction.WasmUpdate(() => {
          val plugin = WasmUtils.actuallyCreatePlugin(
            instance,
            wasm,
            config,
            pluginId,
            attrsOpt,
            addHostFunctions
          )
          if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"updating ${instanceId}")
          WasmUtils.pluginCache.put(s"$pluginId-$instance", plugin)
          env.otoroshiActorSystem.scheduler.scheduleOnce(20.seconds) { // TODO: config ?
            if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"delayed force close ${instanceId}")
            if (!closed.get()) {
              forceClose()
            }
          }
        }))
    }
    this
  }
}

object WasmUtils {

  private[wasm] val logger = Logger("otoroshi-wasm")

  val debugLog = Logger("otoroshi-wasm-debug")

  implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  // TODO: handle env.wasmCacheSize based on creation date ?
  private[wasm] val _script_cache: UnboundedTrieMap[String, CacheableWasmScript] =
    new UnboundedTrieMap[String, CacheableWasmScript]()
  private[wasm] val pluginCache                                                  = new UnboundedTrieMap[String, WasmContextSlot]()
  private[wasm] val queues                                                       = new UnboundedTrieMap[String, (DateTime, SourceQueueWithComplete[WasmAction])]()
  private[wasm] val instancesCounter                                             = new AtomicInteger(0)

  def scriptCache(implicit env: Env): UnboundedTrieMap[String, CacheableWasmScript] = _script_cache

  def convertJsonCookies(wasmResponse: JsValue): Option[Seq[WSCookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          DefaultWSCookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Long],
            path = c.select("path").asOpt[String],
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false)
          )
        }
      }

  def convertJsonPlayCookies(wasmResponse: JsValue): Option[Seq[Cookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          Cookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Int],
            path = c.select("path").asOpt[String].getOrElse("/"),
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false),
            sameSite = c.select("domain").asOpt[String].flatMap(Cookie.SameSite.parse)
          )
        }
      }

  private[wasm] def getInvocationQueueFor(id: String, instance: Int)(implicit
      env: Env
  ): SourceQueueWithComplete[WasmAction] = {
    val key = s"$id-$instance"
    queues.getOrUpdate(key) {
      val stream = Source
        .queue[WasmAction](env.wasmQueueBufferSize, OverflowStrategy.dropHead)
        .mapAsync(1) { action =>
          Future.apply {
            action match {
              case WasmAction.WasmInvocation(invoke, promise)    =>
                try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
              case WasmAction.WasmOpaInvocation(invoke, promise) =>
                try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
              case WasmAction.WasmUpdate(update)                 =>
                try {
                  update()
                } catch {
                  case e: Throwable => e.printStackTrace()
                }
            }
          }(executor)
        }
      (DateTime.now(), stream.toMat(Sink.ignore)(Keep.both).run()(env.otoroshiMaterializer)._1)
    }
  }._2

  private[wasm] def internalCreateManifest(config: WasmConfig, wasm: ByteString, env: Env) =
    env.metrics.withTimer("otoroshi.wasm.core.create-plugin.manifest") {
      val resolver = new WasmSourceResolver()
      val source   = resolver.resolve("wasm", wasm.toByteBuffer.array())
      new Manifest(
        Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
        new MemoryOptions(config.memoryPages),
        config.config.asJava,
        config.allowedHosts.asJava,
        config.allowedPaths.asJava
      )
    }

  private[wasm] def actuallyCreatePlugin(
      instance: Int,
      wasm: ByteString,
      config: WasmConfig,
      pluginId: String,
      attrsOpt: Option[TypedMap],
      addHostFunctions: Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
  )(implicit env: Env, ec: ExecutionContext): WasmContextSlot =
    env.metrics.withTimer("otoroshi.wasm.core.act-create-plugin") {
      if (WasmUtils.logger.isDebugEnabled)
        WasmUtils.logger.debug(s"creating wasm plugin instance for ${pluginId}")
      val engine                                                                    = WasmVmPool.engine
      val manifest                                                                  = internalCreateManifest(config, wasm, env)
      val hash                                                                      = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(wasm.toArray)
        .map("%02x".format(_))
        .mkString
      val template                                                                  = new WasmOtoroshiTemplate(engine, hash, manifest)
      // val context                                           = env.metrics.withTimer("otoroshi.wasm.core.create-plugin.context")(new Context())
      val functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] =
        HostFunctions.getFunctions(config, pluginId, attrsOpt) ++ addHostFunctions
      val plugin                                                                    = env.metrics.withTimer("otoroshi.wasm.core.create-plugin.plugin") {
        template.instantiate(
          engine,
          functions,
          LinearMemories.getMemories(config),
          config.wasi
        )
      }
      new WasmContextSlot(
        pluginId,
        instance,
        plugin,
        config,
        wasm,
        functions = functions,
        closed = new AtomicBoolean(false),
        updating = new AtomicBoolean(false),
        instanceId = IdGenerator.uuid
      )
    }

  private def callWasm(
      wasm: ByteString,
      config: WasmConfig,
      wasmFunctionParameters: WasmFunctionParameters,
      pluginId: String,
      attrsOpt: Option[TypedMap],
      ctx: Option[VmData],
      addHostFunctions: Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] =
    env.metrics.withTimerAsync("otoroshi.wasm.core.call-wasm") {

      WasmUtils.debugLog.debug("callWasm")

      val functionName = config.functionName.filter(_.nonEmpty).getOrElse(wasmFunctionParameters.functionName)
      val instance     = instancesCounter.incrementAndGet() % config.instances

      def createPlugin(): WasmContextSlot = {
        if (config.lifetime == WasmVmLifetime.Forever) {
          pluginCache
            .getOrUpdate(s"$pluginId-$instance") {
              actuallyCreatePlugin(instance, wasm, config, pluginId, None, addHostFunctions)
            }
            .seffectOn(_.updateIfNeeded(pluginId, config, wasm, None, addHostFunctions))
        } else {
          actuallyCreatePlugin(instance, wasm, config, pluginId, attrsOpt, addHostFunctions)
        }
      }

      attrsOpt match {
        case None        => {
          val slot = createPlugin()
          if (config.opa) {
            slot.callOpa(wasmFunctionParameters.input.get).map { output =>
              slot.close(config.lifetime)
              output.map(str => (str, ResultsWrapper(new WasmOtoroshiResults(0))))
            }
          } else {
            slot.call(wasmFunctionParameters, ctx).map { output =>
              slot.close(config.lifetime)
              output
            }
          }
        }
        case Some(attrs) => {
          val context = attrs.get(otoroshi.next.plugins.Keys.WasmContextKey) match {
            case None          => {
              val context = new WasmContext()
              attrs.put(otoroshi.next.plugins.Keys.WasmContextKey -> context)
              context
            }
            case Some(context) => context
          }
          val slot    = context.get(pluginId) match {
            case None         => {
              val plugin = createPlugin()
              if (config.lifetime == WasmVmLifetime.Invocation) context.put(pluginId, plugin)
              plugin
            }
            case Some(plugin) => plugin
          }
          if (config.opa) {
            slot.callOpa(wasmFunctionParameters.input.get).map { output =>
              slot.close(config.lifetime)
              output.map(str => (str, ResultsWrapper(new WasmOtoroshiResults(0))))
            }
          } else {
            slot.call(wasmFunctionParameters, ctx).map { output =>
              slot.close(config.lifetime)
              output
            }
          }
        }
      }
    }

  @deprecated(message = "Use WasmVmPool and WasmVm apis instead", since = "v16.6.0")
  def execute(
      config: WasmConfig,
      defaultFunctionName: String,
      input: JsValue,
      attrs: Option[TypedMap],
      ctx: Option[VmData]
  )(implicit env: Env): Future[Either[JsValue, String]] = {
    rawExecute(
      config,
      WasmFunctionParameters.ExtismFuntionCall(config.functionName.getOrElse(defaultFunctionName), input.stringify),
      attrs,
      ctx,
      Seq.empty
    ).map(r => r.map(_._1))
  }

  @deprecated(message = "Use WasmVmPool and WasmVm apis instead", since = "v16.6.0")
  def rawExecute(
      _config: WasmConfig,
      wasmFunctionParameters: WasmFunctionParameters,
      attrs: Option[TypedMap],
      ctx: Option[VmData],
      addHostFunctions: Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
  )(implicit env: Env): Future[Either[JsValue, (String, ResultsWrapper)]] =
    env.metrics.withTimerAsync("otoroshi.wasm.core.raw-execute") {
      val config   = _config // if (_config.opa) _config.copy(lifetime = WasmVmLifetime.Invocation) else _config
      WasmUtils.debugLog.debug("execute")
      val pluginId = config.source.kind match {
        case WasmSourceKind.Local => {
          env.proxyState.wasmPlugin(config.source.path) match {
            case None         => config.source.cacheKey
            case Some(plugin) => plugin.config.source.cacheKey
          }
        }
        case _                    => config.source.cacheKey
      }
      scriptCache.get(pluginId) match {
        case Some(CacheableWasmScript.FetchingWasmScript(fu))      =>
          fu.flatMap { _ =>
            rawExecute(config, wasmFunctionParameters, attrs, ctx, addHostFunctions)
          }
        case Some(CacheableWasmScript.CachedWasmScript(script, _)) => {
          env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
            case None              =>
              WasmUtils.callWasm(
                script,
                config,
                wasmFunctionParameters,
                pluginId,
                attrs,
                ctx,
                addHostFunctions
              )
            case Some(finalConfig) =>
              val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
              WasmUtils.callWasm(
                script,
                finalConfig.copy(functionName = functionName),
                wasmFunctionParameters.withFunctionName(functionName.getOrElse(wasmFunctionParameters.functionName)),
                pluginId,
                attrs,
                ctx,
                addHostFunctions
              )
          }
        }
        case None if config.source.kind == WasmSourceKind.Unknown  => Left(Json.obj("error" -> "missing source")).future
        case _                                                     =>
          env.metrics.withTimerAsync("otoroshi.wasm.core.get-wasm")(config.source.getWasm()).flatMap {
            case Left(err)   => err.left.vfuture
            case Right(wasm) => {
              env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
                case None              =>
                  WasmUtils.callWasm(
                    wasm,
                    config,
                    wasmFunctionParameters,
                    pluginId,
                    attrs,
                    ctx,
                    addHostFunctions
                  )
                case Some(finalConfig) =>
                  val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
                  WasmUtils.callWasm(
                    wasm,
                    finalConfig.copy(functionName = functionName),
                    wasmFunctionParameters
                      .withFunctionName(functionName.getOrElse(wasmFunctionParameters.functionName)),
                    pluginId,
                    attrs,
                    ctx,
                    addHostFunctions
                  )
              }
            }
          }
      }
    }
}
