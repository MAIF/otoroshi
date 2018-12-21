package foo;

import com.typesafe.config.ConfigFactory;
import models.ServiceGroup;
import play.api.Mode;
import scala.None$;
import scala.Option;
import scala.Unit$;

import java.io.File;
import java.util.Arrays;
import java.io.File;

public class MyApp {

    public static void main(String... args) {
        otoroshi.api.Otoroshi oto = otoroshi.api.Otoroshi.apply(
            play.core.server.ServerConfig.apply(
                EmbedOto.class.getClassLoader(),
                new File("."),
                Option.<Object>apply(8888),
                Option.<Object>apply(null),
                "0.0.0.0",
                Mode.Prod$.MODULE$,
                System.getProperties()
            ),
            ConfigFactory.parseString(String.join("\n", Arrays.asList(
                "app {",
                "  storage = \"leveldb\"",
                "  importFrom = \"./my-state.json\"",
                "  env = \"prod\"",
                "  adminapi {",
                "    targetSubdomain = \"otoroshi-admin-internal-api\"",
                "    exposedSubdomain = \"otoroshi-api\"",
                "    defaultValues {",
                "      backOfficeGroupId = \"admin-api-group\"",
                "      backOfficeApiKeyClientId = \"admin-api-apikey-id\"",
                "      backOfficeApiKeyClientSecret = \"admin-api-apikey-secret\"",
                "      backOfficeServiceId = \"admin-api-service\"",
                "    }",
                "  }",
                "  claim {",
                "    sharedKey = \"mysecret\"",
                "  }",
                "  leveldb {",
                "    path = \"./leveldb\"",
                "  }",
                "}"
            )))
        ).start();

        ServiceGroup group = ServiceGroup.apply("id", "name", "description");

        oto.dataStores().serviceGroupDataStore()
          .set(group, None$.empty(), oto.executionContext(), oto.env())
          .map(resp -> {
            // Do whatever you want
            oto.stop();
            return Unit$.MODULE$;
          }, oto.executionContext());
    }
}
