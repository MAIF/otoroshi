package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import io.otoroshi.wasm4s.scaladsl._
import org.extism.sdk.{ExtismCurrentPlugin, HostFunction, HostUserData, LibExtism}
import otoroshi.env.Env
import otoroshi.wasm.httpwasm.api.{HttpHandler, LogLevel, RequestState}

import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

case class HttpWasmVmData(state: RequestState)
  extends HostUserData
  with WasmVmData {
  override def properties: Map[String, Array[Byte]] = Map.empty
}

object HttpWasmFunctions {
  def build(
      state: HttpHandler,
      vmDataRef: AtomicReference[WasmVmData]
  )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Seq[HostFunction[EnvUserData]] = {
    def getCurrentVmData(): HttpWasmVmData = {
      Option(vmDataRef.get()) match {
        case Some(data: HttpWasmVmData) => data
        case _                  =>
          println("missing vm data")
          new RuntimeException("missing vm data").printStackTrace()
          throw new RuntimeException("missing vm data")
      }
    }
    Seq(
      new HostFunction[EnvUserData](
        "enable_features",
        parameters(2),
        parameters(1),
        (
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.enableFeatures(getCurrentVmData(), params(0).v.i32),
        Optional.empty[EnvUserData]()
      ).withNamespace("http_handler"),
      new HostFunction[EnvUserData](
        "log_enabled",
        parameters(1),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.logEnabled(LogLevel.fromValue(params(0).v.i32)),
        Optional.empty[EnvUserData]()
      ).withNamespace("http_handler"),
      new HostFunction[EnvUserData](
        "log",
        parameters(3),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.log(plugin, LogLevel.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ).withNamespace("http_handler")
    )
  }

  private def parameters(n: Int): Array[LibExtism.ExtismValType] = {
    (0 until n).map(_ => LibExtism.ExtismValType.I32).toArray
  }
}
