package otoroshi.wasm

import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk.ExtismCurrentPlugin
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.proxywasm.BufferType._
import otoroshi.wasm.proxywasm.MapType._
import otoroshi.wasm.proxywasm.Result._
import otoroshi.wasm.proxywasm.Status._
import otoroshi.wasm.proxywasm.WasmUtils.traceVmHost
import otoroshi.wasm.proxywasm.{IoBuffer, _}
import play.api.Logger

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class ProxyWasmState(
    val rootContextId: Int,
    val contextId: AtomicInteger,
    logCallback: Option[Function2[org.slf4j.event.Level, String, Unit]],
    env: Env
) extends Api {

  val logger = Logger("otoroshi-proxy-wasm")

  val u32Len = 4

  def unimplementedFunction[A](name: String): A = {
    logger.error(s"unimplemented state function: '${name}'")
    throw new NotImplementedError(s"proxy state method '${name}' is not implemented")
  }

  override def proxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int): Result = {
    // println(s"proxyLog: $logLevel - $messageData - $messageSize")
    getMemory(plugin, messageData, messageSize)
      .fold(
        Error.toResult,
        r => {
          val message = r._2.utf8String
          logLevel match {
            case 0 =>
              logger.trace(message)
              logCallback.foreach(_.apply(org.slf4j.event.Level.TRACE, message))
            case 1 =>
              logger.debug(message)
              logCallback.foreach(_.apply(org.slf4j.event.Level.DEBUG, message))
            case 2 =>
              logger.info(message)
              logCallback.foreach(_.apply(org.slf4j.event.Level.INFO, message))
            case 3 =>
              logger.warn(message)
              logCallback.foreach(_.apply(org.slf4j.event.Level.WARN, message))
            case _ =>
              logger.error(message)
              logCallback.foreach(_.apply(org.slf4j.event.Level.ERROR, message))
          }
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

  override def proxySendHttpResponse(
      plugin: ExtismCurrentPlugin,
      responseCode: Int,
      responseCodeDetailsData: Int,
      responseCodeDetailsSize: Int,
      responseBodyData: Int,
      responseBodySize: Int,
      additionalHeadersMapData: Int,
      additionalHeadersSize: Int,
      grpcStatus: Int,
      vmData: VmData
  ): Result = {
    traceVmHost(s"proxy_send_http_response: ${responseCode} - ${grpcStatus}")
    for {
      codeDetails <- getMemory(plugin, responseCodeDetailsData, responseCodeDetailsSize)
      body        <- getMemory(plugin, responseBodyData, responseBodySize)
      addHeaders  <- getMemory(plugin, additionalHeadersMapData, additionalHeadersSize)
    } yield {
      //WasmContextSlot.getCurrentContext().map(_.asInstanceOf[VmData]).foreach { vmdata =>
      // Json.obj(
      //   "http_status" -> responseCode,
      //   "grpc_code" -> grpcStatus,
      //   "details" -> codeDetails._2.utf8String,
      //   "body" -> body._2.utf8String,
      //   "headers" -> addHeaders._2.utf8String,
      // ).prettify.debugPrintln
      vmData.respRef.set(
        play.api.mvc.Results
          .Status(responseCode)(body._2)
          .withHeaders()    // TODO: read it
          .as("text/plain") // TODO: change it
      )
      //}
    }
    ResultOk
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
      case BufferTypeHttpRequestBody      =>
        getHttpRequestBody(plugin, data)
      case BufferTypeHttpResponseBody     =>
        getHttpResponseBody(plugin, data)
      case BufferTypeDownstreamData       =>
        getDownStreamData(plugin, data)
      case BufferTypeUpstreamData         =>
        getUpstreamData(plugin, data)
      //            case BufferTypeHttpCalloutResponseBody:
      //                GetHttpCalloutResponseBody(plugin, data)
      case BufferTypePluginConfiguration  =>
        getPluginConfig(plugin, data)
      case BufferTypeVmConfiguration      =>
        getVmConfig(plugin, data)
      case BufferTypeHttpCallResponseBody =>
        getHttpCalloutResponseBody(plugin, data)
      case _                              =>
        getCustomBuffer(bufferType)
    }
  }

  override def proxyGetBuffer(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      bufferType: Int,
      offset: Int,
      mSize: Int,
      returnBufferData: Int,
      returnBufferSize: Int
  ): Result = {
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

          //System.out.println(String.format("%s, %d,%d, %d, %d,", BufferType.valueToType(bufferType), offset, maxSize, returnBufferData, returnBufferSize))
          return copyIntoInstance(plugin, memory, bufferTypePluginConfiguration, returnBufferData, returnBufferSize)
        }
      )
  }

  override def proxySetBuffer(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      bufferType: Int,
      offset: Int,
      size: Int,
      bufferData: Int,
      bufferSize: Int
  ): Result = plugin.synchronized {
    traceVmHost("proxy_set_buffer")
    val buf = getBuffer(plugin, data, BufferType.valueToType(bufferType))
    if (buf == null) {
      return ResultBadArgument
    }

    val memory: Pointer = plugin.customMemoryGet()

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
    mapType match {
      case MapTypeHttpRequestHeaders       => getHttpRequestHeader(plugin, vmData)
      case MapTypeHttpRequestTrailers      => getHttpRequestTrailer(plugin, vmData)
      case MapTypeHttpRequestMetadata      => getHttpRequestMetadata(plugin, vmData)
      case MapTypeHttpResponseHeaders      => getHttpResponseHeader(plugin, vmData)
      case MapTypeHttpResponseTrailers     => getHttpResponseTrailer(plugin, vmData)
      case MapTypeHttpResponseMetadata     => getHttpResponseMetadata(plugin, vmData)
      case MapTypeHttpCallResponseHeaders  => getHttpCallResponseHeaders(plugin, vmData)
      case MapTypeHttpCallResponseTrailers => getHttpCallResponseTrailer(plugin, vmData)
      case MapTypeHttpCallResponseMetadata => getHttpCallResponseMetadata(plugin, vmData)
      case _                               => getCustomMap(plugin, vmData, mapType)
    }
  }

  def copyMapIntoInstance(
      m: Map[String, String],
      plugin: ExtismCurrentPlugin,
      returnMapData: Int,
      returnMapSize: Int
  ): Unit = unimplementedFunction("copyMapIntoInstance")

  override def proxyGetHeaderMapPairs(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      returnDataPtr: Int,
      returnDataSize: Int
  ): Int = {
    traceVmHost("proxy_get_map")
    val header = getMap(plugin, data, MapType.valueToType(mapType))

    if (header == null) {
      return ResultNotFound.value
    }

    var totalBytesLen = u32Len

    header.foreach(entry => {
      val key   = entry._1
      val value = entry._2

      totalBytesLen += u32Len + u32Len                     // keyLen + valueLen
      totalBytesLen += key.length() + 1 + value.length + 1 // key + \0 + value + \0
    })

    // TODO - try to call proxy_on_memory_allocate
    val addr = plugin.customMemoryAlloc(totalBytesLen)

    // TODO - manage error
//        if err != nil {
//            return int32(v2.ResultInvalidMemoryAccess)
//        }

    plugin.synchronized {

      val memory: Pointer = plugin.customMemoryGet()
      memory.setInt(addr, header.size)
      //        if err != nil {
      //            return int32(v2.ResultInvalidMemoryAccess)
      //        }

      var lenPtr  = addr + u32Len
      var dataPtr = lenPtr + (u32Len + u32Len) * header.size

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

        memory.write(dataPtr, v.toArray, 0, v.length)
        dataPtr += v.length
        memory.setByte(dataPtr, 0)
        dataPtr += 1
      })

      memory.setInt(returnDataPtr, addr)
      //        if err != nil {
      //            return int32(v2.ResultInvalidMemoryAccess)
      //        }

      memory.setInt(returnDataSize, totalBytesLen)
      //        if err != nil {
      //            return int32(v2.ResultInvalidMemoryAccess)
      //        }

    }
    ResultOk.value
  }

  override def proxyGetHeaderMapValue(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      keyData: Int,
      keySize: Int,
      valueData: Int,
      valueSize: Int
  ): Result = {
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
            value
              .map(v => copyIntoInstance(plugin, mem._1, new IoBuffer(v), valueData, valueSize))
              .getOrElse(ResultNotFound)
          }
        }
      )
  }

  override def proxyReplaceHeaderMapValue(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      keyData: Int,
      keySize: Int,
      valueData: Int,
      valueSize: Int
  ): Result = {
    traceVmHost("proxy_set_map_value")
    val m = getMap(plugin, data, MapType.valueToType(mapType))

    if (m == null || keySize == 0) {
      return ResultNotFound
    }

    val memKey   = getMemory(plugin, keyData, keySize)
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
              m ++ Map(key._2.utf8String -> value._2)

              ResultOk
            }
          )
        }
      )
  }

  override def proxyOpenSharedKvstore(
      plugin: ExtismCurrentPlugin,
      kvstoreNameData: Int,
      kvstoreNameSiz: Int,
      createIfNotExist: Int,
      kvstoreID: Int
  ): Result = unimplementedFunction("proxyOpenSharedKvstore")

  override def proxyGetSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      returnValuesData: Int,
      returnValuesSize: Int,
      returnCas: Int
  ): Result = unimplementedFunction("proxyGetSharedKvstoreKeyValues")

  override def proxySetSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      valuesData: Int,
      valuesSize: Int,
      cas: Int
  ): Result = unimplementedFunction("proxySetSharedKvstoreKeyValues")

  override def proxyAddSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      valuesData: Int,
      valuesSize: Int,
      cas: Int
  ): Result = unimplementedFunction("proxyAddSharedKvstoreKeyValues")

  override def proxyRemoveSharedKvstoreKey(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      cas: Int
  ): Result = unimplementedFunction("proxyRemoveSharedKvstoreKey")

  override def proxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Result = unimplementedFunction(
    "proxyDeleteSharedKvstore"
  )

  override def proxyOpenSharedQueue(
      plugin: ExtismCurrentPlugin,
      queueNameData: Int,
      queueNameSize: Int,
      createIfNotExist: Int,
      returnQueueID: Int
  ): Result = unimplementedFunction("proxyOpenSharedQueue")

  override def proxyDequeueSharedQueueItem(
      plugin: ExtismCurrentPlugin,
      queueID: Int,
      returnPayloadData: Int,
      returnPayloadSize: Int
  ): Result = unimplementedFunction("proxyDequeueSharedQueueItem")

  override def proxyEnqueueSharedQueueItem(
      plugin: ExtismCurrentPlugin,
      queueID: Int,
      payloadData: Int,
      payloadSize: Int
  ): Result = unimplementedFunction("proxyEnqueueSharedQueueItem")

  override def proxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Result = unimplementedFunction(
    "proxyDeleteSharedQueue"
  )

  override def proxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Result =
    unimplementedFunction("proxyCreateTimer")

  override def proxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Result = unimplementedFunction(
    "proxyDeleteTimer"
  )

  override def proxyCreateMetric(
      plugin: ExtismCurrentPlugin,
      metricType: MetricType,
      metricNameData: Int,
      metricNameSize: Int,
      returnMetricID: Int
  ): MetricType = unimplementedFunction("proxyCreateMetric")

  override def proxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Result = {
    // TODO - get metricID
    val value = 10

    getMemory(plugin)
      .fold(
        _ => ResultBadArgument,
        mem => {
          mem.setInt(returnValue, value)
          ResultOk
        }
      )
  }

  override def proxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Result =
    unimplementedFunction("proxySetMetricValue")

  override def proxyIncrementMetricValue(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      metricID: Int,
      offset: Long
  ): Result = {
    traceVmHost("proxy_increment_metric")
    ResultOk
  }

  override def proxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Result = unimplementedFunction(
    "proxyDeleteMetric"
  )

  override def proxyDefineMetric(
      plugin: ExtismCurrentPlugin,
      metricType: Int,
      namePtr: Int,
      nameSize: Int,
      returnMetricId: Int
  ): Result = {
    traceVmHost("proxy_define_metric")
    if (metricType > MetricType.last) {
      ResultBadArgument
    } else {

      getMemory(plugin, namePtr, nameSize)
        .fold(
          _ => ResultBadArgument,
          mem => {
            // mid = ih.DefineMetric(v1.MetricType(metricType), mem._2.utf8String)

            val mid = 1
            mem._1.setInt(returnMetricId, mid)
            ResultOk
          }
        )
    }
  }

  override def proxyDispatchHttpCall(
      plugin: ExtismCurrentPlugin,
      upstreamNameData: Int,
      upstreamNameSize: Int,
      headersMapData: Int,
      headersMapSize: Int,
      bodyData: Int,
      bodySize: Int,
      trailersMapData: Int,
      trailersMapSize: Int,
      timeoutMilliseconds: Int,
      returnCalloutID: Int
  ): Result = unimplementedFunction("proxyDispatchHttpCall")

  override def proxyDispatchGrpcCall(
      plugin: ExtismCurrentPlugin,
      upstreamNameData: Int,
      upstreamNameSize: Int,
      serviceNameData: Int,
      serviceNameSize: Int,
      serviceMethodData: Int,
      serviceMethodSize: Int,
      initialMetadataMapData: Int,
      initialMetadataMapSize: Int,
      grpcMessageData: Int,
      grpcMessageSize: Int,
      timeoutMilliseconds: Int,
      returnCalloutID: Int
  ): Result = unimplementedFunction("proxyDispatchGrpcCall")

  override def proxyOpenGrpcStream(
      plugin: ExtismCurrentPlugin,
      upstreamNameData: Int,
      upstreamNameSize: Int,
      serviceNameData: Int,
      serviceNameSize: Int,
      serviceMethodData: Int,
      serviceMethodSize: Int,
      initialMetadataMapData: Int,
      initialMetadataMapSize: Int,
      returnCalloutID: Int
  ): Result = unimplementedFunction("proxyOpenGrpcStream")

  override def proxySendGrpcStreamMessage(
      plugin: ExtismCurrentPlugin,
      calloutID: Int,
      grpcMessageData: Int,
      grpcMessageSize: Int
  ): Result = unimplementedFunction("proxySendGrpcStreamMessage")

  override def proxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result = unimplementedFunction(
    "proxyCancelGrpcCall"
  )

  override def proxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result = unimplementedFunction(
    "proxyCloseGrpcCall"
  )

  override def proxyCallCustomFunction(
      plugin: ExtismCurrentPlugin,
      customFunctionID: Int,
      parametersData: Int,
      parametersSize: Int,
      returnResultsData: Int,
      returnResultsSize: Int
  ): Result = unimplementedFunction("proxyCallCustomFunction")

  override def copyIntoInstance(
      plugin: ExtismCurrentPlugin,
      memory: Pointer,
      value: IoBuffer,
      retPtr: Int,
      retSize: Int
  ): Result = {
    val addr = plugin.customMemoryAlloc(value.length)

    memory.write(addr, value.buf.toArray, 0, value.length)

    memory.setInt(retPtr, addr)
    memory.setInt(retSize, value.length)

    ResultOk
  }

  override def proxyGetProperty(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      pathPtr: Int,
      pathSize: Int,
      returnValueData: Int,
      returnValueSize: Int
  ): Result = {
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

          val value: Array[Byte] = data.properties.getOrElse(path, ByteString.empty.toArray)

          if (value == null) {
            return ResultNotFound
          }

          copyIntoInstance(plugin, m._1, new IoBuffer(ByteString(value)), returnValueData, returnValueSize)
        }
      }
    )
  }

  override def proxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Status =
    unimplementedFunction("proxyRegisterSharedQueue")

  override def proxyResolveSharedQueue(
      vmIDData: ByteString,
      vmIDSize: Int,
      nameData: ByteString,
      nameSize: Int,
      returnID: Int
  ): Status = unimplementedFunction("proxyResolveSharedQueue")

  override def proxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Status =
    unimplementedFunction("proxyEnqueueSharedQueue")

  override def proxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Status =
    unimplementedFunction("proxyDequeueSharedQueue")

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

  override def getHttpRequestBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = {
    data.bodyIn match {
      case None       => new IoBuffer(ByteString.empty)
      case Some(body) => new IoBuffer(body)
    }
  }

  override def getHttpResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = {
    data.bodyOut match {
      case None       => new IoBuffer(ByteString.empty)
      case Some(body) => new IoBuffer(body)
    }
  }

  override def getDownStreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = unimplementedFunction(
    "getDownStreamData"
  )

  override def getUpstreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = unimplementedFunction(
    "getUpstreamData"
  )

  override def getHttpCalloutResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = unimplementedFunction(
    "getHttpCalloutResponseBody"
  )

  override def getVmConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = unimplementedFunction("getVmConfig")

  override def getCustomBuffer(bufferType: BufferType): IoBuffer = unimplementedFunction("getCustomBuffer")

  override def getHttpRequestHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = {
    data.properties
      .filter(entry => entry._1.startsWith("request.headers.") || entry._1.startsWith(":"))
      .map(t => (t._1.replace("request.headers.", ""), ByteString(t._2)))
  }

  override def getHttpRequestTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = {
    Map.empty
  }

  override def getHttpRequestMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = {
    Map.empty
  }

  override def getHttpResponseHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = {
    data.properties
      .filter(entry => entry._1.startsWith("response.headers.") || entry._1.startsWith(":"))
      .map(t => (t._1.replace("response.headers.", ""), ByteString(t._2)))
  }

  override def getHttpResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] =
    unimplementedFunction("getHttpResponseTrailer")

  override def getHttpResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] =
    unimplementedFunction("getHttpResponseMetadata")

  override def getHttpCallResponseHeaders(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] =
    unimplementedFunction("getHttpCallResponseHeaders")

  override def getHttpCallResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] =
    unimplementedFunction("getHttpCallResponseTrailer")

  override def getHttpCallResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] =
    unimplementedFunction("getHttpCallResponseMetadata")

  override def getCustomMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString] =
    unimplementedFunction("getCustomMap")

  override def getMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Error, (Pointer, ByteString)] =
    plugin.synchronized {

      val memory: Pointer = plugin.customMemoryGet()
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

  override def getMemory(plugin: ExtismCurrentPlugin): Either[Error, Pointer] = plugin.synchronized {

    val memory: Pointer = plugin.customMemoryGet()
    if (memory == null) {
      return Error.ErrorExportsNotFound.left
    }

    memory.right
  }
}
