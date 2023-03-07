package next.plugins

import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import akka.util.ByteString
import org.extism.sdk._
import org.joda.time.DateTime
import otoroshi.cluster.ClusterConfig
import otoroshi.env.Env
import otoroshi.events.{Location, WasmLogEvent}
import otoroshi.next.plugins.WasmConfig
import otoroshi.next.plugins.api.NgCachedConfigContext
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{JsArray, JsNull, Json}

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext}

object Utils {
    def rawBytePtrToString(plugin: ExtismCurrentPlugin, offset: Long, arrSize: Int): String = {
        val memoryLength = LibExtism.INSTANCE.extism_current_plugin_memory_length(plugin.pointer, arrSize)
        val arr = plugin.memory().share(offset, memoryLength)
                .getByteArray(0, arrSize)
        new String(arr, StandardCharsets.UTF_8)
    }
}

case class EnvUserData(
                        env: Env,
                        executionContext: ExecutionContext,
                        mat: Materializer,
                        config: WasmConfig,
                        ctx: Option[NgCachedConfigContext] = None
                      ) extends HostUserData

case class StateUserData(
                        env: Env,
                        executionContext: ExecutionContext,
                        mat: Materializer,
                        cache: LegitTrieMap[String, LegitTrieMap[String, ByteString]]
                      ) extends HostUserData
case class EmptyUserData() extends HostUserData

object LogLevel extends Enumeration {
  type LogLevel = Value

  val LogLevelTrace,
  LogLevelDebug,
  LogLevelInfo,
  LogLevelWarn,
  LogLevelError,
  LogLevelCritical,
  LogLevelMax = Value
}

object Status extends Enumeration {
  type Status = Value

  val StatusOK,
    StatusNotFound,
    StatusBadArgument,
    StatusEmpty,
    StatusCasMismatch,
    StatusInternalFailure,
    StatusUnimplemented = Value
}

object Logging {

    def proxyLogFunction(): ExtismFunction[EmptyUserData] =
      (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EmptyUserData]) => {
      val logLevel = LogLevel(params(0).v.i32)
      val messageData  = Utils.rawBytePtrToString(plugin, params(1).v.i64, params(2).v.i32)

        System.out.println(String.format("[%s]: %s", logLevel.toString, messageData))

        returns(0).v.i32 = Status.StatusOK.id
    }
    
    def proxyLogWithEventFunction()
                                 (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
      (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], hostData: Optional[EnvUserData]) => {
        hostData
          .ifPresent(ud => {
            val data  = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

            val event = WasmLogEvent(
              `@id` = ud.env.snowflakeGenerator.nextIdStr(),
              `@service` = ud.ctx.map(e => e.route.name).getOrElse(""),
              `@serviceId` = ud.ctx.map(e => e.route.id).getOrElse(""),
              `@timestamp` = DateTime.now(),
              route = ud.ctx.map(_.route),
              fromFunction = (data \ "function").asOpt[String].getOrElse("unknown function"),
              message = (data \ "message").asOpt[String].getOrElse("--"),
              level = (data \ "level").asOpt[Int].map(r => LogLevel(r)).getOrElse(LogLevel.LogLevelDebug).toString,
            )

            event.toAnalytics()

            returns(0).v.i32 = Status.StatusOK.id
        })
    }

    def proxyLog() = new org.extism.sdk.HostFunction[EmptyUserData](
            "proxy_log",
            Array(LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I64,LibExtism.ExtismValType.I32),
            Array(LibExtism.ExtismValType.I32),
            proxyLogFunction,
            Optional.of(EmptyUserData())
    )

    def proxyLogWithEvent(config: WasmConfig, ctx: Option[NgCachedConfigContext])
                         (implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = new org.extism.sdk.HostFunction[EnvUserData](
        "proxy_log_event",
        Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
        Array(LibExtism.ExtismValType.I32),
        proxyLogWithEventFunction,
        Optional.of(EnvUserData(env, executionContext, mat, config, ctx))
    )

    def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext])
                    (implicit env: Env, executionContext: ExecutionContext, mat: Materializer)
    = Seq(proxyLog(), proxyLogWithEvent(config, ctx))
}

object Http {
    def httpCallFunction: ExtismFunction[EnvUserData] =
      (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
        data.ifPresent(hostData => {
          implicit val ec  = hostData.executionContext
          implicit val mat  = hostData.mat

          val context = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

          val builder = hostData.env
            .Ws
            .url((context \ "url").asOpt[String].getOrElse("mirror.otoroshi.io"))
            .withMethod((context \ "method").asOpt[String].getOrElse("GET"))
            .withHttpHeaders((context \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
            .withRequestTimeout(Duration((context \ "request_timeout").asOpt[Long].getOrElse(hostData.env.clusterConfig.worker.timeout), TimeUnit.MILLISECONDS))
            .withFollowRedirects((context \ "follow_redirects").asOpt[Boolean].getOrElse(false))
            .withQueryStringParameters((context \ "query").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)

          val request = (context \ "body").asOpt[String] match {
            case Some(body) => builder.withBody(body)
            case None => builder
          }

          val out = Await.result(request
            .stream()
            .fast
            .flatMap { res =>
              res.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map { body =>
                Json.obj(
                  "status" -> res.status,
                  "headers" -> res.headers
                    .mapValues(_.head)
                    .toSeq
                    .filter(_._1 != "Content-Type")
                    .filter(_._1 != "Content-Length")
                    .filter(_._1 != "Transfer-Encoding"),
                  "body" -> body
                )
              }
            }, Duration(hostData.config.proxyHttpCallTimeout, TimeUnit.MILLISECONDS))

          plugin.returnString(returns(0), Json.stringify(out))
        })
      }

    def proxyHttpCall(config: WasmConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
        new HostFunction[EnvUserData](
                "proxy_http_call",
                Array(LibExtism.ExtismValType.I64,LibExtism.ExtismValType.I32),
                Array(LibExtism.ExtismValType.I64),
                httpCallFunction,
                Optional.of(EnvUserData(env, executionContext, mat, config))
        )
    }

    def getFunctions(config: WasmConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = Seq(proxyHttpCall(config))
}

object DataStore {
  def proxyDataStoreKeysFunction(prefix: Option[String])
                                (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore.keys(s"${hostData.env.storageRoot}:$path$key")
          .map(values => {
            plugin.returnString(returns(0),  Json.arr(values).stringify)
          }), 5.seconds)
      })
    }
  def proxyDataStoreGetFunction(prefix: Option[String])
                               (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore.get(s"${hostData.env.storageRoot}:$path$key")
          .map(value => {
            plugin.returnBytes(returns(0), value.map(_.toArray).getOrElse(Array.empty[Byte]))
          }), 5.seconds)
      })
    }
  def proxyDataStoreExistsFunction(prefix: Option[String])
                                  (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore.exists(s"${hostData.env.storageRoot}:$path$key")
          .map(value => {
            plugin.returnString(returns(0), value.toString)
          }), 5.seconds)
      })
    }
  def proxyDataStorePttlFunction(prefix: Option[String])
                                (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore.pttl(s"${hostData.env.storageRoot}:$path$key")
          .map(value => {
            plugin.returnString(returns(0), value.toString)
          }), 5.seconds)
      })
    }
  def proxyDataStoreSetnxFunction(prefix: Option[String])
                                 (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        val key = (data \ "key").as[String]
        val value = (data \ "value").as[String]
        val ttl = (data \ "ttl").asOpt[Long]

        Await.result(hostData.env.datastores.rawDataStore.setnx(s"${hostData.env.storageRoot}:$path$key", ByteString(value), ttl)
          .map(value => {
            plugin.returnString(returns(0), value.toString)
          }), 5.seconds)
      })
    }
  def proxyDataStoreDelFunction(prefix: Option[String])
                               (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore
          .del((data \ "keys").asOpt[Seq[String]].getOrElse(Seq.empty).map(r => s"${hostData.env.storageRoot}:$path$r"))
          .map(value => {
            plugin.returnString(returns(0), value.toString)
          }), 5.seconds)
      })
    }
  def proxyDataStoreIncrbyFunction(prefix: Option[String])
                                  (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        val key = (data \ "key").as[String]
        val incr = (data \ "incr").as[Long]

        Await.result(hostData.env.datastores.rawDataStore.incrby(s"${hostData.env.storageRoot}:$path$key", incr)
          .map(value => {
            plugin.returnString(returns(0), value.toString)
          }), 5.seconds)
      })
    }
  def proxyDataStorePexpireFunction(prefix: Option[String])
                                   (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
    data.ifPresent(hostData => {
      val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))
      val path = prefix.map(p => s"wasm:$p:").getOrElse("")

      val key = (data \ "key").as[String]
      val pttl = (data \ "pttl").as[Long]

      Await.result(hostData.env.datastores.rawDataStore.pexpire(s"${hostData.env.storageRoot}:$path$key", pttl)
        .map(value => {
          plugin.returnString(returns(0), value.toString)
        }), 5.seconds)
    })
  }
  def proxyDataStoreAllMatchingFunction(prefix: Option[String])
                                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
        val path = prefix.map(p => s"wasm:$p:").getOrElse("")

        Await.result(hostData.env.datastores.rawDataStore.allMatching(s"${hostData.env.storageRoot}:$path$key")
          .map(values => {
            plugin.returnString(returns(0), Json.arr(values).stringify)
          }), 5.seconds)
      })
    }

  def proxyDataStoreKeys(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                        (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_keys",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreKeysFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreGet(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_get",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreGetFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreExists(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                          (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_exists",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreExistsFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStorePttl(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                        (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_pttl",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStorePttlFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreSetnx(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                         (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_setnx",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreSetnxFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreDel(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_del",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreDelFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreIncrby(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                          (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_incrby",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreIncrbyFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStorePexpire(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                           (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_keys_pexpire",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStorePexpireFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyDataStoreAllMatching(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)
                               (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    new HostFunction[EnvUserData](
      s"proxy_${prefixName}datastore_all_matching",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyDataStoreAllMatchingFunction(prefix),
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def getFunctions(config: WasmConfig, pluginId: String)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = Seq(
    proxyDataStoreKeys(config = config),
    proxyDataStoreGet(config = config),
    proxyDataStoreExists(config = config),
    proxyDataStorePttl(config = config),
    proxyDataStoreSetnx(config = config),
    proxyDataStoreDel(config = config),
    proxyDataStoreIncrby(config = config),
    proxyDataStorePexpire(config = config),
    proxyDataStoreAllMatching(config = config),

    proxyDataStoreKeys(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreGet(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreExists(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStorePttl(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreSetnx(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreDel(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreIncrby(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStorePexpire(config = config, pluginRestricted = true, prefix = pluginId.some),
    proxyDataStoreAllMatching(config = config, pluginRestricted = true, prefix = pluginId.some)
  )
}

object State {

  private val cache: LegitTrieMap[String, LegitTrieMap[String, ByteString]] = new LegitTrieMap[String, LegitTrieMap[String, ByteString]]()

  def getClusterState(cc: ClusterConfig) = Json.obj(
    "mode" -> Json.obj(
      "name" -> cc.mode.name,
      "clusterActive" -> cc.mode.clusterActive,
      "isOff" -> cc.mode.isOff,
      "isWorker" -> cc.mode.isWorker,
      "isLeader" -> cc.mode.isLeader,
    ),
    "compression" -> cc.compression,
    "proxy" -> Json.obj(
      "host" -> cc.proxy.map(_.host),
      "port" -> cc.proxy.map(_.port),
      "protocol" -> cc.proxy.map(_.protocol),
      "principal" -> cc.proxy.map(_.principal),
      "password" -> cc.proxy.map(_.password),
      "ntlmDomain" -> cc.proxy.map(_.ntlmDomain),
      "encoding" -> cc.proxy.map(_.encoding),
      "nonProxyHosts" -> cc.proxy.map(_.nonProxyHosts),
    ),
    "mtlsConfig" -> cc.mtlsConfig.json,
    "streamed" -> cc.streamed,
    "relay" -> cc.relay.json,
    "retryDelay" -> cc.retryDelay,
    "retryFactor" -> cc.retryFactor,
    "leader" -> Json.obj(
      "name" -> cc.leader.name,
      "urls" -> cc.leader.urls,
      "host" -> cc.leader.host,
      "clientId" -> cc.leader.clientId,
      "clientSecret" -> cc.leader.clientSecret,
      "groupingBy" -> cc.leader.groupingBy,
      "cacheStateFor" -> cc.leader.cacheStateFor,
      "stateDumpPath" -> cc.leader.stateDumpPath
    ),
    "worker" -> Json.obj(
      "name" -> cc.worker.name,
      "retries" -> cc.worker.retries,
      "timeout" -> cc.worker.timeout,
      "dataStaleAfter" -> cc.worker.dataStaleAfter,
      "dbPath" -> cc.worker.dbPath,
      "state" -> Json.obj(
        "timeout" -> cc.worker.state.timeout,
        "pollEvery" -> cc.worker.state.pollEvery,
        "retries" -> cc.worker.state.retries
      ),
      "quotas" -> Json.obj(
        "timeout" -> cc.worker.quotas.timeout,
        "pushEvery" -> cc.worker.quotas.pushEvery,
        "retries" -> cc.worker.quotas.retries
      ),
      "tenants" -> cc.worker.tenants.map(_.value),
      "swapStrategy" -> cc.worker.swapStrategy.name
    )
  )

  def getProxyStateFunction: ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val proxyState = hostData.env.proxyState

        val state = Json.obj(
          "raw_routes" -> JsArray(proxyState.allRawRoutes().map(_.json)),
          "routes" -> JsArray(proxyState.allRoutes().map(_.json)),
          "routeCompositions" -> JsArray(proxyState.allRouteCompositions().map(_.json)),
          "apikeys" -> JsArray(proxyState.allApikeys().map(_.json)),
          "ngbackends" -> JsArray(proxyState.allBackends().map(_.json)),
          "jwtVerifiers" -> JsArray(proxyState.allJwtVerifiers().map(_.json)),
          "certificates" -> JsArray(proxyState.allCertificates().map(_.json)),
          "authModules" -> JsArray(proxyState.allAuthModules().map(_.json)),
          "services" -> JsArray(proxyState.allServices().map(_.json)),
          "teams" -> JsArray(proxyState.allTeams().map(_.json)),
          "tenants" -> JsArray(proxyState.allTenants().map(_.json)),
          "serviceGroups" -> JsArray(proxyState.allServiceGroups().map(_.json)),
          "dataExporters" -> JsArray(proxyState.allDataExporters().map(_.json)),
          "otoroshiAdmins" -> JsArray(proxyState.allOtoroshiAdmins().map(_.json)),
          "backofficeSessions" -> JsArray(proxyState.allBackofficeSessions().map(_.json)),
          "privateAppsSessions" -> JsArray(proxyState.allPrivateAppsSessions().map(_.json)),
          "tcpServices" -> JsArray(proxyState.allTcpServices().map(_.json)),
          "scripts" -> JsArray(proxyState.allScripts().map(_.json)),
        ).stringify

        plugin.returnString(returns(0), state)
      })
    }

  def proxyStateGetValueFunction: ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(userData => {
        val context = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

        val entity = (context \ "entity").asOpt[String].getOrElse("")
        val id: Option[String] = (context \ "id").asOpt[String]

        plugin.returnString(returns(0), ((entity, id) match {
          case ("raw_routes", None) => JsArray(userData.env.proxyState.allRawRoutes().map(_.json))
          case ("routes", None) => JsArray(userData.env.proxyState.allRoutes().map(_.json))
          case ("routeCompositions", None) => JsArray(userData.env.proxyState.allRouteCompositions().map(_.json))
          case ("apikeys", None) => JsArray(userData.env.proxyState.allApikeys().map(_.json))
          case ("ngbackends", None) => JsArray(userData.env.proxyState.allBackends().map(_.json))
          case ("jwtVerifiers", None) => JsArray(userData.env.proxyState.allJwtVerifiers().map(_.json))
          case ("certificates", None) => JsArray(userData.env.proxyState.allCertificates().map(_.json))
          case ("authModules", None) => JsArray(userData.env.proxyState.allAuthModules().map(_.json))
          case ("services", None) => JsArray(userData.env.proxyState.allServices().map(_.json))
          case ("teams", None) => JsArray(userData.env.proxyState.allTeams().map(_.json))
          case ("tenants", None) => JsArray(userData.env.proxyState.allTenants().map(_.json))
          case ("serviceGroups", None) => JsArray(userData.env.proxyState.allServiceGroups().map(_.json))
          case ("dataExporters", None) => JsArray(userData.env.proxyState.allDataExporters().map(_.json))
          case ("otoroshiAdmins", None) => JsArray(userData.env.proxyState.allOtoroshiAdmins().map(_.json))
          case ("backofficeSessions", None) => JsArray(userData.env.proxyState.allBackofficeSessions().map(_.json))
          case ("privateAppsSessions", None) => JsArray(userData.env.proxyState.allPrivateAppsSessions().map(_.json))
          case ("tcpServices", None) => JsArray(userData.env.proxyState.allTcpServices().map(_.json))
          case ("scripts", None) => JsArray(userData.env.proxyState.allScripts().map(_.json))

          case ("raw_routes", Some(key)) => userData.env.proxyState.rawRoute(key).map(_.json).getOrElse(JsNull)
          case ("routes", Some(key)) => userData.env.proxyState.route(key).map(_.json).getOrElse(JsNull)
          case ("routeCompositions", Some(key)) => userData.env.proxyState.routeComposition(key).map(_.json).getOrElse(JsNull)
          case ("apikeys", Some(key)) => userData.env.proxyState.apikey(key).map(_.json).getOrElse(JsNull)
          case ("ngbackends", Some(key)) => userData.env.proxyState.backend(key).map(_.json).getOrElse(JsNull)
          case ("jwtVerifiers", Some(key)) => userData.env.proxyState.jwtVerifier(key).map(_.json).getOrElse(JsNull)
          case ("certificates", Some(key)) => userData.env.proxyState.certificate(key).map(_.json).getOrElse(JsNull)
          case ("authModules", Some(key)) => userData.env.proxyState.authModule(key).map(_.json).getOrElse(JsNull)
          case ("services", Some(key)) => userData.env.proxyState.service(key).map(_.json).getOrElse(JsNull)
          case ("teams", Some(key)) => userData.env.proxyState.team(key).map(_.json).getOrElse(JsNull)
          case ("tenants", Some(key)) => userData.env.proxyState.tenant(key).map(_.json).getOrElse(JsNull)
          case ("serviceGroups", Some(key)) => userData.env.proxyState.serviceGroup(key).map(_.json).getOrElse(JsNull)
          case ("dataExporters", Some(key)) => userData.env.proxyState.dataExporter(key).map(_.json).getOrElse(JsNull)
          case ("otoroshiAdmins", Some(key)) => userData.env.proxyState.otoroshiAdmin(key).map(_.json).getOrElse(JsNull)
          case ("backofficeSessions", Some(key)) => userData.env.proxyState.backofficeSession(key).map(_.json).getOrElse(JsNull)
          case ("privateAppsSessions", Some(key)) => userData.env.proxyState.privateAppsSession(key).map(_.json).getOrElse(JsNull)
          case ("tcpServices", Some(key)) => userData.env.proxyState.tcpService(key).map(_.json).getOrElse(JsNull)
          case ("scripts", Some(key)) => userData.env.proxyState.script(key).map(_.json).getOrElse(JsNull)

          case (_, __) => JsNull
        }).stringify)
      })
    }

  def getClusterStateFunction: ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(hostData => {
        val cc = hostData.env.clusterConfig
        plugin.returnString(returns(0), getClusterState(cc).stringify)
      })
    }

  def proxyClusteStateGetValueFunction: ExtismFunction[EnvUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[EnvUserData]) => {
      data.ifPresent(userData => {
        val path = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val cc = userData.env.clusterConfig
        plugin.returnString(
          returns(0),
          JsonOperationsHelper.getValueAtPath(path, getClusterState(cc))._2.stringify
        )
      })
    }

  def proxyGlobalMapSetFunction(pluginId: Option[String] = None): ExtismFunction[StateUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[StateUserData]) => {
      data.ifPresent(hostData => {
        val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

        val key = (data \ "key").as[String]
        val value = (data \ "value").as[String]

        val id = pluginId.getOrElse("global")
        hostData.cache.get(id) match {
          case Some(state) =>
            state.put(key, ByteString(value))
            hostData.cache.put(id, state)
          case None =>
            val state = new LegitTrieMap[String, ByteString]()
            state.put(key, ByteString(value))
            hostData.cache.put(id, state)
        }

        plugin.returnString(returns(0), Status.StatusOK.toString)
      })
    }

  def proxyGlobalMapGetFunction(pluginId: Option[String] = None): ExtismFunction[StateUserData] =
    (plugin: ExtismCurrentPlugin, params: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[StateUserData]) => {
      data.ifPresent(hostData => {
        val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

        val id = pluginId.getOrElse("global")

        plugin.returnString(returns(0), hostData.cache.get(id) match {
          case Some(state) => state.get(key).map(_.utf8String).getOrElse("")
          case None => ""
        })
      })
    }

  def proxyGlobalMapFunction(pluginId: Option[String] = None): ExtismFunction[StateUserData] =
    (plugin: ExtismCurrentPlugin, _: Array[LibExtism.ExtismVal], returns: Array[LibExtism.ExtismVal], data: Optional[StateUserData]) => {
      data.ifPresent(hostData => {
        val id = pluginId.getOrElse("global")

        plugin.returnString(returns(0), hostData.cache.get(id) match {
          case Some(state) => Json.arr(state.toSeq).stringify
          case None => ""
        })
      })
    }

  def getProxyState(config: WasmConfig)
                   (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_proxy_state",
      Array(LibExtism.ExtismValType.I64),
      Array(LibExtism.ExtismValType.I64),
      getProxyStateFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }
  def proxyStateGetValue(config: WasmConfig)
                        (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_proxy_state_value",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyStateGetValueFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }
  def getClusterState(config: WasmConfig)
                     (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_cluster_state",
      Array(LibExtism.ExtismValType.I64),
      Array(LibExtism.ExtismValType.I64),
      getClusterStateFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }
  def proxyClusteStateGetValue(config: WasmConfig)
                              (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_cluster_state_value",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyClusteStateGetValueFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyGlobalMapSet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)
                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[StateUserData] = {
    new HostFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_set" else "proxy_global_map_set",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyGlobalMapSetFunction(pluginId),
      Optional.of(StateUserData(env, executionContext, mat, cache))
    )
  }

  def proxyGlobalMapGet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)
                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[StateUserData] = {
    new HostFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_get" else "proxy_global_map_get",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyGlobalMapGetFunction(pluginId),
      Optional.of(StateUserData(env, executionContext, mat, cache))
    )
  }

  def proxyGlobalMap(pluginRestricted: Boolean = false, pluginId: Option[String] = None)
                       (implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[StateUserData] = {
    new HostFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map" else "proxy_global_map",
      Array(LibExtism.ExtismValType.I64),
      Array(LibExtism.ExtismValType.I64),
      proxyGlobalMapFunction(pluginId),
      Optional.of(StateUserData(env, executionContext, mat, cache))
    )
  }

  def getFunctions(config: WasmConfig, pluginId: String)
                  (implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = Seq(
    getProxyState(config),
    proxyStateGetValue(config),
    getClusterState(config),
    proxyClusteStateGetValue(config),

    proxyGlobalMapSet(),
    proxyGlobalMapGet(),
    proxyGlobalMap(),

    proxyGlobalMapSet(pluginRestricted = true, pluginId.some),
    proxyGlobalMapGet(pluginRestricted = true, pluginId.some),
    proxyGlobalMap(pluginRestricted = true, pluginId.some)
  )
}

object HostFunctions {
    def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext], pluginId: String)
                    (implicit env: Env, executionContext: ExecutionContext): Array[HostFunction[_ <: HostUserData]] = {
      implicit val mat = env.otoroshiMaterializer
      (Logging.getFunctions(config, ctx) ++
        Http.getFunctions(config) ++
        State.getFunctions(config, pluginId) ++
        DataStore.getFunctions(config, pluginId)
        ).toArray
    }
}
