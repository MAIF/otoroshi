package next.plugins

import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import akka.util.ByteString
import org.extism.sdk._
import otoroshi.env.Env
import otoroshi.next.plugins.WasmQueryConfig
import play.api.libs.json.{JsDefined, JsUndefined, Json}

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, DurationInt}

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

object HostFunctions {
    def getFunctions(config: WasmQueryConfig)
                    (implicit env: Env, executionContext: ExecutionContext): Array[HostFunction[_ <: HostUserData]] = {
      implicit val mat = env.otoroshiMaterializer
      (Logging.getFunctions ++ Http.getFunctions(config)).toArray
    }
}
