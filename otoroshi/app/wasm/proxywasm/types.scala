package otoroshi.wasm.proxywasm;

sealed trait Result {
  def value: Int
}

object Result {
  case object ResultOk extends Result {
    def value: Int = 0
  }
  case object ResultEmpty extends Result {
    def value: Int = 1
  }
  case object ResultNotFound extends Result {
    def value: Int = 2
  }
  case object ResultNotAllowed extends Result {
    def value: Int = 3
  }
  case object ResultBadArgument extends Result {
    def value: Int = 4
  }
  case object ResultInvalidMemoryAccess extends Result {
    def value: Int = 5
  }
  case object ResultInvalidOperation extends Result {
    def value: Int = 6
  }
  case object ResultCompareAndSwapMismatch extends Result {
    def value: Int = 7
  }
  case object ResultUnimplemented extends Result {
    def value: Int = 12
  }
}

sealed trait Action {
  def value: Int
}

object Action {
  case object ActionContinue extends Action {
    def value: Int = 1
  }
  case object ActionEndStream extends Action {
    def value: Int = 2
  }
  case object ActionDone extends Action {
    def value: Int = 3
  }
  case object ActionPause extends Action {
    def value: Int = 4
  }
  case object ActionWaitForMoreData extends Action {
    def value: Int = 5
  }
  case object ActionWaitForEndOrFull extends Action {
    def value: Int = 6
  }
  case object ActionClose extends Action {
    def value: Int = 7
  }
}

sealed trait MetricType {
  def value: Int
}

object MetricType {
  case object MetricTypeCounter extends MetricType {
    def value: Int = 1
  }
  case object MetricTypeGauge extends MetricType {
    def value: Int = 2
  }
  case object MetricTypeHistogram extends MetricType {
    def value: Int = 3
  }
}

sealed trait MapType {
  def value: Int
}

object MapType {

  def valueToType(n: Int): MapType = n match {
    case MapTypeHttpRequestHeaders.value => MapTypeHttpRequestHeaders
    case MapTypeHttpRequestTrailers.value => MapTypeHttpRequestTrailers
    case MapTypeHttpRequestMetadata.value => MapTypeHttpRequestMetadata
    case MapTypeHttpResponseHeaders.value => MapTypeHttpResponseHeaders
    case MapTypeHttpResponseTrailers.value => MapTypeHttpResponseTrailers
    case MapTypeHttpResponseMetadata.value => MapTypeHttpResponseMetadata
    case MapTypeHttpCallResponseHeaders.value => MapTypeHttpCallResponseHeaders
    case MapTypeHttpCallResponseTrailers.value => MapTypeHttpCallResponseTrailers
    case MapTypeHttpCallResponseMetadata.value => MapTypeHttpCallResponseMetadata
    case _ => ???
  }
  case object MapTypeHttpRequestHeaders extends MapType {
    def value: Int = 0
  }
  case object MapTypeHttpRequestTrailers extends MapType {
    def value: Int = 1
  }
  case object MapTypeHttpRequestMetadata extends MapType {
    def value: Int = 2
  }
  case object MapTypeHttpResponseHeaders extends MapType {
    def value: Int = 3
  }
  case object MapTypeHttpResponseTrailers extends MapType {
    def value: Int = 4
  }
  case object MapTypeHttpResponseMetadata extends MapType {
    def value: Int = 5
  }
  case object MapTypeHttpCallResponseHeaders extends MapType {
    def value: Int = 6
  }
  case object MapTypeHttpCallResponseTrailers extends MapType {
    def value: Int = 7
  }
  case object MapTypeHttpCallResponseMetadata extends MapType {
    def value: Int = 8
  }
}

sealed trait BufferType {
  def value: Int
}

object BufferType {
  val last = 8

  def valueToType(v: Int): BufferType = v match {
    case BufferTypeHttpRequestBody.value => BufferTypeHttpRequestBody
    case BufferTypeHttpResponseBody.value => BufferTypeHttpResponseBody
    case BufferTypeDownstreamData.value => BufferTypeDownstreamData
    case BufferTypeUpstreamData.value => BufferTypeUpstreamData
    case BufferTypeHttpCallResponseBody.value => BufferTypeHttpCallResponseBody
    case BufferTypeGrpcReceiveBuffer.value => BufferTypeGrpcReceiveBuffer
    case BufferTypeVmConfiguration.value => BufferTypeVmConfiguration
    case BufferTypePluginConfiguration.value => BufferTypePluginConfiguration
    case BufferTypeCallData.value => BufferTypeCallData
    case _ => ???
  }

  case object BufferTypeHttpRequestBody extends BufferType {
    def value: Int = 0
  }
  case object BufferTypeHttpResponseBody extends BufferType {
    def value: Int = 1
  }
  case object BufferTypeDownstreamData extends BufferType {
    def value: Int = 2
  }
  case object BufferTypeUpstreamData extends BufferType {
    def value: Int = 3
  }
  case object BufferTypeHttpCallResponseBody extends BufferType {
    def value: Int = 4
  }
  case object BufferTypeGrpcReceiveBuffer extends BufferType {
    def value: Int = 5
  }
  case object BufferTypeVmConfiguration extends BufferType {
    def value: Int = 6
  }
  case object BufferTypePluginConfiguration extends BufferType {
    def value: Int = 7
  }
  case object BufferTypeCallData extends BufferType {
    def value: Int = 8
  }
}

sealed trait StreamType {
  def value: Int
}

object StreamType {
  case object StreamTypeDownstream extends StreamType {
    def value: Int = 1
  }
  case object StreamTypeUpstream extends StreamType {
    def value: Int = 2
  }
  case object StreamTypeHttpRequest extends StreamType {
    def value: Int = 3
  }
  case object StreamTypeHttpResponse extends StreamType {
    def value: Int = 4
  }
}

sealed trait ContextType {
  def value: Int
}

object ContextType {
  case object ContextTypeVmContext extends ContextType {
    def value: Int = 1
  }
  case object ContextTypePluginContext extends ContextType {
    def value: Int = 2
  }
  case object ContextTypeStreamContext extends ContextType {
    def value: Int = 3
  }
  case object ContextTypeHttpContext extends ContextType {
    def value: Int = 4
  }
}

sealed trait LogLevel {
  def value: Int
}

object LogLevel {
  case object LogLevelTrace extends LogLevel {
    def value: Int = 1
  }
  case object LogLevelDebug extends LogLevel {
    def value: Int = 2
  }
  case object LogLevelWarning extends LogLevel {
    def value: Int = 3
  }
  case object LogLevelInfo extends LogLevel {
    def value: Int = 4
  }
  case object LogLevelWarn extends LogLevel {
    def value: Int = 5
  }
  case object LogLevelError extends LogLevel {
    def value: Int = 6
  }
  case object LogLevelCritical extends LogLevel {
    def value: Int = 7
  }
}

sealed trait CloseSourceType {
  def value: Int
}

object CloseSourceType {
  case object CloseSourceTypeLocal extends CloseSourceType {
    def value: Int = 1
  }
  case object CloseSourceTypeRemote extends CloseSourceType {
    def value: Int = 2
  }
}

sealed trait Error {
  def value: String
}

object Error {
  case object ErrorStatusNotFound extends Error {
    def value: String = "error status returned by host: not found"
  }
  case object ErrorStatusBadArgument extends Error {
    def value: String = "error status returned by host: bad argument"
  }
  case object ErrorStatusEmpty extends Error {
    def value: String = "error status returned by host: empty"
  }
  case object ErrorStatusCasMismatch extends Error {
    def value: String = "error status returned by host: cas mismatch"
  }
  case object ErrorInternalFailure extends Error {
    def value: String = "error status returned by host: internal failure"
  }
  case object ErrorUnimplemented extends Error {
    def value: String = "error status returned by host: unimplemented"
  }
  case object ErrorUnknownStatus extends Error {
    def value: String = "unknown status code"
  }
  case object ErrorExportsNotFound extends Error {
    def value: String = "exports not found"
  }
  case object ErrAddrOverflow extends Error {
    def value: String = "addr overflow"
  }

  def toResult(error: Error): Result = error match {
    case ErrorStatusNotFound | ErrorUnknownStatus | ErrorExportsNotFound =>
      Result.ResultNotFound
    case ErrorStatusBadArgument =>
      Result.ResultBadArgument
    case ErrorStatusEmpty =>
      Result.ResultEmpty
    case ErrorStatusCasMismatch =>
      Result.ResultCompareAndSwapMismatch
    case ErrorInternalFailure | ErrAddrOverflow =>
      Result.ResultInvalidOperation
    case ErrorUnimplemented =>
      Result.ResultUnimplemented
    case _ =>
      Result.ResultInvalidOperation
  }
}


sealed trait Status {
  def value: Int
}

object Status {
  case object StatusOK extends Status {
    def value: Int = 0
  }
  case object StatusNotFound extends Status {
    def value: Int = 1
  }
  case object StatusBadArgument extends Status {
    def value: Int = 2
  }
  case object StatusEmpty extends Status {
    def value: Int = 7
  }
  case object StatusCasMismatch extends Status {
    def value: Int = 8
  }
  case object StatusInternalFailure extends Status {
    def value: Int = 10
  }
  case object StatusUnimplemented extends Status {
    def value: Int = 12
  }

  def StatusToError(status: Status): Error = status match {
    case StatusOK =>
      null
    case StatusNotFound =>
      Error.ErrorStatusNotFound
    case StatusBadArgument =>
      Error.ErrorStatusBadArgument
    case StatusEmpty =>
      Error.ErrorStatusEmpty
    case StatusCasMismatch =>
      Error.ErrorStatusCasMismatch
    case StatusInternalFailure =>
      Error.ErrorInternalFailure
    case StatusUnimplemented =>
      Error.ErrorUnimplemented
    case _ => Error.ErrorUnknownStatus
  }
}
