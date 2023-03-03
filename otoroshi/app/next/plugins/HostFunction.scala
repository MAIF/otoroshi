package next.plugins

import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import akka.util.ByteString
import org.extism.sdk._
import otoroshi.env.Env
import otoroshi.next.plugins.WasmQueryConfig
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.{JsArray, JsNull, Json}

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object Utils {
    def rawBytePtrToString(plugin: ExtismCurrentPlugin, offset: Long, arrSize: Int): String = {
        val memoryLength = LibExtism.INSTANCE.extism_current_plugin_memory_length(plugin.pointer, arrSize)
        val arr = plugin.memory().share(offset, memoryLength)
                .getByteArray(0, arrSize)
        new String(arr, StandardCharsets.UTF_8)
    }
}

case class EnvUserData(env: Env, executionContext: ExecutionContext, mat: Materializer, config: WasmQueryConfig) extends HostUserData
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

    def proxyLog() = new org.extism.sdk.HostFunction[EmptyUserData](
            "proxy_log",
            Array(LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I64,LibExtism.ExtismValType.I32),
            Array(LibExtism.ExtismValType.I32),
            proxyLogFunction,
            Optional.of(EmptyUserData())
    )

  def getFunctions = Seq(proxyLog())
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

    def proxyHttpCall(config: WasmQueryConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
        new HostFunction[EnvUserData](
                "proxy_http_call",
                Array(LibExtism.ExtismValType.I64,LibExtism.ExtismValType.I32),
                Array(LibExtism.ExtismValType.I64),
                httpCallFunction,
                Optional.of(EnvUserData(env, executionContext, mat, config))
        )
    }

    def getFunctions(config: WasmQueryConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = Seq(proxyHttpCall(config))
}


object State {

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

  def getProxyState(config: WasmQueryConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_proxy_state",
      Array(LibExtism.ExtismValType.I64),
      Array(LibExtism.ExtismValType.I64),
      getProxyStateFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def proxyStateGetValue(config: WasmQueryConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    new HostFunction[EnvUserData](
      "get_proxy_state_value",
      Array(LibExtism.ExtismValType.I64, LibExtism.ExtismValType.I32),
      Array(LibExtism.ExtismValType.I64),
      proxyStateGetValueFunction,
      Optional.of(EnvUserData(env, executionContext, mat, config))
    )
  }

  def getFunctions(config: WasmQueryConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = Seq(
    getProxyState(config),
    proxyStateGetValue(config),
  )
}

object HostFunctions {
    def getFunctions(config: WasmQueryConfig)
                    (implicit env: Env, executionContext: ExecutionContext): Array[HostFunction[_ <: HostUserData]] = {
      implicit val mat = env.otoroshiMaterializer
      (Logging.getFunctions ++ Http.getFunctions(config) ++ State.getFunctions(config)).toArray
    }
}
