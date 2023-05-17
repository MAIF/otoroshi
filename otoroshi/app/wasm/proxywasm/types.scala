package otoroshi.wasm.proxywasm

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

  def valueToType(value: Int): Result = value match {
    case 0 => ResultOk
    case 1 => ResultEmpty
    case 2 => ResultNotFound
    case 3 => ResultNotAllowed
    case 4 => ResultBadArgument
    case 5 => ResultInvalidMemoryAccess
    case 6 => ResultInvalidOperation
    case 7 => ResultCompareAndSwapMismatch
    case _ => ResultUnimplemented
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
    case 0 => MapTypeHttpRequestHeaders
    case 1 => MapTypeHttpRequestTrailers
    case 2 => MapTypeHttpRequestMetadata
    case 3 => MapTypeHttpResponseHeaders
    case 4 => MapTypeHttpResponseTrailers
    case 5 => MapTypeHttpResponseMetadata
    case 6 => MapTypeHttpCallResponseHeaders
    case 7 => MapTypeHttpCallResponseTrailers
    case _ => MapTypeHttpCallResponseMetadata
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
    case 0 => BufferTypeHttpRequestBody
    case 1 => BufferTypeHttpResponseBody
    case 2 => BufferTypeDownstreamData
    case 3 => BufferTypeUpstreamData
    case 4 => BufferTypeHttpCallResponseBody
    case 5 => BufferTypeGrpcReceiveBuffer
    case 6 => BufferTypeVmConfiguration
    case 7 => BufferTypePluginConfiguration
    case _ => BufferTypeCallData
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
