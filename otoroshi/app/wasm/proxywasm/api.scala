package otoroshi.wasm.proxywasm

import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk._
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.proxywasm.Types._

object VmData {

  def default(request: Map[String, String]): VmData = {
    new VmData(
      configuration = "",
      tickPeriod = -1,
      properties = Map(
        "plugin_root_id"-> "proxy-wasm".byteString,
        "source.address" -> "127.0.0.1".byteString,
        "source.port" -> "8080".byteString,
        "destination.address" -> "127.0.0.1".byteString,
        "destination.port" -> "12345".byteString,
      ) ++ request.flatMap {
        case (key, value) => Map(
          s"request.$key" ->, value.byteString,
        )
      }
    )
  }
}

case class VmData(configuration: String, properties: Map[String, ByteString], tickPeriod: Int = -1) extends HostUserData

trait Api {

  def ProxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int): Result

  def ProxyResumeStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def ProxyCloseStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def ProxySendHttpResponse(plugin: ExtismCurrentPlugin, responseCode: Int, responseCodeDetailsData: Int, responseCodeDetailsSize: Int,
                            responseBodyData: Int, responseBodySize: Int, additionalHeadersMapData: Int, additionalHeadersSize: Int,
                            grpcStatus: Int): Result

  def ProxyResumeHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def ProxyCloseHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def GetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: BufferType): IoBuffer

  def ProxyGetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, maxSize: Int,
                     returnBufferData: Int, returnBufferSize: Int): Result

  def ProxySetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, size: Int,
                     bufferData: Int, bufferSize: Int): Result

  def GetMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString]

  def copyMapIntoInstance(m: Map[String, String], plugin: ExtismCurrentPlugin, returnMapData: Int, returnMapSize: Int): Unit

  def ProxyGetHeaderMapPairs(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, returnDataPtr: Int, returnDataSize: Int): Int

  def ProxyGetHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result

  def ProxyReplaceHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int,  keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result

  def ProxyOpenSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreNameData: Int, kvstoreNameSiz: Int,  createIfNotExist: Int,
                             kvstoreID: Int): Result

  def ProxyGetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     returnValuesData: Int, returnValuesSize: Int, returnCas: Int): Result

  def ProxySetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     valuesData: Int, valuesSize: Int, cas: Int): Result

  def ProxyAddSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     valuesData: Int, valuesSize: Int, cas: Int): Result

  def ProxyRemoveSharedKvstoreKey(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, cas: Int): Result

  def ProxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Result

  def ProxyOpenSharedQueue(plugin: ExtismCurrentPlugin, queueNameData: Int, queueNameSize: Int, createIfNotExist: Int,
                           returnQueueID: Int): Result

  def ProxyDequeueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, returnPayloadData: Int, returnPayloadSize: Int): Result

  def ProxyEnqueueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, payloadData: Int, payloadSize: Int): Result

  def ProxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Result

  def ProxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Result

  def ProxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Result

  def ProxyCreateMetric(plugin: ExtismCurrentPlugin, metricType: MetricType ,
                        metricNameData: Int, metricNameSize: Int, returnMetricID: Int): MetricType

  def ProxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Result

  def ProxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Result

  def ProxyIncrementMetricValue(plugin: ExtismCurrentPlugin, data: VmData, metricID: Int, offset: Long): Result

  def ProxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Result

  def ProxyDefineMetric(plugin: ExtismCurrentPlugin, metricType: Int, namePtr: Int, nameSize: Int, returnMetricId: Int): Result

  def ProxyDispatchHttpCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, headersMapData: Int, headersMapSize: Int,
                            bodyData: Int, bodySize: Int, trailersMapData: Int, trailersMapSize: Int, timeoutMilliseconds: Int,
                            returnCalloutID: Int): Result

  def ProxyDispatchGrpcCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int,
                            serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int,
                            grpcMessageData: Int, grpcMessageSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Result

  def ProxyOpenGrpcStream(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int,
                          serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int,
                          returnCalloutID: Int): Result

  def ProxySendGrpcStreamMessage(plugin: ExtismCurrentPlugin, calloutID: Int, grpcMessageData: Int, grpcMessageSize: Int): Result

  def ProxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def ProxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def ProxyCallCustomFunction(plugin: ExtismCurrentPlugin, customFunctionID: Int, parametersData: Int, parametersSize: Int,
                              returnResultsData: Int, returnResultsSize: Int): Result

  def copyIntoInstance(plugin: ExtismCurrentPlugin, memory: Pointer, value: IoBuffer, retPtr: Int, retSize: Int): Result

  def ProxyGetProperty(plugin: ExtismCurrentPlugin, data: VmData, keyPtr: Int, keySize: Int, returnValueData: Int, returnValueSize: Int): Result

  def ProxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Types.Status

  def ProxyResolveSharedQueue(vmIDData: ByteString, vmIDSize: Int, nameData: ByteString, nameSize: Int, returnID: Int): Types.Status

  def ProxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Types.Status

  def ProxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Types.Status

  def ProxyDone(): Types.Status

  def ProxySetTickPeriodMilliseconds(data: VmData, period: Int): Types.Status

  def ProxySetEffectiveContext(plugin: ExtismCurrentPlugin, contextID: Int): Types.Status

  def GetPluginConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetHttpRequestBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetHttpResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetDownStreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetUpstreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetHttpCalloutResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetVmConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def GetCustomBuffer(bufferType: BufferType): IoBuffer

  def GetHttpRequestHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpRequestTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpRequestMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpResponseHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpCallResponseHeaders(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpCallResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetHttpCallResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def GetCustomMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString]

  def GetMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Types.Error, (Pointer, ByteString)]

  def GetMemory(plugin: ExtismCurrentPlugin): Either[Types.Error, Pointer]
}
