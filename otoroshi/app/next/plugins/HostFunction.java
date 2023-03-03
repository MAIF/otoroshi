package next.plugins;

import org.extism.sdk.ExtismCurrentPlugin;
import org.extism.sdk.ExtismFunction;
import org.extism.sdk.LibExtism;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Utils {
    public static String rawBytePtrToString(ExtismCurrentPlugin plugin, long offset, int arrSize) {
        int memoryLength = LibExtism.INSTANCE.extism_current_plugin_memory_length(plugin.pointer, arrSize);
        byte[] arr = plugin.memory().share(offset, memoryLength)
                .getByteArray(0, arrSize);
        return new String(arr, StandardCharsets.UTF_8);
    }
}

class Logging {

    enum LogLevel {
        LogLevelTrace,
        LogLevelDebug,
        LogLevelInfo,
        LogLevelWarn,
        LogLevelError,
        LogLevelCritical,
        LogLevelMax
    }

    enum Status {
        StatusOK(0),
        StatusNotFound(1),
        StatusBadArgument(2),
        StatusEmpty(7),
        StatusCasMismatch(8),
        StatusInternalFailure(10),
        StatusUnimplemented(12);

        private int n;

        Status(int n) {
            this.n = n;
        }

        void from(int n) {
            this.n = n;
        }
    }

    public static ExtismFunction proxyLogFunction = (plugin, params, returns, data) -> {
        LogLevel logLevel = LogLevel.values()[params[0].v.i32];
        String messageData  = Utils.rawBytePtrToString(plugin, params[1].v.i64, params[2].v.i32);

        System.out.println(String.format("[%s]: %s", logLevel.toString(), messageData));

        returns[0].v.i32 = Status.StatusOK.n;
    };

    public static org.extism.sdk.HostFunction proxyLog = new org.extism.sdk.HostFunction<>(
            "proxy_log",
            new LibExtism.ExtismValType[]{LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I64,LibExtism.ExtismValType.I32},
            new LibExtism.ExtismValType[]{LibExtism.ExtismValType.I32},
            proxyLogFunction,
            Optional.empty()
    );

    public static  List<org.extism.sdk.HostFunction> functions = Stream.of(proxyLog)
            .collect(Collectors.toList());
}

public class HostFunction {

    public static org.extism.sdk.HostFunction[] functions = Stream.concat(
            Logging.functions.stream(), Stream.empty()

    )
            .collect(Collectors.toList())
            .toArray(new org.extism.sdk.HostFunction[0]);
}
