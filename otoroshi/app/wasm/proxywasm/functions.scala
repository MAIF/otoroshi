package otoroshi.wasm.proxywasm

import org.extism.sdk._
import otoroshi.wasm._

import java.util.Optional

class Functions(config: WasmConfig, data: VmData, state: ProxyWasmState) {

  val functions = Seq(
    HFunction.defineContextualFunction("proxy_log", config) { (plugin, params, returns, data) =>
      state.ProxyLog(plugin, params(0).v.i32, params(1).v.i32, params(2).v.i32)
    },
    new HostFunction[](
      "proxy_get_buffer_bytes",
      this.parameters(5),
      this.parameters(1),
      (plugin, params, returns, data) ->
        state.ProxyGetBuffer(plugin, (VmData) data.get(),
      params[0].v.i32,
      params[1].v.i32,
      params[2].v.i32,
      params[3].v.i32,
      params[4].v.i32),
    Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_set_effective_context",
    this.parameters(1),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxySetEffectiveContext(plugin, params[0].v.i32),
    Optional.empty()
  ),
  new HostFunction[](
    "proxy_get_header_map_pairs",
    this.parameters(3),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyGetHeaderMapPairs(plugin, (VmData) data.get(), params[0].v.i32, params[1].v.i32, params[2].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_set_buffer_bytes",
    this.parameters(5),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxySetBuffer(plugin, (VmData) data.get(),
    params[0].v.i32,
    params[1].v.i32,
    params[2].v.i32,
    params[3].v.i32,
    params[4].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_get_header_map_value",
    this.parameters(5),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyGetHeaderMapValue(plugin,
        (VmData) data.get(),
    params[0].v.i32,
    params[1].v.i32,
    params[2].v.i32,
    params[3].v.i32,
    params[4].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_get_property",
    this.parameters(4),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyGetProperty(plugin, (VmData) data.get(), params[0].v.i32, params[1].v.i32, params[2].v.i32, params[3].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_increment_metric",
    new LibExtism.ExtismValType[]{LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I64},
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyIncrementMetricValue(plugin, (VmData) data.get(), params[0].v.i32, params[1].v.i64),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_define_metric",
    this.parameters(4),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyDefineMetric(plugin, params[0].v.i32, params[1].v.i32, params[2].v.i32, params[3].v.i32),
    Optional.empty()
  ),
  new HostFunction[](
    "proxy_set_tick_period_milliseconds",
    this.parameters(1),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxySetTickPeriodMilliseconds((VmData) data.get(), params[0].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_replace_header_map_value",
    this.parameters(5),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxyReplaceHeaderMapValue(plugin,
        (VmData) data.get(),
    params[0].v.i32,
    params[1].v.i32,
    params[2].v.i32,
    params[3].v.i32,
    params[4].v.i32),
  Optional.of(VmData)
  ),
  new HostFunction[](
    "proxy_send_local_response",
    this.parameters(8),
    this.parameters(1),
    (plugin, params, returns, data) ->
      state.ProxySendHttpResponse(plugin,
        params[0].v.i32,
        params[1].v.i32,
        params[2].v.i32,
        params[3].v.i32,
        params[4].v.i32,
        params[5].v.i32,
        params[6].v.i32,
        params[7].v.i32),
    Optional.empty()
  )
  )

  def all(): Seq[HostFunction[_]] = functions

  def parameters(n: Int): Array[LibExtism.ExtismValType] = {
    (0 to n).map(_ => LibExtism.ExtismValType.I32).toArray
  }
}