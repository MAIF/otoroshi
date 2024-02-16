package otoroshi.wasm

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import io.otoroshi.wasm4s.scaladsl.opa._
import org.extism.sdk.{ExtismCurrentPlugin, ExtismFunction, HostFunction, HostUserData, LibExtism}
import org.extism.sdk.wasmotoroshi._
import org.joda.time.DateTime
import otoroshi.cluster.ClusterConfig
import otoroshi.env.Env
import otoroshi.events.WasmLogEvent
import otoroshi.models._
import otoroshi.next.models.NgTarget
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{ConcurrentMutableTypedMap, RegexPool, TypedMap}
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

object Utils {
  def rawBytePtrToString(plugin: ExtismCurrentPlugin, offset: Long, arrSize: Long): String = {
    val memoryLength = plugin.memoryLength(arrSize)
    val arr          = plugin
      .memory()
      .share(offset, memoryLength)
      .getByteArray(0, arrSize.toInt)
    new String(arr, StandardCharsets.UTF_8)
  }

  def contextParamsToString(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*) = {
    rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
  }

  def contextParamsToJson(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*) = {
    Json.parse(rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))
  }
}

object LogLevel extends Enumeration {
  type LogLevel = Value

  val LogLevelTrace, LogLevelDebug, LogLevelInfo, LogLevelWarn, LogLevelError, LogLevelCritical, LogLevelMax = Value
}

object Status extends Enumeration {
  type Status = Value

  val StatusOK, StatusNotFound, StatusBadArgument, StatusEmpty, StatusCasMismatch, StatusInternalFailure,
      StatusUnimplemented = Value
}

trait AwaitCapable {
  def await[T](future: Future[T], atMost: FiniteDuration = 5.seconds)(implicit env: Env): T = {
    Await.result(future, atMost) // TODO: atMost from env
  }
}

object HFunction {

  def defineEmptyFunction(fname: String, returnType: LibExtism.ExtismValType, params: LibExtism.ExtismValType*)(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal]) => Unit
  ): HostFunction[EmptyUserData] = {
    defineFunction[EmptyUserData](fname, None, returnType, params: _*)((p1, p2, p3, _) => f(p1, p2, p3))
  }

  def defineClassicFunction(
      fname: String,
      config: WasmConfig,
      returnType: LibExtism.ExtismValType,
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], EnvUserData) => Unit
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val ev = EnvUserData(env.wasmIntegration.context, ec, mat, config)
    defineFunction[EnvUserData](fname, ev.some, returnType, params: _*)((p1, p2, p3, _) => f(p1, p2, p3, ev))
  }

  def defineContextualFunction(
      fname: String,
      config: WasmConfig
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], EnvUserData) => Unit
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val ev = EnvUserData(env.wasmIntegration.context, ec, mat, config)
    defineFunction[EnvUserData](
      fname,
      ev.some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    )((p1, p2, p3, _) => f(p1, p2, p3, ev))
  }

  def defineFunction[A <: HostUserData](
      fname: String,
      data: Option[A],
      returnType: LibExtism.ExtismValType,
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], Option[A]) => Unit
  ): HostFunction[A] = {
    new HostFunction[A](
      fname,
      Array(params: _*),
      Array(returnType),
      new ExtismFunction[A] {
        override def invoke(
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            data: Optional[A]
        ): Unit = {
          f(plugin, params, returns, if (data.isEmpty) None else Some(data.get()))
        }
      },
      data match {
        case None    => Optional.empty[A]()
        case Some(d) => Optional.of(d)
      }
    ).withNamespace("env")
  }

  def defineFunctionWithReturn[A <: HostUserData](
      fname: String,
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], Option[A]) => Unit
  ): HostFunction[A] = {
    new HostFunction[A](
      fname,
      Array(params: _*),
      Array(),
      new ExtismFunction[A] {
        override def invoke(
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            data: Optional[A]
        ): Unit = {
          f(plugin, params, returns, if (data.isEmpty) None else Some(data.get()))
        }
      },
      Optional.empty[A]()
    ).withNamespace("env")
  }
}

object Logging extends AwaitCapable {

  val logger = Logger("otoroshi-wasm-logger")

  def proxyLog() = HFunction.defineEmptyFunction(
    "proxy_log",
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns) =>
    val logLevel = LogLevel(params(0).v.i32)

    val messageData = Utils.rawBytePtrToString(plugin, params(1).v.i64, params(2).v.i64)

    logLevel match {
      case LogLevel.LogLevelTrace => logger.trace(messageData)
      case LogLevel.LogLevelDebug => logger.debug(messageData)
      case LogLevel.LogLevelInfo  => logger.info(messageData)
      case LogLevel.LogLevelWarn  => logger.warn(messageData)
      case _                      => logger.error(messageData)
    }

    returns(0).v.i32 = Status.StatusOK.id
  }

  def proxyLogWithEvent(config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_log_event",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, ud) =>
      val data  = Utils.contextParamsToJson(plugin, params: _*)
      val route = data.select("route_id").asOpt[String].flatMap(env.proxyState.route)
      val event = WasmLogEvent(
        `@id` = ud.asInstanceOf[OtoroshiWasmIntegrationContext].ev.snowflakeGenerator.nextIdStr(),
        `@service` = route.map(_.name).getOrElse(""),
        `@serviceId` = route.map(_.id).getOrElse(""),
        `@timestamp` = DateTime.now(),
        route = route,
        fromFunction = (data \ "function").asOpt[String].getOrElse("unknown function"),
        message = (data \ "message").asOpt[String].getOrElse("--"),
        level = (data \ "level").asOpt[Int].map(r => LogLevel(r)).getOrElse(LogLevel.LogLevelDebug).toString
      )
      event.toAnalytics()
      returns(0).v.i32 = Status.StatusOK.id
    }
  }

  def getFunctions(config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyLog(), _ => true),
      HostFunctionWithAuthorization(proxyLogWithEvent(config), _ => true)
    )
  }
}

object Http extends AwaitCapable {

  def proxyHttpCall(config: WasmConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = {
    HFunction.defineContextualFunction("proxy_http_call", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val context = Json.parse(Utils.contextParamsToString(plugin, params: _*))

          val url          = (context \ "url").asOpt[String].getOrElse("https://mirror.otoroshi.io")
          val allowedHosts = hostData.config.allowedHosts
          val urlHost      = Uri(url).authority.host.toString()
          val allowed      = allowedHosts.isEmpty || allowedHosts.contains("*") || allowedHosts.exists(h =>
            RegexPool(h).matches(urlHost)
          )
          if (allowed) {
            val builder                  = hostData.ic
              .asInstanceOf[OtoroshiWasmIntegrationContext]
              .ev
              .Ws
              .url(url)
              .withMethod((context \ "method").asOpt[String].getOrElse("GET"))
              .withHttpHeaders((context \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
              .withRequestTimeout(
                Duration(
                  (context \ "request_timeout")
                    .asOpt[Long]
                    .getOrElse(hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.clusterConfig.worker.timeout),
                  TimeUnit.MILLISECONDS
                )
              )
              .withFollowRedirects((context \ "follow_redirects").asOpt[Boolean].getOrElse(false))
              .withQueryStringParameters((context \ "query").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
            val bodyAsBytes              = context.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
            val bodyBase64               = context.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
            val bodyJson                 = context.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
            val bodyStr                  = context
              .select("body_str")
              .asOpt[String]
              .orElse(context.select("body").asOpt[String])
              .map(str => ByteString(str))
            val body: Option[ByteString] = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes)
            val request                  = body match {
              case Some(bytes) => builder.withBody(bytes)
              case None        => builder
            }
            val out                      = Await.result(
              request
                .execute()
                .map { res =>
                  val body                         = res.bodyAsBytes.encodeBase64.utf8String
                  val headers: Map[String, String] = res.headers.mapValues(_.head)
                  Json.obj(
                    "status"      -> res.status,
                    "headers"     -> headers,
                    "body_base64" -> body
                  )
                },
              Duration(
                hostData.config.asInstanceOf[WasmConfig].authorizations.proxyHttpCallTimeout,
                TimeUnit.MILLISECONDS
              )
            )
            plugin.returnString(returns(0), Json.stringify(out))
          } else {
            plugin.returnString(
              returns(0),
              Json.stringify(
                Json.obj(
                  "status"      -> 403,
                  "headers"     -> Json.obj("content-type" -> "text/plain"),
                  "body_base64" -> ByteString(s"you cannot access host: ${urlHost}").encodeBase64.utf8String
                )
              )
            )
          }
        }
    }
  }

  def getAttributes(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_get_attrs",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      attrs match {
        case None     => plugin.returnBytes(returns(0), Array.empty[Byte])
        case Some(at) => plugin.returnBytes(returns(0), at.json.stringify.byteString.toArray)
      }
    }
  }

  def getAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_get_attr", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case None     => plugin.returnBytes(returns(0), Array.empty[Byte])
            case Some(at) =>
              val key = Utils.contextParamsToString(plugin, params: _*)
              at.json.select(key).asOpt[JsValue] match {
                case None        => plugin.returnBytes(returns(0), Array.empty[Byte])
                case Some(value) => plugin.returnBytes(returns(0), value.stringify.byteString.toArray)
              }
          }
        }
    }
  }

  def clearAttributes(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_clear_attrs",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      attrs match {
        case None     => plugin.returnInt(returns(0), 0)
        case Some(at) => {
          at.clear()
          plugin.returnInt(returns(0), 0)
        }
      }
    }
  }

  lazy val possibleAttributes: Map[String, otoroshi.plugins.AttributeSetter[_]] = Seq(
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.ResponseAddHeadersKey,
      json => json.asObject.value.mapValues(_.asString).toSeq
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.JwtInjectionKey,
      json => JwtInjection.fromJson(json).right.get
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey,
      json => ApikeyTuple.fromJson(json).right.get
    ),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.UserKey, json => PrivateAppsUser.fmt.reads(json).get),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ApiKeyKey, json => ApiKey._fmt.reads(json).get),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ExtraAnalyticsDataKey, json => json),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.GatewayEventExtraInfosKey, json => json),
    otoroshi.plugins.AttributeSetter(
      otoroshi.plugins.Keys.PreExtractedRequestTargetKey,
      json => Target.format.reads(json).get
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.plugins.Keys.PreExtractedRequestTargetsKey,
      json => json.asArray.value.map(v => NgTarget.fmt.reads(v).get)
    ),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ElCtxKey, json => json.asObject.value.mapValues(_.asString))
  )
    .map(s => (s.key.displayName, s))
    .collect { case (Some(k), s) =>
      (k, s)
    }
    .toMap

  def setAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_set_attr", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case Some(at: ConcurrentMutableTypedMap) => {
              val context = Json.parse(Utils.contextParamsToString(plugin, params: _*))
              val key     = context.select("key").asString
              val value   = context.select("value").asValue
              try {
                possibleAttributes.get(key).foreach { setter =>
                  at.m.put(setter.key, setter.f(value))
                }
              } catch {
                case t: Throwable => t.printStackTrace()
              }
              plugin.returnInt(returns(0), 1)
            }
            case _                                   => plugin.returnInt(returns(0), 0)
          }
        }
    }
  }

  def delAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_del_attr", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case Some(at: ConcurrentMutableTypedMap) => {
              val key = Utils.contextParamsToString(plugin, params: _*)
              at.m.keySet.find(_.displayName.contains(key)).foreach(tk => at.remove(tk))
              plugin.returnInt(returns(0), 1)
            }
            case _                                   => plugin.returnInt(returns(0), 0)
          }
        }
    }
  }

  def getFunctions(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyHttpCall(config), _.asInstanceOf[WasmConfig].authorizations.httpAccess),
      HostFunctionWithAuthorization(getAttributes(config, attrs), _ => true),
      HostFunctionWithAuthorization(getAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(setAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(delAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(clearAttributes(config, attrs), _ => true)
    )
  }
}

object DataStore extends AwaitCapable {

  def proxyDataStoreAllMatching(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_all_matching", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore
            .allMatching(s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key")
            .map { values =>
              values.map(v => JsString(v.encodeBase64.utf8String))
            }
          val res    = await(future)
          plugin.returnBytes(returns(0), ByteString(JsArray(res).stringify).toArray)
        }
    }
  }

  def proxyDataStoreKeys(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_keys", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore
            .keys(s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key")
            .map { values =>
              JsArray(values.map(JsString.apply)).stringify
            }
          val out    = await(future)
          plugin.returnString(returns(0), out)
        }
    }
  }

  def proxyDataStoreGet(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_get", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.get(
            s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key"
          )
          val out    = await(future)
          val bytes  = out.map(_.toArray).getOrElse(Array.empty[Byte])
          plugin.returnBytes(returns(0), bytes)
        }
    }
  }

  def proxyDataStoreExists(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_exists", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.exists(
            s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key"
          )
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStorePttl(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_pttl", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.pttl(
            s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key"
          )
          returns(0).v.i64 = await(future)
        }
    }
  }

  def proxyDataStoreSetnx(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_setnx", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val value  = (data \ "value")
            .asOpt[String]
            .map(ByteString.apply)
            .orElse(data.select("value_base64").asOpt[String].map(s => ByteString(s).decodeBase64))
            .get
          val ttl    = (data \ "ttl").asOpt[Long]
          val future = env.datastores.rawDataStore.setnx(
            s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key",
            value,
            ttl
          )
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStoreSet(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_set", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val value  = (data \ "value")
            .asOpt[String]
            .map(ByteString.apply)
            .orElse(data.select("value_base64").asOpt[String].map(s => ByteString(s).decodeBase64))
            .get
          val ttl    = (data \ "ttl").asOpt[Long]
          val future = env.datastores.rawDataStore.set(
            s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key",
            value,
            ttl
          )
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStoreDel(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_del", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore
            .del(
              (data \ "keys")
                .asOpt[Seq[String]]
                .getOrElse(Seq.empty)
                .map(r => s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$r")
            )
          val out    = await(future)
          returns(0).v.i64 = out
        }
    }
  }

  def proxyDataStoreIncrby(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_incrby", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val incr   = (data \ "incr").asOpt[String].map(_.toInt).getOrElse((data \ "incr").asOpt[Int].getOrElse(0))
          val future = env.datastores.rawDataStore
            .incrby(s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key", incr)
          val out    = await(future)
          returns(0).v.i64 = out
        }
    }
  }

  def proxyDataStorePexpire(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_pexpire", config) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val pttl   = (data \ "pttl").asOpt[String].map(_.toInt).getOrElse((data \ "pttl").asOpt[Int].getOrElse(0))
          val future = env.datastores.rawDataStore
            .pexpire(s"${hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.storageRoot}:$path$key", pttl)
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def getFunctions(config: WasmConfig, pluginId: String)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] =
    Seq(
      HostFunctionWithAuthorization(
        proxyDataStoreKeys(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreGet(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreExists(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePttl(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSet(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSetnx(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreDel(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreIncrby(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePexpire(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreAllMatching(config = config),
        _.asInstanceOf[WasmConfig].authorizations.globalDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreKeys(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreGet(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreExists(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePttl(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreAllMatching(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSet(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSetnx(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreDel(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreIncrby(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePexpire(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginDataStoreAccess.write
      )
    )
}

object Wazero extends AwaitCapable {

  def runtimeTicks() = HFunction.defineEmptyFunction(
    "runtime.ticks",
    LibExtism.ExtismValType.F64
  ) { (plugin, params, returns) =>
    returns(0).v.f64 = 0.0
  }

  def valueGet() = HFunction.defineEmptyFunction(
    "syscall/js.valueGet",
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32
  ) { (plugin, params, returns) => }

  def valuePrepareString() = HFunction.defineFunctionWithReturn[EmptyUserData](
    "syscall/js.valuePrepareString",
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns, data) => }

  def valueLoadString() = HFunction.defineFunctionWithReturn[EmptyUserData](
    "syscall/js.valueLoadString",
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32
  ) { (plugin, params, returns, data) => }

  def finalizeRef() = HFunction.defineFunctionWithReturn[EmptyUserData](
    "syscall/js.finalizeRef",
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns, data) => }

  def stringVal() = HFunction.defineEmptyFunction(
    "syscall/js.stringVal",
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32
  ) { (plugin, params, returns) => }

  def valueSet() = HFunction.defineFunctionWithReturn[EmptyUserData](
    "syscall/js.valueSet",
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns, data) => }

  def valueLength() = HFunction.defineEmptyFunction(
    "syscall/js.valueLength",
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns) => }

  def valueIndex() = HFunction.defineEmptyFunction(
    "syscall/js.valueIndex",
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32
  ) { (plugin, params, returns) => }

  def valueCall() = HFunction.defineFunctionWithReturn[EmptyUserData](
    "syscall/js.valueCall",
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
  ) { (plugin, params, returns, data) => }

  def getFunctions(): Seq[HostFunctionWithAuthorization] = Seq(
    HostFunctionWithAuthorization(runtimeTicks().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueGet().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valuePrepareString().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueLoadString().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(finalizeRef().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(stringVal().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueSet().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueLength().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueIndex().withNamespace("gojs"), _ => true),
    HostFunctionWithAuthorization(valueCall().withNamespace("gojs"), _ => true)
  )
}

object State {

  private val cache: UnboundedTrieMap[String, UnboundedTrieMap[String, ByteString]] =
    new UnboundedTrieMap[String, UnboundedTrieMap[String, ByteString]]()

  def getClusterState(cc: ClusterConfig): JsValue = cc.json

  def getProxyState(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_state",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val proxyState = hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.proxyState

        val state = Json
          .obj(
            "raw_routes"          -> JsArray(proxyState.allRawRoutes().map(_.json)),
            "routes"              -> JsArray(proxyState.allRoutes().map(_.json)),
            "routeCompositions"   -> JsArray(proxyState.allRouteCompositions().map(_.json)),
            "apikeys"             -> JsArray(proxyState.allApikeys().map(_.json)),
            "ngbackends"          -> JsArray(proxyState.allBackends().map(_.json)),
            "jwtVerifiers"        -> JsArray(proxyState.allJwtVerifiers().map(_.json)),
            "certificates"        -> JsArray(proxyState.allCertificates().map(_.json)),
            "authModules"         -> JsArray(proxyState.allAuthModules().map(_.json)),
            "services"            -> JsArray(proxyState.allServices().map(_.json)),
            "teams"               -> JsArray(proxyState.allTeams().map(_.json)),
            "tenants"             -> JsArray(proxyState.allTenants().map(_.json)),
            "serviceGroups"       -> JsArray(proxyState.allServiceGroups().map(_.json)),
            "dataExporters"       -> JsArray(proxyState.allDataExporters().map(_.json)),
            "otoroshiAdmins"      -> JsArray(proxyState.allOtoroshiAdmins().map(_.json)),
            "backofficeSessions"  -> JsArray(proxyState.allBackofficeSessions().map(_.json)),
            "privateAppsSessions" -> JsArray(proxyState.allPrivateAppsSessions().map(_.json)),
            "tcpServices"         -> JsArray(proxyState.allTcpServices().map(_.json)),
            "scripts"             -> JsArray(proxyState.allScripts().map(_.json)),
            "wasmPlugins"         -> JsArray(proxyState.allWasmPlugins().map(_.json))
          )
          .stringify

        plugin.returnString(returns(0), state)
      }
    }
  }
  def proxyStateGetValue(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_state_value", config) { (plugin, params, returns, userData) =>
      {
        val context = Utils.contextParamsToJson(plugin, params: _*)

        val entity             = (context \ "entity").asOpt[String].getOrElse("")
        val id: Option[String] = (context \ "id").asOpt[String]
        val env                = userData.ic.asInstanceOf[OtoroshiWasmIntegrationContext].ev

        plugin.returnString(
          returns(0),
          ((entity, id) match {
            case ("raw_routes", None)          => JsArray(env.proxyState.allRawRoutes().map(_.json))
            case ("routes", None)              => JsArray(env.proxyState.allRoutes().map(_.json))
            case ("routeCompositions", None)   => JsArray(env.proxyState.allRouteCompositions().map(_.json))
            case ("apikeys", None)             => JsArray(env.proxyState.allApikeys().map(_.json))
            case ("ngbackends", None)          => JsArray(env.proxyState.allBackends().map(_.json))
            case ("jwtVerifiers", None)        => JsArray(env.proxyState.allJwtVerifiers().map(_.json))
            case ("certificates", None)        => JsArray(env.proxyState.allCertificates().map(_.json))
            case ("authModules", None)         => JsArray(env.proxyState.allAuthModules().map(_.json))
            case ("services", None)            => JsArray(env.proxyState.allServices().map(_.json))
            case ("teams", None)               => JsArray(env.proxyState.allTeams().map(_.json))
            case ("tenants", None)             => JsArray(env.proxyState.allTenants().map(_.json))
            case ("serviceGroups", None)       => JsArray(env.proxyState.allServiceGroups().map(_.json))
            case ("dataExporters", None)       => JsArray(env.proxyState.allDataExporters().map(_.json))
            case ("otoroshiAdmins", None)      => JsArray(env.proxyState.allOtoroshiAdmins().map(_.json))
            case ("backofficeSessions", None)  => JsArray(env.proxyState.allBackofficeSessions().map(_.json))
            case ("privateAppsSessions", None) => JsArray(env.proxyState.allPrivateAppsSessions().map(_.json))
            case ("tcpServices", None)         => JsArray(env.proxyState.allTcpServices().map(_.json))
            case ("scripts", None)             => JsArray(env.proxyState.allScripts().map(_.json))

            case ("raw_routes", Some(key))          => env.proxyState.rawRoute(key).map(_.json).getOrElse(JsNull)
            case ("routes", Some(key))              => env.proxyState.route(key).map(_.json).getOrElse(JsNull)
            case ("routeCompositions", Some(key))   =>
              env.proxyState.routeComposition(key).map(_.json).getOrElse(JsNull)
            case ("apikeys", Some(key))             => env.proxyState.apikey(key).map(_.json).getOrElse(JsNull)
            case ("ngbackends", Some(key))          => env.proxyState.backend(key).map(_.json).getOrElse(JsNull)
            case ("jwtVerifiers", Some(key))        => env.proxyState.jwtVerifier(key).map(_.json).getOrElse(JsNull)
            case ("certificates", Some(key))        => env.proxyState.certificate(key).map(_.json).getOrElse(JsNull)
            case ("authModules", Some(key))         => env.proxyState.authModule(key).map(_.json).getOrElse(JsNull)
            case ("services", Some(key))            => env.proxyState.service(key).map(_.json).getOrElse(JsNull)
            case ("teams", Some(key))               => env.proxyState.team(key).map(_.json).getOrElse(JsNull)
            case ("tenants", Some(key))             => env.proxyState.tenant(key).map(_.json).getOrElse(JsNull)
            case ("serviceGroups", Some(key))       => env.proxyState.serviceGroup(key).map(_.json).getOrElse(JsNull)
            case ("dataExporters", Some(key))       => env.proxyState.dataExporter(key).map(_.json).getOrElse(JsNull)
            case ("otoroshiAdmins", Some(key))      =>
              env.proxyState.otoroshiAdmin(key).map(_.json).getOrElse(JsNull)
            case ("backofficeSessions", Some(key))  =>
              env.proxyState.backofficeSession(key).map(_.json).getOrElse(JsNull)
            case ("privateAppsSessions", Some(key)) =>
              env.proxyState.privateAppsSession(key).map(_.json).getOrElse(JsNull)
            case ("tcpServices", Some(key))         => env.proxyState.tcpService(key).map(_.json).getOrElse(JsNull)
            case ("scripts", Some(key))             => env.proxyState.script(key).map(_.json).getOrElse(JsNull)

            case (_, __) => JsNull
          }).stringify
        )
      }
    }
  }

  def getProxyConfig(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_config",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.configurationJson.stringify
        plugin.returnString(returns(0), cc)
      }
    }
  }

  def getGlobalProxyConfig(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = {
    HFunction.defineClassicFunction(
      "proxy_global_config",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData
          .asInstanceOf[OtoroshiWasmIntegrationContext]
          .ev
          .datastores
          .globalConfigDataStore
          .latest()
          .json
          .stringify
        plugin.returnString(returns(0), cc)
      }
    }
  }

  def getClusterState(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_cluster_state",
      config,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.clusterConfig
        plugin.returnString(returns(0), getClusterState(cc).stringify)
      }
    }
  }

  def proxyClusteStateGetValue(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_cluster_state_value", config) { (plugin, params, returns, userData) =>
      {
        val path = Utils.contextParamsToString(plugin, params: _*)

        val cc = userData.asInstanceOf[OtoroshiWasmIntegrationContext].ev.clusterConfig
        plugin.returnString(returns(0), JsonOperationsHelper.getValueAtPath(path, getClusterState(cc))._2.stringify)
      }
    }
  }

  def proxyGlobalMapSet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_set" else "proxy_global_map_set",
      StateUserData(env.wasmIntegration.context, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

          val key   = (data \ "key").as[String]
          val value = (data \ "value").as[String]

          val id = pluginId.getOrElse("global")
          hostData.cache.get(id) match {
            case Some(state) =>
              state.put(key, ByteString(value))
              hostData.cache.put(id, state)
            case None        =>
              val state = new UnboundedTrieMap[String, ByteString]()
              state.put(key, ByteString(value))
              hostData.cache.put(id, state)
          }

          plugin.returnInt(returns(0), Status.StatusOK.id)
        })
      }
    }
  }

  def proxyGlobalMapDel(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_del" else "proxy_global_map_del",
      StateUserData(env.wasmIntegration.context, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
          val id  = pluginId.getOrElse("global")
          hostData.cache.get(id) match {
            case Some(state) =>
              state.remove(key)
              hostData.cache.put(id, state)
            case None        =>
              val state = new UnboundedTrieMap[String, ByteString]()
              state.remove(key)
              hostData.cache.put(id, state)
          }
          plugin.returnString(returns(0), Status.StatusOK.toString)
        })
      }
    }
  }

  def proxyGlobalMapGet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_get" else "proxy_global_map_get",
      StateUserData(env.wasmIntegration.context, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

          val id = pluginId.getOrElse("global")

          plugin.returnBytes(
            returns(0),
            hostData.cache.get(id) match {
              case Some(state) => state.get(key).map(_.toArray).getOrElse(Array.empty[Byte])
              case None        => Array.empty[Byte]
            }
          )
        })
      }
    }
  }

  def proxyGlobalMap(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map" else "proxy_global_map",
      StateUserData(env.wasmIntegration.context, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val id = pluginId.getOrElse("global")
          plugin.returnString(
            returns(0),
            hostData.cache.get(id) match {
              case Some(state) =>
                state
                  .foldLeft(Json.obj()) { case (values, element) =>
                    values ++ Json.obj(element._1 -> element._2.encodeBase64.utf8String)
                  }
                  .stringify
              case None        => ""
            }
          )
        })
      }
    }
  }

  def getFunctions(config: WasmConfig, pluginId: String)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] =
    Seq(
      HostFunctionWithAuthorization(getProxyState(config), _.asInstanceOf[WasmConfig].authorizations.proxyStateAccess),
      HostFunctionWithAuthorization(
        proxyStateGetValue(config),
        _.asInstanceOf[WasmConfig].authorizations.proxyStateAccess
      ),
      HostFunctionWithAuthorization(
        getGlobalProxyConfig(config),
        _.asInstanceOf[WasmConfig].authorizations.proxyStateAccess
      ),
      HostFunctionWithAuthorization(
        getClusterState(config),
        _.asInstanceOf[WasmConfig].authorizations.configurationAccess
      ),
      HostFunctionWithAuthorization(
        proxyClusteStateGetValue(config),
        _.asInstanceOf[WasmConfig].authorizations.configurationAccess
      ),
      HostFunctionWithAuthorization(
        getProxyConfig(config),
        _.asInstanceOf[WasmConfig].authorizations.configurationAccess
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMapDel(),
        _.asInstanceOf[WasmConfig].authorizations.globalMapAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMapSet(),
        _.asInstanceOf[WasmConfig].authorizations.globalMapAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMapGet(),
        _.asInstanceOf[WasmConfig].authorizations.globalMapAccess.read
      ),
      HostFunctionWithAuthorization(proxyGlobalMap(), _.asInstanceOf[WasmConfig].authorizations.globalMapAccess.read),
      HostFunctionWithAuthorization(
        proxyGlobalMapDel(pluginRestricted = true, pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginMapAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMapSet(pluginRestricted = true, pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginMapAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMapGet(pluginRestricted = true, pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginMapAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyGlobalMap(pluginRestricted = true, pluginId.some),
        _.asInstanceOf[WasmConfig].authorizations.pluginMapAccess.read
      )
    )
}

object HostFunctions {

  def getFunctions(config: WasmConfig, pluginId: String, attrs: Option[TypedMap])(implicit
      env: Env,
      executionContext: ExecutionContext
  ): Array[HostFunction[_ <: HostUserData]] = {

    implicit val mat = env.otoroshiMaterializer

    val functions =
      Logging.getFunctions(config) ++
      Http.getFunctions(config, attrs) ++
      State.getFunctions(config, pluginId) ++
      DataStore.getFunctions(config, pluginId) ++
      Wazero.getFunctions()

    functions.collect {
      case func if func.authorized(config) => func.function
    }.toArray
  }
}
