package otoroshi.wasm.proxywasm

import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.mvc.RequestHeader
import otoroshi.wasm.proxywasm._

object VmData {
  def from(request: RequestHeader, attrs: TypedMap)(implicit env: Env): VmData = {
    new VmData(
      configuration = "",
      tickPeriod = -1,
      properties = Map(
        "plugin_name" -> "foo".byteString,
        "plugin_root_id" -> "foo".byteString,
        "plugin_vm_id" -> "foo".byteString,
        "cluster_name" -> "foo".byteString,
        "route_name" -> "foo".byteString,
        "source.address" -> request.connection.remoteAddress.toString.split(":").apply(0).byteString,
        "source.port" -> request.connection.remoteAddress.toString.split(":").apply(1).byteString,
        "destination.address" -> request.remoteAddress.split(":").apply(0).byteString,
        "destination.port" -> request.remoteAddress.split(":").apply(1).byteString,
        "request.path" -> request.uri.byteString,
        "request.url_path" -> request.thePath.byteString,
        "request.host" -> request.domain.byteString,
        "request.scheme" -> request.theProtocol.byteString,
        "request.method" -> request.method.byteString,
        "request.protocol" -> request.theProtocol.byteString,
        "request.query" -> request.rawQueryString.byteString,
      )
      .applyOnWithOpt(request.headers.get("x-request-id")) {
        case (props, value) => props ++ Map("request.id" -> value.byteString)
      }
      .applyOnWithOpt(request.headers.get("Referer")) {
        case (props, value) => props ++ Map("request.referer" -> value.byteString)
      }
      .applyOnWithOpt(request.headers.get("User-Agent")) {
        case (props, value) => props ++ Map("request.useragent" -> value.byteString)
      }
      .applyOnWithOpt(attrs.get(otoroshi.plugins.Keys.RequestTimestampKey)) {
        case (props, value) => props ++ Map("request.time" -> value.toDate.getTime.toString.byteString)
      }
      .applyOn { props =>
        props ++ request.headers.toSimpleMap.map {
          case (key, value) => s"request.headers.${key}" -> value.byteString
        }
      }
    )
  }
}

case class VmData(configuration: String, properties: Map[String, ByteString], tickPeriod: Int = -1) extends HostUserData

trait Api {

  def proxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int): Result

  def proxyResumeStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxyCloseStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxySendHttpResponse(plugin: ExtismCurrentPlugin, responseCode: Int, responseCodeDetailsData: Int, responseCodeDetailsSize: Int,
                            responseBodyData: Int, responseBodySize: Int, additionalHeadersMapData: Int, additionalHeadersSize: Int,
                            grpcStatus: Int): Result

  def proxyResumeHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxyCloseHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def getBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: BufferType): IoBuffer

  def proxyGetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, maxSize: Int,
                     returnBufferData: Int, returnBufferSize: Int): Result

  def proxySetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, size: Int,
                     bufferData: Int, bufferSize: Int): Result

  def getMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString]

  def copyMapIntoInstance(m: Map[String, String], plugin: ExtismCurrentPlugin, returnMapData: Int, returnMapSize: Int): Unit

  def proxyGetHeaderMapPairs(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, returnDataPtr: Int, returnDataSize: Int): Int

  def proxyGetHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result

  def proxyReplaceHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int,  keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Result

  def proxyOpenSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreNameData: Int, kvstoreNameSiz: Int,  createIfNotExist: Int,
                             kvstoreID: Int): Result

  def proxyGetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     returnValuesData: Int, returnValuesSize: Int, returnCas: Int): Result

  def proxySetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     valuesData: Int, valuesSize: Int, cas: Int): Result

  def proxyAddSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int,
                                     valuesData: Int, valuesSize: Int, cas: Int): Result

  def proxyRemoveSharedKvstoreKey(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, cas: Int): Result

  def proxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Result

  def proxyOpenSharedQueue(plugin: ExtismCurrentPlugin, queueNameData: Int, queueNameSize: Int, createIfNotExist: Int,
                           returnQueueID: Int): Result

  def proxyDequeueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, returnPayloadData: Int, returnPayloadSize: Int): Result

  def proxyEnqueueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, payloadData: Int, payloadSize: Int): Result

  def proxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Result

  def proxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Result

  def proxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Result

  def proxyCreateMetric(plugin: ExtismCurrentPlugin, metricType: MetricType ,
                        metricNameData: Int, metricNameSize: Int, returnMetricID: Int): MetricType

  def proxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Result

  def proxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Result

  def proxyIncrementMetricValue(plugin: ExtismCurrentPlugin, data: VmData, metricID: Int, offset: Long): Result

  def proxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Result

  def proxyDefineMetric(plugin: ExtismCurrentPlugin, metricType: Int, namePtr: Int, nameSize: Int, returnMetricId: Int): Result

  def proxyDispatchHttpCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, headersMapData: Int, headersMapSize: Int,
                            bodyData: Int, bodySize: Int, trailersMapData: Int, trailersMapSize: Int, timeoutMilliseconds: Int,
                            returnCalloutID: Int): Result

  def proxyDispatchGrpcCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int,
                            serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int,
                            grpcMessageData: Int, grpcMessageSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Result

  def proxyOpenGrpcStream(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int,
                          serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int,
                          returnCalloutID: Int): Result

  def proxySendGrpcStreamMessage(plugin: ExtismCurrentPlugin, calloutID: Int, grpcMessageData: Int, grpcMessageSize: Int): Result

  def proxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def proxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def proxyCallCustomFunction(plugin: ExtismCurrentPlugin, customFunctionID: Int, parametersData: Int, parametersSize: Int,
                              returnResultsData: Int, returnResultsSize: Int): Result

  def copyIntoInstance(plugin: ExtismCurrentPlugin, memory: Pointer, value: IoBuffer, retPtr: Int, retSize: Int): Result

  def proxyGetProperty(plugin: ExtismCurrentPlugin, data: VmData, keyPtr: Int, keySize: Int, returnValueData: Int, returnValueSize: Int): Result

  def ProxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Status

  def ProxyResolveSharedQueue(vmIDData: ByteString, vmIDSize: Int, nameData: ByteString, nameSize: Int, returnID: Int): Status

  def ProxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Status

  def ProxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Status

  def ProxyDone(): Status

  def ProxySetTickPeriodMilliseconds(data: VmData, period: Int): Status

  def ProxySetEffectiveContext(plugin: ExtismCurrentPlugin, contextID: Int): Status

  def getPluginConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getHttpRequestBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getHttpResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getDownStreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getUpstreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getHttpCalloutResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getVmConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer

  def getCustomBuffer(bufferType: BufferType): IoBuffer

  def getHttpRequestHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpRequestTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpRequestMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpResponseHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpCallResponseHeaders(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpCallResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getHttpCallResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString]

  def getCustomMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString]

  def GetMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Error, (Pointer, ByteString)]

  def GetMemory(plugin: ExtismCurrentPlugin): Either[Error, Pointer]
}
