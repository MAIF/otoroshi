package io.otoroshi.common.wasm.scaladsl.opa

import io.otoroshi.common.wasm.scaladsl.WasmConfiguration
import org.extism.sdk.wasmotoroshi.WasmOtoroshiLinearMemory

import java.util.concurrent.atomic.AtomicReference

object LinearMemories {

  private val memories: AtomicReference[Seq[WasmOtoroshiLinearMemory]] =
    new AtomicReference[Seq[WasmOtoroshiLinearMemory]](Seq.empty[WasmOtoroshiLinearMemory])

  def getMemories(config: WasmConfiguration): Array[WasmOtoroshiLinearMemory] = {
    if (config.opa) {
      if (memories.get.isEmpty) {
        memories.set(
          io.otoroshi.common.wasm.impl.OPA.getLinearMemories()
        )
      }
      memories.get().toArray
    } else {
      Array.empty
    }
  }
}
