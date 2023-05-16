package otoroshi.wasm

import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk.ExtismCurrentPlugin
import otoroshi.wasm.proxywasm._

import java.util.Map

class ProxyWasmState extends Api {
  override def ProxyLog(plugin: ExtismCurrentPlugin, logLevel: Int, messageData: Int, messageSize: Int): Types.Result = ???

  override def ProxyResumeStream(plugin: ExtismCurrentPlugin, streamType: Types.StreamType): Types.Result = ???

  override def ProxyCloseStream(plugin: ExtismCurrentPlugin, streamType: Types.StreamType): Types.Result = ???

  override def ProxySendHttpResponse(plugin: ExtismCurrentPlugin, responseCode: Int, responseCodeDetailsData: Int, responseCodeDetailsSize: Int, responseBodyData: Int, responseBodySize: Int, additionalHeadersMapData: Int, additionalHeadersSize: Int, grpcStatus: Int): Types.Result = ???

  override def ProxyResumeHttpStream(plugin: ExtismCurrentPlugin, streamType: Types.StreamType): Types.Result = ???

  override def ProxyCloseHttpStream(plugin: ExtismCurrentPlugin, streamType: Types.StreamType): Types.Result = ???

  override def GetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Types.BufferType): IoBuffer = ???

  override def ProxyGetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, maxSize: Int, returnBufferData: Int, returnBufferSize: Int): Types.Result = ???

  override def ProxySetBuffer(plugin: ExtismCurrentPlugin, data: VmData, bufferType: Int, offset: Int, size: Int, bufferData: Int, bufferSize: Int): Types.Result = ???

  override def GetMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: Types.MapType): Map[String, ByteString] = ???

  override def copyMapIntoInstance(m: Map[String, String], plugin: ExtismCurrentPlugin, returnMapData: Int, returnMapSize: Int): Unit = ???

  override def ProxyGetHeaderMapPairs(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, returnDataPtr: Int, returnDataSize: Int): Int = ???

  override def ProxyGetHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Types.Result = ???

  override def ProxyReplaceHeaderMapValue(plugin: ExtismCurrentPlugin, data: VmData, mapType: Int, keyData: Int, keySize: Int, valueData: Int, valueSize: Int): Types.Result = ???

  override def ProxyOpenSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreNameData: Int, kvstoreNameSiz: Int, createIfNotExist: Int, kvstoreID: Int): Types.Result = ???

  override def ProxyGetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, returnValuesData: Int, returnValuesSize: Int, returnCas: Int): Types.Result = ???

  override def ProxySetSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, valuesData: Int, valuesSize: Int, cas: Int): Types.Result = ???

  override def ProxyAddSharedKvstoreKeyValues(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, valuesData: Int, valuesSize: Int, cas: Int): Types.Result = ???

  override def ProxyRemoveSharedKvstoreKey(plugin: ExtismCurrentPlugin, kvstoreID: Int, keyData: Int, keySize: Int, cas: Int): Types.Result = ???

  override def ProxyDeleteSharedKvstore(plugin: ExtismCurrentPlugin, kvstoreID: Int): Types.Result = ???

  override def ProxyOpenSharedQueue(plugin: ExtismCurrentPlugin, queueNameData: Int, queueNameSize: Int, createIfNotExist: Int, returnQueueID: Int): Types.Result = ???

  override def ProxyDequeueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, returnPayloadData: Int, returnPayloadSize: Int): Types.Result = ???

  override def ProxyEnqueueSharedQueueItem(plugin: ExtismCurrentPlugin, queueID: Int, payloadData: Int, payloadSize: Int): Types.Result = ???

  override def ProxyDeleteSharedQueue(plugin: ExtismCurrentPlugin, queueID: Int): Types.Result = ???

  override def ProxyCreateTimer(plugin: ExtismCurrentPlugin, period: Int, oneTime: Int, returnTimerID: Int): Types.Result = ???

  override def ProxyDeleteTimer(plugin: ExtismCurrentPlugin, timerID: Int): Types.Result = ???

  override def ProxyCreateMetric(plugin: ExtismCurrentPlugin, metricType: Types.MetricType, metricNameData: Int, metricNameSize: Int, returnMetricID: Int): Types.MetricType = ???

  override def ProxyGetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, returnValue: Int): Types.Result = ???

  override def ProxySetMetricValue(plugin: ExtismCurrentPlugin, metricID: Int, value: Int): Types.Result = ???

  override def ProxyIncrementMetricValue(plugin: ExtismCurrentPlugin, data: VmData, metricID: Int, offset: Long): Types.Result = ???

  override def ProxyDeleteMetric(plugin: ExtismCurrentPlugin, metricID: Int): Types.Result = ???

  override def ProxyDefineMetric(plugin: ExtismCurrentPlugin, metricType: Int, namePtr: Int, nameSize: Int, returnMetricId: Int): Types.Result = ???

  override def ProxyDispatchHttpCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, headersMapData: Int, headersMapSize: Int, bodyData: Int, bodySize: Int, trailersMapData: Int, trailersMapSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Types.Result = ???

  override def ProxyDispatchGrpcCall(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int, serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int, grpcMessageData: Int, grpcMessageSize: Int, timeoutMilliseconds: Int, returnCalloutID: Int): Types.Result = ???

  override def ProxyOpenGrpcStream(plugin: ExtismCurrentPlugin, upstreamNameData: Int, upstreamNameSize: Int, serviceNameData: Int, serviceNameSize: Int, serviceMethodData: Int, serviceMethodSize: Int, initialMetadataMapData: Int, initialMetadataMapSize: Int, returnCalloutID: Int): Types.Result = ???

  override def ProxySendGrpcStreamMessage(plugin: ExtismCurrentPlugin, calloutID: Int, grpcMessageData: Int, grpcMessageSize: Int): Types.Result = ???

  override def ProxyCancelGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Types.Result = ???

  override def ProxyCloseGrpcCall(plugin: ExtismCurrentPlugin, calloutID: Int): Types.Result = ???

  override def ProxyCallCustomFunction(plugin: ExtismCurrentPlugin, customFunctionID: Int, parametersData: Int, parametersSize: Int, returnResultsData: Int, returnResultsSize: Int): Types.Result = ???

  override def copyIntoInstance(plugin: ExtismCurrentPlugin, memory: Pointer, value: IoBuffer, retPtr: Int, retSize: Int): Types.Result = ???

  override def ProxyGetProperty(plugin: ExtismCurrentPlugin, data: VmData, keyPtr: Int, keySize: Int, returnValueData: Int, returnValueSize: Int): Types.Result = ???

  override def ProxyRegisterSharedQueue(nameData: ByteString, nameSize: Int, returnID: Int): Types.Status = ???

  override def ProxyResolveSharedQueue(vmIDData: ByteString, vmIDSize: Int, nameData: ByteString, nameSize: Int, returnID: Int): Types.Status = ???

  override def ProxyEnqueueSharedQueue(queueID: Int, valueData: ByteString, valueSize: Int): Types.Status = ???

  override def ProxyDequeueSharedQueue(queueID: Int, returnValueData: ByteString, returnValueSize: Int): Types.Status = ???

  override def ProxyDone(): Types.Status = ???

  override def ProxySetTickPeriodMilliseconds(data: VmData, period: Int): Types.Status = ???

  override def ProxySetEffectiveContext(plugin: ExtismCurrentPlugin, contextID: Int): Types.Status = ???

  override def GetPluginConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetHttpRequestBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetHttpResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetDownStreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetUpstreamData(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetHttpCalloutResponseBody(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetVmConfig(plugin: ExtismCurrentPlugin, data: VmData): IoBuffer = ???

  override def GetCustomBuffer(bufferType: Types.BufferType): IoBuffer = ???

  override def GetHttpRequestHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpRequestTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpRequestMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpResponseHeader(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpCallResponseHeaders(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpCallResponseTrailer(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetHttpCallResponseMetadata(plugin: ExtismCurrentPlugin, data: VmData): Map[String, ByteString] = ???

  override def GetCustomMap(plugin: ExtismCurrentPlugin, data: VmData, mapType: Types.MapType): Map[String, ByteString] = ???

  override def GetMemory(plugin: ExtismCurrentPlugin, addr: Int, size: Int): Either[Types.Error, Map.Entry[Pointer, ByteString]] = ???

  override def GetMemory(plugin: ExtismCurrentPlugin): Either[Types.Error, Pointer] = ???
}
