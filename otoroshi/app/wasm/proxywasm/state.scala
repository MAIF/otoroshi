package otoroshi.wasm

import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk.ExtismCurrentPlugin
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.wasm.proxywasm.WasmUtils.{DEBUG, traceVmHost}
import otoroshi.wasm.proxywasm._
import otoroshi.wasm.proxywasm.BufferType._
import otoroshi.wasm.proxywasm.MapType._
import otoroshi.wasm.proxywasm.Result._
import otoroshi.wasm.proxywasm.Status._

import java.nio.charset.StandardCharsets

class ProxyWasmState(val rootContextId: Int, val contextId: Int) extends Api {

  val u32Len = 4

  override def proxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int): Result = {
    traceVmHost("proxy_log")

    getMemory(plugin, messageData, messageSize)
      .fold(
        Error.toResult,
        r => {
          System.out.println(r._2.utf8String)
          ResultOk
        }
      )
  }

  override def proxyResumeStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result = {
    traceVmHost("proxy_resume_stream")
    null
  }

  override def proxyCloseStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result = {
    traceVmHost("proxy_close_stream")
    null
  }

  override def proxySendHttpResponse(plugin: ExtismCurrentPlugin, responseCode: Int, responseCodeDetailsData: Int, responseCodeDetailsSize: Int, responseBodyData: Int, responseBodySize: Int, additionalHeadersMapData: Int, additionalHeadersSize: Int, grpcStatus: Int): Result = {
    traceVmHost("proxy_send_http_response")
    null
  }

  override def proxyResumeHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result = {
    traceVmHost("proxy_resume_http_stream")
    null
  }

  override def proxyCloseHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result = {
    traceVmHost("proxy_close_http_stream")
    null
  }

  override def getBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: BufferType): IoBuffer = {
    bufferType match {
      case BufferTypeHttpRequestBody =>
        getHttpRequestBody(plugin, data)
      case BufferTypeHttpResponseBody =>
        getHttpResponseBody(plugin, data)
      case BufferTypeDownstreamData =>
        getDownStreamData(plugin, data)
      case BufferTypeUpstreamData =>
        getUpstreamData(plugin, data)
      //            case BufferTypeHttpCalloutResponseBody:
      //                GetHttpCalloutResponseBody(plugin, data)
      case BufferTypePluginConfiguration =>
        getPluginConfig(plugin, data)
      case BufferTypeVmConfiguration =>
        getVmConfig(plugin, data)
      case BufferTypeHttpCallResponseBody =>
        getHttpCalloutResponseBody(plugin, data)
      case _ =>
        getCustomBuffer(bufferType)
    }
  }

  override def proxyGetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, mSize: Int, returnBufferData: Int, returnBufferSize: Int): Result = {
    traceVmHost("proxy_get_buffer")

    getMemory(plugin)
      .fold(
        _ => ResultBadArgument,
        memory => {
          if (bufferType > BufferType.last) {
            return ResultBadArgument
          }
          val bufferTypePluginConfiguration = getBuffer(plugin, data, BufferType.valueToType(bufferType))

          var maxSize = mSize
          if (offset > offset + maxSize) {
            return ResultBadArgument
          }
          if (offset + maxSize > bufferTypePluginConfiguration.length) {
            maxSize = bufferTypePluginConfiguration.length - offset
          }

          bufferTypePluginConfiguration.drain(offset, offset + maxSize)

          System.out.println(String.format("%s, %d,%d, %d, %d,", BufferType.valueToType(bufferType), offset, maxSize, returnBufferData, returnBufferSize))
          return copyIntoInstance(plugin, memory, bufferTypePluginConfiguration, returnBufferData, returnBufferSize)
        }
      )
  }

  override def proxySetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, size: Int, bufferData: Int, bufferSize: Int): Result = {
    traceVmHost("proxy_set_buffer")
    val buf = getBuffer(plugin, data, BufferType.valueToType(bufferType))
    if (buf == null) {
      return ResultBadArgument
    }

    val memory: Pointer = plugin.getLinearMemory("memory")

    val content = new Array[Byte](bufferSize)
    memory.read(bufferData, content, 0, bufferSize)

    if (offset == 0) {
      if (size == 0 || size >= buf.length) {
        buf.drain(buf.length, -1)
        buf.write(ByteString(content))
      } else {
        return ResultBadArgument
      }
    } else if (offset >= buf.length) {
      buf.write(ByteString(content))
    } else {
      return ResultBadArgument
    }

    ResultOk
  }

  override def getMap(plugin: ExtismCurrentPlugin, vmData: VmData, mapType: MapType): Map[String, ByteString] = {
    System.out.println("CALL MAP: " + mapType)
    mapType match {
      case MapTypeHttpRequestHeaders => getHttpRequestHeader(plugin, vmData),
      case MapTypeHttpRequestTrailers => getHttpRequestTrailer(plugin, vmData),
      case MapTypeHttpRequestMetadata => getHttpRequestMetadata(plugin, vmData)
      case MapTypeHttpResponseHeaders => getHttpResponseHeader(plugin, vmData)
      case MapTypeHttpResponseTrailers => getHttpResponseTrailer(plugin, vmData)
      case MapTypeHttpResponseMetadata => getHttpResponseMetadata(plugin, vmData)
      case MapTypeHttpCallResponseHeaders => getHttpCallResponseHeaders(plugin, vmData)
      case MapTypeHttpCallResponseTrailers => getHttpCallResponseTrailer(plugin, vmData)
      case MapTypeHttpCallResponseMetadata=> getHttpCallResponseMetadata(plugin, vmData)
      case _ => getCustomMap(plugin, vmData, mapType)
    }
  }

  def copyMapIntoInstance(m: Map[String, String], plugin: ExtismCurrentPlugin, returnMapData: Int, returnMapSize: Int): Unit = ???

  override def proxyGetHeaderMapPairs(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, returnDataPtr: Int, returnDataSize: Int): Int = {
        traceVmHost("proxy_get_map")
        val header = getMap(plugin, data, MapType.valueToType(mapType))

        if (header == null) {
            return ResultNotFound.value
        }

        var totalBytesLen = u32Len

        header.foreach(entry => {
          val key = entry._1
          val value = entry._2

          totalBytesLen += u32Len + u32Len // keyLen + valueLen
          totalBytesLen += key.length() + 1 + value.length + 1 // key + \0 + value + \0
        })

        // TODO - try to call proxy_on_memory_allocate
        val addr = plugin.alloc(totalBytesLen)

        // TODO - manage error
//        if err != nil {
//            return int32(v2.ResultInvalidMemoryAccess)
//        }

        val memory: Pointer = plugin.getLinearMemory("memory")
        memory.setInt(addr, header.size)
//        if err != nil {
//            return int32(v2.ResultInvalidMemoryAccess)
//        }

        var lenPtr = addr + u32Len
        var dataPtr = lenPtr + (u32Len+u32Len) * header.size

        header.foreach(entry => {
          val k = entry._1
          val v = entry._2

            memory.setInt(lenPtr, k.length())
            lenPtr += u32Len
            memory.setInt(lenPtr, v.length)
            lenPtr += u32Len

            memory.write(dataPtr, k.getBytes(StandardCharsets.UTF_8), 0, k.length())
            dataPtr += k.length()
            memory.setByte(dataPtr, 0)
            dataPtr += 1

            memory.write(dataPtr, v, 0, v.length)
            dataPtr += v.length
            memory.setByte(dataPtr, 0)
            dataPtr += 1
        }

        memory.setInt(returnDataPtr, addr)
//        if err != nil {
//            return int32(v2.ResultInvalidMemoryAccess)
//        }

        memory.setInt(returnDataSize, totalBytesLen)
//        if err != nil {
//            return int32(v2.ResultInvalidMemoryAccess)
//        }

        ResultOk.value
  }

  override def proxyGetHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result = {
    traceVmHost("proxy_get_header_map_value")
    val m = getMap(plugin, data, MapType.valueToType(mapType))

    if (m == null || keySize == 0) {
        return ResultNotFound
    }

    getMemory(plugin, keyData, keySize)
      .fold(
        Error.toResult,
        mem => {
          val key = mem._2

          if (key.isEmpty) {
            ResultBadArgument
          } else {
            val value = m.get(key.utf8String)
            value.map(v =>
              copyIntoInstance(
                plugin,
                mem._1,
                new IoBuffer(v),
                valueData,
                valueSize)
            ).getOrElse(ResultNotFound)
          }
        }
      )
  }

  override def proxyReplaceHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result = {
    traceVmHost("proxy_set_map_value")
    val m = getMap(plugin, data, MapType.valueToType(mapType))

    if (m == null || keySize == 0) {
        return ResultNotFound
    }

    val memKey = getMemory(plugin, keyData, keySize)
    val memValue = getMemory(plugin, valueData, valueSize)

    memKey
      .fold(
        Error.toResult,
        key => {
          memValue.fold(
            Error.toResult,
            value => {
              if (key._2.isEmpty) {
                return ResultBadArgument
              }

              // TODO - not working
              m ++ (key._2.utf8String -> value._2)

              ResultOk
            }
          )
        }
      )
  }

  override def proxyOpenSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreNameData: Int, kvstoreNameSiz: Int, createIfNotExist: Int, kvstoreID: Int): Result = ???

  override def proxyGetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, returnValuesData: Int, returnValuesSize: Int, returnCas: Int): Result = ???

  override def proxySetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, valuesData: Int, valuesSize: Int, cas: Int): Result = ???

  override def proxyAddSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, valuesData: Int, valuesSize: Int, cas: Int): Result = ???

  override def proxyRemoveSharedKvstoreKey(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, cas: Int): Result = ???

  override def proxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Result = ???

  override def proxyOpenSharedQueue(plugin: ExtismCurrentPlugin, queueNameData: Int, queueNameSize: Int, createIfNotExist: Int, returnQueueID: Int): Result = ???

  override def proxyDequeueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, returnPayloadData: Int, returnPayloadSize: Int): Result = ???

  override def proxyEnqueueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, payloadData: Int, payloadSize: Int): Result = ???

  override def proxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Result = ???

  override def proxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Result = ???

  override def proxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Result = ???

  override def proxyCreateMetric(plugin: ExtismCurrentPlugin, metricType: MetricType, metricNameData: Int, metricNameSize: Int, returnMetricID: Int): MetricType = ???

  override def proxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Result = ???

  override def proxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Result = ???

  override def proxyIncrementMetricValue(plugin: ExtismCurrentPlugin, data: VmData, metricID: Int, offset: Long): Result = ???

  override def proxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Result = ???

  override def proxyDefineMetric(plugin: ExtismCurrentPlugin, metricType: Int, namePtr: Int, nameSize: Int, returnMetricId: Int): Result = ???

  override def proxyDispatchHttpCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, headersMapData: Int, headersMapSize: Int, bodyData: Int, bodySize: Int, trailersMapData: Int, trailersMapSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Result = ???

  override def proxyDispatchGrpcCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int, serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int, grpcMessageData: Int, grpcMessageSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Result = ???

  override def proxyOpenGrpcStream(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int, serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int, returnCalloutID: Int): Result = ???

  override def proxySendGrpcStreamMessage(plugin: ExtismCurrentPlugin, calloutID: Int, grpcMessageData: Int, grpcMessageSize: Int): Result = ???

  override def proxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result = ???

  override def proxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result = ???

  override def proxyCallCustomFunction(plugin: ExtismCurrentPlugin, customFunctionID: Int, parametersData: Int, parametersSize: Int, returnResultsData: Int, returnResultsSize: Int): Result = ???

  override def copyIntoInstance(plugin: ExtismCurrentPlugin, memory: Pointer, value: IoBuffer, retPtr: Int, retSize: Int): Result = {
    val addr = plugin.alloc(value.length)

    memory.write(addr, value.buf.toArray, 0, value.length)

    memory.setInt(retPtr, addr)
    memory.setInt(retSize, value.length)

    ResultOk
  }

  override def proxyGetProperty(plugin: ExtismCurrentPlugin, data: VmData, pathPtr: Int, pathSize: Int, returnValueData: Int, returnValueSize: Int): Result = {
    traceVmHost("proxy_get_property")
    val mem = getMemory(plugin, pathPtr, pathSize)

    mem.fold(
      _ => ResultBadArgument,
      m => {
        if (m._2.isEmpty) {
          ResultBadArgument
        } else {
          val path = m._2.utf8String
                  .replace(Character.toString(0), ".")

          val value: ByteString = data.properties.getOrElse(path, ByteString(""))

          DEBUG("proxy_get_property", path + " : " + value)

          if (value == null) {
              return ResultNotFound
          }

          copyIntoInstance(plugin, m._1, new IoBuffer(value), returnValueData, returnValueSize)
        }
      }
    )
  }

  override def proxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Status = ???

  override def proxyResolveSharedQueue(vmIDData: ByteString, vmIDSize: Int, nameData: ByteString, nameSize: Int, returnID: Int): Status = ???

  override def proxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Status = ???

  override def proxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Status = ???

  override def proxyDone(): Status = {
    StatusOK
  }

  override def proxySetTickPeriodMilliseconds(data: VmData, period: Int): Status = {
    // TODO - manage tick period
    // data.setTickPeriod(period)
    StatusOK
  }

  override def proxySetEffectiveContext(plugin: ExtismCurrentPlugin, contextID: Int): Status = {
    // TODO - manage context id changes
    // this.contextId = contextID
    StatusOK
  }

  override def getPluginConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = {
    new IoBuffer(data.configuration)
  }

  override def getHttpRequestBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getHttpResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getDownStreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getUpstreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getHttpCalloutResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getVmConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def getCustomBuffer(bufferType: BufferType): IoBuffer = ???

  override def getHttpRequestHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = {
    System.out.println("CALL GetHttpRequestHeader")
    data
      .properties
      .filter(entry => entry._1.startsWith("request.") || entry._1.startsWith(":"))
  }

  override def getHttpRequestTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpRequestMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpResponseHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpCallResponseHeaders(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpCallResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getHttpCallResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def getCustomMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString] = ???

  override def getMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Error, (Pointer, ByteString)] = {
    val memory: Pointer = plugin.getLinearMemory("memory")
    if (memory == null) {
        return Error.ErrorExportsNotFound.left
    }

    // TODO - get memory size from RUST
//        long memoryLength = 1024 * 64 * 50// plugin.memoryLength(0)
//        if (addr > memoryLength || (addr+size) > memoryLength) {
//            return Either.left(Error.ErrAddrOverflow)
//        }

    (memory -> ByteString(memory.share(addr).getByteArray(0, size))).right[Error]
  }

  override def getMemory(plugin: ExtismCurrentPlugin): Either[Error, Pointer] = {
     val memory: Pointer = plugin.getLinearMemory("memory")
      if (memory == null) {
          return Error.ErrorExportsNotFound.left
      }

      memory.right
  }
}
