package otoroshi.wasm.proxywasm;

public class Types {

    public enum Result {
        ResultOk                     (0),
        ResultEmpty                  (1),
        ResultNotFound               (2),
        ResultNotAllowed             (3),
        ResultBadArgument            (4),
        ResultInvalidMemoryAccess    (5),
        ResultInvalidOperation       (6),
        ResultCompareAndSwapMismatch (7),
        ResultUnimplemented          (12);

        private int n;
        Result(int n) { this.n = n; }
    }

    public enum Action {
        ActionContinue         (1),
        ActionEndStream        (2),
        ActionDone             (3),
        ActionPause            (4),
        ActionWaitForMoreData  (5),
        ActionWaitForEndOrFull (6),
        ActionClose            (7);

        private int n;
        Action(int n) { this.n = n; }
    }

    public enum MetricType {
        MetricTypeCounter(1),
        MetricTypeGauge(2),
        MetricTypeHistogram(3);

        private int n;
        MetricType(int n) { this.n = n; }
    }

    public enum MapType {
        MapTypeHttpRequestHeaders       (0),
        MapTypeHttpRequestTrailers      (1),
        MapTypeHttpRequestMetadata      (2),
        MapTypeHttpResponseHeaders      (3),
        MapTypeHttpResponseTrailers     (4),
        MapTypeHttpResponseMetadata     (5),
        MapTypeHttpCallResponseHeaders  (6),
        MapTypeHttpCallResponseTrailers (7),
        MapTypeHttpCallResponseMetadata (8);

        private int n;
        MapType(int n) { this.n = n; }
    }

    public enum BufferType {
        //        BufferTypeVmConfiguration(0),
//        BufferTypePluginConfiguration(1),
//        BufferTypeDownstreamData(2),
//        BufferTypeUpstreamData(3),
//        BufferTypeHttpCallResponseBody(4),
//        BufferTypeHttpRequestBody(5),
//        BufferTypeHttpResponseBody(6),
//        BufferTypeHttpCalloutResponseBody(7);
        BufferTypeHttpRequestBody      (0),
        BufferTypeHttpResponseBody     (1),
        BufferTypeDownstreamData       (2),
        BufferTypeUpstreamData         (3),
        BufferTypeHttpCallResponseBody (4),
        BufferTypeGrpcReceiveBuffer    (5),
        BufferTypeVmConfiguration      (6),
        BufferTypePluginConfiguration  (7),
        BufferTypeCallData             (8);

        private int n;

        BufferType(int n) {
            this.n = n;
        }
    }

    public enum StreamType {
        StreamTypeDownstream (1),
        StreamTypeUpstream ( 2),
        StreamTypeHttpRequest ( 3),
        StreamTypeHttpResponse ( 4);

        private int n;
        StreamType(int n) { this.n = n; }
    }

    public enum ContextType {
        ContextTypeVmContext (1),
        ContextTypePluginContext (2),
        ContextTypeStreamContext (3),
        ContextTypeHttpContext (4);

        private int n;
        ContextType(int n) { this.n = n; }
    }

    public enum LogLevel {
        LogLevelTrace (1),
        LogLevelDebug (2),
        LogLevelWarning (3),
        LogLevelInfo (4),
        LogLevelWarn (5),
        LogLevelError (6),
        LogLevelCritical (7);

        private int n;
        LogLevel(int n) { this.n = n; }
    }

    public enum CloseSourceType {
        CloseSourceTypeLocal (1),
        CloseSourceTypeRemote (2);

        private int n;
        CloseSourceType(int n) { this.n = n; }
    }

    public enum Error {
        // ErrorStatusNotFound means not found for various hostcalls.
        ErrorStatusNotFound("error status returned by host: not found"),
        // ErrorStatusBadArgument means the arguments for a hostcall are invalid.
        ErrorStatusBadArgument("error status returned by host: bad argument"),
        // ErrorStatusEmpty means the target queue of DequeueSharedQueue call is empty.
        ErrorStatusEmpty("error status returned by host: empty"),
        // ErrorStatusCasMismatch means the CAS value provided to the SetSharedData
        // does not match the current value. It indicates that other Wasm VMs
        // have already set a value for the same key, and the current CAS
        // for the key gets incremented.
        // Having retry logic in the face of this error is recommended.
        ErrorStatusCasMismatch("error status returned by host: cas mismatch"),
        // ErrorInternalFailure indicates an internal failure in hosts.
        // When this error occurs, there's nothing we could do in the Wasm VM.
        // Abort or panic after this error is recommended.
        ErrorInternalFailure("error status returned by host: internal failure"),
        // ErrorUnimplemented indicates the API is not implemented in the host yet.
        ErrorUnimplemented("error status returned by host: unimplemented"),
        ErrorUnknownStatus("unknown status code"),
        ErrorExportsNotFound("exports not found"),
        ErrAddrOverflow("addr overflow");

        private String n;
        Error(String n) {
            this.n = n;
        }

        public static Result toResult(Error error) {
            switch (error) {
                case ErrorStatusNotFound:
                case ErrorUnknownStatus:
                case ErrorExportsNotFound:
                    return Result.ResultNotFound;
                case ErrorStatusBadArgument:
                    return Result.ResultBadArgument;
                case ErrorStatusEmpty:
                    return Result.ResultEmpty;
                case ErrorStatusCasMismatch:
                    return Result.ResultCompareAndSwapMismatch;
                case ErrorInternalFailure:
                case ErrAddrOverflow:
                    return Result.ResultInvalidOperation;
                case ErrorUnimplemented:
                    return Result.ResultUnimplemented;
                default:
                    return Result.ResultInvalidOperation;
            }
        }
    }

    public enum Status {
        StatusOK              (0),
        StatusNotFound        (1),
        StatusBadArgument     (2),
        StatusEmpty           (7),
        StatusCasMismatch     (8),
        StatusInternalFailure (10),
        StatusUnimplemented   (12);

        private int n;
        Status(int n) { this.n = n; }
    }

    public static Error StatusToError(Status status) {
        switch (status) {
            case StatusOK:
                return null;
            case StatusNotFound:
                return Error.ErrorStatusNotFound;
            case StatusBadArgument:
                return Error.ErrorStatusBadArgument;
            case StatusEmpty:
                return Error.ErrorStatusEmpty;
            case StatusCasMismatch:
                return Error.ErrorStatusCasMismatch;
            case StatusInternalFailure:
                return Error.ErrorInternalFailure;
            case StatusUnimplemented:
                return Error.ErrorUnimplemented;
        }
        return Types.Error.ErrorUnknownStatus;
    }

}
