package otoroshi.wasm.proxywasm

import akka.util.ByteString
import com.sun.jna.Pointer
import io.otoroshi.wasm4s.scaladsl.WasmVmData
import org.extism.sdk.{ExtismCurrentPlugin, HostUserData}
import org.extism.sdk.wasmotoroshi._
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.NgPluginHttpResponse
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsValue
import play.api.mvc
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic.AtomicReference

object VmData {
  def empty(): VmData                   = VmData(
    configuration = "",
    properties = Map.empty,
    tickPeriod = -1,
    respRef = new AtomicReference[mvc.Result](null),
    bodyInRef = new AtomicReference[ByteString](null),
    bodyOutRef = new AtomicReference[ByteString](null),
    requestRef = None,
    routeRef = None
  )
  def withRules(rules: JsValue): VmData = VmData.empty().copy(configuration = rules.stringify)
  def from(request: RequestHeader, route: Option[NgRoute], attrs: TypedMap)(implicit env: Env): VmData = {
    val remote = request.headers
      .get("remote-address")
      .getOrElse(s"${request.connection.remoteAddress.toString.substring(1)}:${1234}")
    new VmData(
      configuration = "",
      respRef = new AtomicReference[play.api.mvc.Result](null),
      bodyInRef = new AtomicReference[ByteString](null),
      bodyOutRef = new AtomicReference[ByteString](null),
      requestRef = Some(request),
      routeRef = route,
      tickPeriod = -1,
      properties = Map(
        "plugin_name"         -> "foo".bytes,
        "plugin_root_id"      -> "foo".bytes,
        "plugin_vm_id"        -> "foo".bytes,
        "cluster_name"        -> "foo".bytes,
        "route_name"          -> "foo".bytes,
        "source.address"      -> remote.bytes,
        //"source.port" -> remote.split(":")(1).toLong.bytes,
        "destination.address" -> s"${request.theDomain}:${env.httpPort}".bytes,
        //"destination.port" -> env.httpPort.toLong.bytes,
        "request.path"        -> request.uri.bytes,
        "request.url_path"    -> request.thePath.bytes,
        "request.host"        -> request.host.bytes,
        "request.scheme"      -> request.theProtocol.bytes,
        "request.method"      -> request.method.bytes,
        "request.protocol"    -> request.version.bytes,
        "request.query"       -> request.rawQueryString.bytes,
        ":method"             -> request.method.bytes,
        ":path"               -> request.thePath.bytes,
        ":authority"          -> request.host.bytes,
        ":scheme"             -> request.theProtocol.bytes
      )
        .applyOnWithOpt(request.headers.get("x-request-id")) { case (props, value) =>
          props ++ Map("request.id" -> value.bytes)
        }
        .applyOnWithOpt(request.headers.get("Referer")) { case (props, value) =>
          props ++ Map("request.referer" -> value.bytes)
        }
        .applyOnWithOpt(request.headers.get("User-Agent")) { case (props, value) =>
          props ++ Map("request.useragent" -> value.bytes)
        }
        .applyOnWithOpt(attrs.get(otoroshi.plugins.Keys.RequestTimestampKey)) { case (props, value) =>
          props ++ Map("request.time" -> value.toDate.getTime.toString.bytes)
        }
        .applyOn { props =>
          props ++ request.headers.toSimpleMap
            .filterNot(_._1.toLowerCase() == "tls-session-info")
            .filterNot(_._1.toLowerCase() == "timeout-access")
            .map { case (key, value) =>
              s"request.headers.${key.toLowerCase()}" -> value.bytes
            }
        }
    )
  }
}

case class VmData(
    configuration: String,
    properties: Map[String, Array[Byte]],
    tickPeriod: Int = -1,
    respRef: AtomicReference[play.api.mvc.Result],
    bodyInRef: AtomicReference[ByteString],
    bodyOutRef: AtomicReference[ByteString],
    requestRef: Option[RequestHeader],
    routeRef: Option[NgRoute]
) extends HostUserData
    with WasmVmData {
  def withRequest(request: RequestHeader, route: Option[NgRoute], attrs: TypedMap)(implicit env: Env): VmData = {
    VmData
      .from(request, route, attrs)
      .copy(
        configuration = configuration,
        tickPeriod = tickPeriod,
        respRef = respRef,
        bodyInRef = bodyInRef,
        bodyOutRef = bodyOutRef
      )
  }
  def withResponse(response: NgPluginHttpResponse, attrs: TypedMap, body_bytes: Option[ByteString])(implicit
      env: Env
  ): VmData = {
    val newProps: Map[String, Array[Byte]] = properties ++ Map(
      "response.code"         -> response.status.bytes,
      "response.code_details" -> "".bytes,
      //"response.flags"        -> -1.bytes,
      //"response.grpc_status"  -> -1.bytes,
      ":status"               -> response.status.toString.bytes
    )
      .applyOnWithOpt(body_bytes.map(_.size).orElse(response.contentLength)) { case (props, len) =>
        props ++ Map(
          "response.size"                   -> len.toString.bytes,
          "response.headers.content-length" -> len.toString.bytes
        )
      }
      .applyOn { props =>
        props ++ response.headers.map { case (key, value) =>
          s"response.headers.${key.toLowerCase()}" -> value.bytes
        }
      }
    copy(
      configuration = configuration,
      properties = newProps,
      tickPeriod = tickPeriod,
      respRef = respRef,
      bodyInRef = bodyInRef,
      bodyOutRef = bodyOutRef,
      requestRef = requestRef,
      routeRef = routeRef
    )
  }
  def httpResponse: Option[play.api.mvc.Result] = Option(respRef.get())
  def bodyIn: Option[ByteString]                = Option(bodyInRef.get())
  def bodyOut: Option[ByteString]               = Option(bodyOutRef.get())
  def request: Option[RequestHeader]            = requestRef
  def route: Option[NgRoute]                    = routeRef
}

trait Api {

  def proxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int, data: VmData): Result

  def proxyResumeStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxyCloseStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxySendHttpResponse(
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
  ): Result

  def proxyResumeHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def proxyCloseHttpStream(plugin: ExtismCurrentPlugin, streamType: StreamType): Result

  def getBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: BufferType): IoBuffer

  def proxyGetBuffer(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      bufferType: Int,
      offset: Int,
      maxSize: Int,
      returnBufferData: Int,
      returnBufferSize: Int
  ): Result

  def proxySetBuffer(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      bufferType: Int,
      offset: Int,
      size: Int,
      bufferData: Int,
      bufferSize: Int
  ): Result

  def getMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: MapType): Map[String, ByteString]

  def copyMapIntoInstance(
      m: Map[String, String],
      plugin: ExtismCurrentPlugin,
      returnMapData: Int,
      returnMapSize: Int
  ): Unit

  def proxyGetHeaderMapPairs(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      returnDataPtr: Int,
      returnDataSize: Int
  ): Int

  def proxyGetHeaderMapValue(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      keyData: Int,
      keySize: Int,
      valueData: Int,
      valueSize: Int
  ): Result

  def proxyReplaceHeaderMapValue(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      mapType: Int,
      keyData: Int,
      keySize: Int,
      valueData: Int,
      valueSize: Int
  ): Result

  def proxyOpenSharedKvstore(
      plugin: ExtismCurrentPlugin,
      kvstoreNameData: Int,
      kvstoreNameSiz: Int,
      createIfNotExist: Int,
      kvstoreID: Int
  ): Result

  def proxyGetSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      returnValuesData: Int,
      returnValuesSize: Int,
      returnCas: Int
  ): Result

  def proxySetSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      valuesData: Int,
      valuesSize: Int,
      cas: Int
  ): Result

  def proxyAddSharedKvstoreKeyValues(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      valuesData: Int,
      valuesSize: Int,
      cas: Int
  ): Result

  def proxyRemoveSharedKvstoreKey(
      plugin: ExtismCurrentPlugin,
      kvstoreID: Int,
      keyData: Int,
      keySize: Int,
      cas: Int
  ): Result

  def proxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Result

  def proxyOpenSharedQueue(
      plugin: ExtismCurrentPlugin,
      queueNameData: Int,
      queueNameSize: Int,
      createIfNotExist: Int,
      returnQueueID: Int
  ): Result

  def proxyDequeueSharedQueueItem(
      plugin: ExtismCurrentPlugin,
      queueID: Int,
      returnPayloadData: Int,
      returnPayloadSize: Int
  ): Result

  def proxyEnqueueSharedQueueItem(
      plugin: ExtismCurrentPlugin,
      queueID: Int,
      payloadData: Int,
      payloadSize: Int
  ): Result

  def proxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Result

  def proxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Result

  def proxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Result

  def proxyCreateMetric(
      plugin: ExtismCurrentPlugin,
      metricType: MetricType,
      metricNameData: Int,
      metricNameSize: Int,
      returnMetricID: Int
  ): MetricType

  def proxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Result

  def proxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Result

  def proxyIncrementMetricValue(plugin: ExtismCurrentPlugin, data: VmData, metricID: Int, offset: Long): Result

  def proxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Result

  def proxyDefineMetric(
      plugin: ExtismCurrentPlugin,
      metricType: Int,
      namePtr: Int,
      nameSize: Int,
      returnMetricId: Int
  ): Result

  def proxyDispatchHttpCall(
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
  ): Result

  def proxyDispatchGrpcCall(
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
  ): Result

  def proxyOpenGrpcStream(
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
  ): Result

  def proxySendGrpcStreamMessage(
      plugin: ExtismCurrentPlugin,
      calloutID: Int,
      grpcMessageData: Int,
      grpcMessageSize: Int
  ): Result

  def proxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def proxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Result

  def proxyCallCustomFunction(
      plugin: ExtismCurrentPlugin,
      customFunctionID: Int,
      parametersData: Int,
      parametersSize: Int,
      returnResultsData: Int,
      returnResultsSize: Int
  ): Result

  def copyIntoInstance(
      plugin: ExtismCurrentPlugin,
      memory: Pointer,
      value: IoBuffer,
      retPtr: Int,
      retSize: Int
  ): Result

  def proxyGetProperty(
      plugin: ExtismCurrentPlugin,
      data: VmData,
      keyPtr: Int,
      keySize: Int,
      returnValueData: Int,
      returnValueSize: Int
  ): Result

  def proxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Status

  def proxyResolveSharedQueue(
      vmIDData: ByteString,
      vmIDSize: Int,
      nameData: ByteString,
      nameSize: Int,
      returnID: Int
  ): Status

  def proxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Status

  def proxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Status

  def proxyDone(): Status

  def proxySetTickPeriodMilliseconds(data: VmData, period: Int): Status

  def proxySetEffectiveContext(plugin: ExtismCurrentPlugin, contextID: Int): Status

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

  def getMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Error, (Pointer, ByteString)]

  def getMemory(plugin: ExtismCurrentPlugin): Either[Error, Pointer]
}
