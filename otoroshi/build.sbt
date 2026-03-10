import xerial.sbt.Sonatype.*

name := """otoroshi"""
organization := "fr.maif"
version := "17.14.0-dev"
scalaVersion := scalaLangVersion

ThisBuild / evictionErrorLevel := Level.Warn

inThisBuild(
    List(
        description := "Lightweight api management on top of a modern http reverse proxy",
        startYear := Some(2017),
        organization := "fr.maif",
        homepage := Some(url("https://github.com/MAIF/otoroshi")),
        licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
        sonatypeProfileName := "fr.maif",
        scmInfo := Some(
            ScmInfo(
                url("https://github.com/MAIF/otoroshi"),
                "scm:git@github.com:MAIF/otoroshi.git"
            )
        ),
        publishMavenStyle := true,
        developers := List(
            Developer(
                "mathieuancelin",
                "Mathieu Ancelin",
                "mathieu.ancelin@serli.com",
                url("https://github.com/mathieuancelin")
            ),
            Developer(
                "quentinovega",
                "Quentin Aubert",
                "",
                url("https://github.com/quentinovega")
            ),
            Developer(
                "Zwiterrion",
                "Etienne Anne",
                "",
                url("https://github.com/Zwiterrion")
            ),
            Developer(
                "baudelotphilippe",
                "Philippe Baudelot",
                "",
                url("https://github.com/baudelotphilippe")
            )
        )
    )
)

enablePlugins(PlayScala)
disablePlugins(PlayFilters)

lazy val scalaLangVersion   = "3.7.4"
val playVersion             = "3.0.10"
val metricsVersion          = "4.2.37"
val acme4jVersion           = "3.5.1"
val prometheusVersion       = "0.16.0"
val playJsonVersion         = "3.0.6"
val webAuthnVersion         = "2.7.0"
val kubernetesVersion       = "25.0.0"
val bouncyCastleVersion     = "1.83"
val bouncyCastleExtVersion  = "1.78.1"
val pulsarVersion           = "2.12.0.1"
val openTelemetryVersion    = "1.58.0"
val jacksonVersion          = "2.19.2"
val pekkoVersion            = "1.3.0"
val pekkoHttpVersion        = "1.3.0"
val pekkoConnectorsVersion  = "1.2.0"
val reactorNettyVersion     = "1.3.0"
val circeVersion            = "0.14.14"
val nettyVersion            = "4.2.7.Final"
val okhttpVersion           = "5.3.2"
val okioVersion             = "3.16.4"
val scramVersion            = "3.2"
val excludesJackson         = Seq(
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)
val excludeScalaJava8Compat = Seq(
    ExclusionRule(organization = "org.scala-lang.modules")
)
val excludeSlf4jAndJackson  = excludesJackson ++ Seq(
    ExclusionRule(organization = "org.slf4j")
)

scalacOptions ++= Seq(
    "-experimental",
    "-explain",
    "-feature",
    "-explain-cyclic",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:existentials",
    "-language:postfixOps",
)

// FIX: Comprehensive dependency overrides to enforce consistent versions
dependencyOverrides ++= Seq(
    // Pekko overrides
    "org.apache.pekko" %% "pekko-actor"                 % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream"                % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j"                 % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-typed"           % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http"                  % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-core"             % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-xml"              % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-parsing"               % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json"       % pekkoHttpVersion,

    // Scala modules
    "org.scala-lang.modules" %% "scala-xml" % "2.4.0",

    // Netty overrides - ALL at the same version
    "io.netty" % "netty-common" % nettyVersion,
    "io.netty" % "netty-buffer" % nettyVersion,
    "io.netty" % "netty-codec" % nettyVersion,
    "io.netty" % "netty-codec-base" % nettyVersion,
    "io.netty" % "netty-codec-compression" % nettyVersion,
    "io.netty" % "netty-codec-dns" % nettyVersion,
    "io.netty" % "netty-codec-http" % nettyVersion,
    "io.netty" % "netty-codec-http2" % nettyVersion,
    "io.netty" % "netty-codec-socks" % nettyVersion,
    "io.netty" % "netty-handler" % nettyVersion,
    "io.netty" % "netty-handler-proxy" % nettyVersion,
    "io.netty" % "netty-resolver" % nettyVersion,
    "io.netty" % "netty-resolver-dns" % nettyVersion,
    "io.netty" % "netty-transport" % nettyVersion,
    "io.netty" % "netty-transport-native-epoll" % nettyVersion,
    "io.netty" % "netty-transport-native-kqueue" % nettyVersion,
    "io.netty" % "netty-transport-native-unix-common" % nettyVersion,

    // Jackson overrides
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,

    // OkHttp overrides - force single version
    "com.squareup.okhttp3" % "okhttp" % okhttpVersion,
    "com.squareup.okhttp3" % "okhttp-jvm" % okhttpVersion,
    "com.squareup.okhttp3" % "okhttp-urlconnection" % okhttpVersion,

    // Okio (dependency of OkHttp) - also needs to be consistent
    "com.squareup.okio" % "okio" % okioVersion,
    "com.squareup.okio" % "okio-jvm" % okioVersion,

    // SCRAM library - force version 3.1
    "com.ongres.scram" % "scram-common" % scramVersion,
    "com.ongres.scram" % "scram-client" % scramVersion,

    // Other common conflicts
    "org.slf4j" % "slf4j-api" % "2.0.17",
    "com.google.guava" % "guava" % "33.5.0-jre"
)

libraryDependencies ++= Seq(
    ws,
    filters,
    "com.softwaremill.macwire"        %% "macros"                                    % "2.6.7" % "provided",
    "org.scala-lang"                  %% "scala3-tasty-inspector"                    % scalaLangVersion,
    "org.playframework"               %% "play-json"                                 % playJsonVersion,
    "org.playframework"               %% "play-json-joda"                            % playJsonVersion,
    "joda-time"                        % "joda-time"                                 % "2.14.0",
    "io.github.rediscala"             %% "rediscala"                                 % "1.17.0",
    ("com.github.gphat"               %% "censorinus"                                % "2.1.16").cross(CrossVersion.for3Use2_13),
    "org.apache.pekko"                %% "pekko-connectors-kafka"                    % "1.1.0",
    "org.apache.pekko"                %% "pekko-connectors-s3"                       % "1.2.0",
    "org.apache.pekko"                %% "pekko-actor"                               % pekkoVersion,
    "org.apache.pekko"                %% "pekko-stream"                              % pekkoVersion,
    "org.apache.pekko"                %% "pekko-slf4j"                               % pekkoVersion,
    "org.apache.pekko"                %% "pekko-actor-typed"                         % pekkoVersion,
    "org.apache.pekko"                %% "pekko-serialization-jackson"               % pekkoVersion,
    "org.apache.pekko"                %% "pekko-http"                                % pekkoHttpVersion,
    "org.apache.pekko"                %% "pekko-http-xml"                            % pekkoHttpVersion,
    "com.spotify.metrics"              % "semantic-metrics-core"                     % "1.2.0",
    "io.dropwizard.metrics"            % "metrics-jmx"                               % metricsVersion excludeAll (excludesJackson *),
    "io.dropwizard.metrics"            % "metrics-json"                              % metricsVersion excludeAll (excludesJackson *),
    "io.prometheus"                    % "simpleclient_common"                       % prometheusVersion excludeAll (excludesJackson *),
    "io.prometheus"                    % "simpleclient_dropwizard"                   % prometheusVersion excludeAll (excludesJackson *),
    "com.auth0"                        % "java-jwt"                                  % "4.5.0" excludeAll (excludesJackson *),
    "com.auth0"                        % "jwks-rsa"                                  % "0.23.0" excludeAll (excludesJackson *),
    "com.nimbusds"                     % "nimbus-jose-jwt"                           % "10.6",
    "de.svenkubiak"                    % "jBCrypt"                                   % "0.4.3",
    "dev.soundness"                    % "kaleidoscope-core"                         % "0.53.0",
    "io.github.classgraph"             % "classgraph"                                % "4.8.184" excludeAll (excludesJackson *),
    "com.comcast"                     %% "ip4s-core"                                 % "3.7.0",
    "com.yubico"                       % "webauthn-server-core"                      % webAuthnVersion excludeAll (excludesJackson *),
    "com.yubico"                       % "webauthn-server-attestation"               % webAuthnVersion excludeAll (excludesJackson *),
    "com.yubico"                       % "yubico-util"                               % webAuthnVersion excludeAll (excludesJackson *),
    "com.maxmind.geoip2"               % "geoip2"                                    % "4.3.1",
    "com.blueconic"                    % "browscap-java"                             % "1.5.1",
    "javax.xml.bind"                   % "jaxb-api"                                  % "2.3.1",
    "com.sun.xml.bind"                 % "jaxb-core"                                 % "4.0.6",
    "com.github.blemale"              %% "scaffeine"                                 % "5.3.0",
    "org.shredzone.acme4j"             % "acme4j-client"                             % acme4jVersion excludeAll (excludeSlf4jAndJackson *),
    "io.lettuce"                       % "lettuce-core"                              % "7.1.0.RELEASE" excludeAll (excludesJackson *),
    "io.vertx"                         % "vertx-pg-client"                           % "4.3.4",
    "com.ongres.scram"                 % "scram-common"                              % scramVersion,
    "com.ongres.scram"                 % "scram-client"                              % scramVersion,
    "com.jayway.jsonpath"              % "json-path"                                 % "2.10.0",
    "com.cronutils"                    % "cron-utils"                                % "9.2.1",
    "com.datastax.oss"                 % "java-driver-core"                          % "4.17.0" excludeAll (excludesJackson *),
    "org.gnieh"                       %% "diffson-play-json"                         % "4.6.1" excludeAll ExclusionRule(organization = "org.apache.pekko"),
    "io.kubernetes"                    % "client-java"                               % kubernetesVersion excludeAll (excludesJackson *),
    "io.kubernetes"                    % "client-java-extended"                      % kubernetesVersion excludeAll (excludesJackson *),
    "org.bouncycastle"                 % "bcpkix-jdk18on"                            % bouncyCastleVersion excludeAll (excludesJackson *),
    "org.bouncycastle"                 % "bcprov-ext-jdk18on"                        % bouncyCastleExtVersion excludeAll (excludesJackson *),
    "org.bouncycastle"                 % "bcprov-jdk18on"                            % bouncyCastleVersion excludeAll (excludesJackson *),
    "org.apache.pulsar"                % "pulsar-client"                             % "4.1.2" excludeAll (excludesJackson *),
    "org.jsoup"                        % "jsoup"                                     % "1.22.1",
    "org.biscuitsec"                   % "biscuit"                                   % "4.0.1",
    "org.opensaml"                     % "opensaml-core"                             % "4.0.1",
    "org.opensaml"                     % "opensaml-saml-api"                         % "4.0.1",
    "org.opensaml"                     % "opensaml-saml-impl"                        % "4.0.1",
    "org.openjdk.jol"                  % "jol-core"                                  % "0.17",
    "org.typelevel"                   %% "squants"                                   % "1.8.3" excludeAll (excludesJackson *),
    // Jackson libraries
    "com.fasterxml.jackson.core"       % "jackson-core"                              % jacksonVersion,
    "com.fasterxml.jackson.core"       % "jackson-annotations"                       % jacksonVersion,
    "com.fasterxml.jackson.core"       % "jackson-databind"                          % jacksonVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"                   % jacksonVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"                   % jacksonVersion,
    "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310"                   % jacksonVersion,
    "com.fasterxml.jackson.module"    %% "jackson-module-scala"                      % jacksonVersion,
    "org.yaml"                         % "snakeyaml"                                 % "2.4" excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-api"                         % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-bom"                         % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-context"                     % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-sdk"                         % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-sdk-common"                  % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-sdk-logs"                    % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-sdk-metrics"                 % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-sdk-trace"                   % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-exporter-logging"            % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-exporter-otlp"               % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-exporter-zipkin"             % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry"                 % "opentelemetry-exporter-sender-okhttp"      % openTelemetryVersion excludeAll (excludesJackson *),
    "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"        % "2.22.0-alpha" excludeAll (excludesJackson *),
    "com.amazonaws"                    % "aws-java-sdk-secretsmanager"               % "1.12.793" excludeAll (excludesJackson *),
    "org.apache.logging.log4j"         % "log4j-api"                                 % "2.25.3",
    "org.sangria-graphql"             %% "sangria"                                   % "4.2.15",
    "org.bigtesting"                   % "routd"                                     % "1.0.7",
    "com.nixxcode.jvmbrotli"           % "jvmbrotli"                                 % "0.2.0",
    "io.azam.ulidj"                    % "ulidj"                                     % "2.0.0",
    "fr.maif"                         %% "wasm4s"                                    % "5.0.3" classifier "bundle",
    "com.google.crypto.tink"           % "tink"                                      % "1.20.0",
    "org.json4s"                      %% "json4s-jackson"                            % "4.0.7",
    "org.json4s"                      %% "json4s-ast"                                % "4.0.7",
    "org.json4s"                      %% "json4s-ext"                                % "4.0.7",
    // New HTTP stack
    "io.projectreactor.netty"          % "reactor-netty-core"                        % reactorNettyVersion,
    "io.projectreactor.netty"          % "reactor-netty-http"                        % reactorNettyVersion,
    "io.netty"                         % "netty-transport-native-kqueue"             % nettyVersion,
    "io.netty"                         % "netty-transport-native-kqueue"             % nettyVersion classifier "osx-aarch_64" classifier "osx-x86_64",
    "io.netty"                         % "netty-transport-native-epoll"              % nettyVersion,
    "io.netty"                         % "netty-transport-native-epoll"              % nettyVersion classifier "linux-x86_64" classifier "linux-aarch_64",
    "io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.26.Final",
    "io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.26.Final" classifier "linux-x86_64" classifier "linux-aarch_64",
    "io.netty.incubator"               % "netty-incubator-codec-native-quic"         % "0.0.73.Final",
    "io.netty.incubator"               % "netty-incubator-codec-native-quic"         % "0.0.73.Final" classifier "linux-x86_64" classifier "osx-x86_64",
    "io.netty.incubator"               % "netty-incubator-codec-http3"               % "0.0.30.Final",
    // Tests
    "org.scalatestplus.play"          %% "scalatestplus-play"                        % "7.0.2" % Test,
    "com.networknt"                    % "json-schema-validator"                     % "1.5.8" excludeAll (excludeSlf4jAndJackson *),
    "jakarta.jms"                      % "jakarta.jms-api"                           % "3.1.0",
    "org.apache.activemq"              % "artemis-jakarta-client"                    % "2.44.0" excludeAll (excludeSlf4jAndJackson *),
  "com.dimafeng"                    %% "testcontainers-scala-scalatest"            % "0.44.1" % Test,
  "com.microsoft.playwright"         % "playwright"                                % "1.57.0" % Test
)

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

Test / parallelExecution := false
IntegrationTest / testForkedParallel := false
IntegrationTest / fork := true

Test / javaOptions ++= Seq(
  "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
  "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED"
)

usePgpKeyHex("4EFDC6FC2DEC936B13B7478C2F8C0F4E1D397E7F")
sonatypeProjectHosting := Some(GitHubHosting("MAIF", "otoroshi", "mathieu.ancelin@serli.com"))
sonatypeRepository := "https://ossrh-staging-api.central.sonatype.com/service/local/"
sonatypeCredentialHost := sonatypeCentralHost
licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

// assembly
assembly /mainClass := Some("play.core.server.ProdServerStart")
assembly / test := {}
assembly /assemblyJarName := "otoroshi.jar"
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)

// FIX: Complete merge strategy handling all conflicts
assembly / assemblyMergeStrategy := {
    // Handle class conflicts from error log
    case PathList("io", "netty", xs @ _*) => MergeStrategy.first
    case PathList("com", "ongres", "scram", xs @ _*) => MergeStrategy.first

    // Handle META-INF conflicts
    case "META-INF/FastDoubleParser-NOTICE" => MergeStrategy.first
    case "META-INF/mimetypes.default" => MergeStrategy.first
    case "META-INF/mailcap.default" => MergeStrategy.first
    case "META-INF/kotlin-project-structure-metadata.json" => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "OSGI-INF", "MANIFEST.MF") => MergeStrategy.first
    case PathList("META-INF", "native-image", xs @ _*) => MergeStrategy.first
    case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat

    // Discard module-info.class as it's not needed and causes conflicts
    case "module-info.class" => MergeStrategy.discard
    case PathList(ps @ _*) if ps.contains("module-info.class") => MergeStrategy.discard

    // Your existing successful strategies
    case path if path.contains("com/upokecenter/util") => MergeStrategy.first
    case path if path.contains("org/slf4j/impl") => MergeStrategy.first
    case path if path.contains("edu/umd/cs/findbugs/annotations") => MergeStrategy.first
    case PathList("scala", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "commons", "logging", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "commons", "lang", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "commons", "collections", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "maven", "surefire", "shade", "org", "apache", "maven", "shared", "utils", "StringUtils.class") => MergeStrategy.first
    case PathList("io", "sundr", xs @ _*) => MergeStrategy.first
    case PathList("com", "sun", "xml", xs @ _*) => MergeStrategy.first
    case PathList("com", "sun", "istack", xs @ _*) => MergeStrategy.first
    case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("reference-overrides.conf") => MergeStrategy.concat
    case PathList(ps @ _*) if ps.contains("field_mask.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("plugin.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("descriptor.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("ModuleUtil.class") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("GuardedBy.class") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("nowarn$.class") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("nowarn.class") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("reflection-config.json") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("metadata.json") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("version.conf") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("any.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("api.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("duration.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("empty.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("struct.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("type.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("timestamp.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("wrappers.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("source_context.proto") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("native-image.properties") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("library.properties") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("public-suffix-list.txt") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("jna") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("findbugsExclude.xml") => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("okio.kotlin_module") => MergeStrategy.first
    case path if path.contains("pekko/stream") => MergeStrategy.first
    case path if path.contains("org/bouncycastle") => MergeStrategy.first
    case PathList("javax", xs @ _*) => MergeStrategy.first

    // Fallback to default strategy
    case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
    (Compile / dist).value
    (Compile / assembly).value
}

import play.sbt.PlayImport.PlayKeys.*

publish / packagedArtifacts := {
    val artifacts: Map[sbt.Artifact, java.io.File] = (publishLocal / packagedArtifacts).value
    val assets: java.io.File                       = (Compile / playPackageAssets).value
    artifacts + (Artifact(moduleName.value, "jar", "jar", "assets") -> assets)
}

resolvers += Resolver.mavenLocal

bashScriptExtraDefines += """
addJava "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED"
addJava "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
addJava "--add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED"
addJava "--add-exports=java.base/sun.security.x509=ALL-UNNAMED"
addJava "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED"
addJava "-Dlog4j2.formatMsgNoLookups=true"
"""

Revolver.enableDebugging(port = Integer.parseInt(sys.props.getOrElse("otoroshi.sbt.port", "5005")), suspend = false)

reStart / mainClass := Some("play.core.server.ProdServerStart")
reStart / javaOptions ++= Seq(
  "-Xms2g",
  "-Xmx8g",
  "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
  "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
  "-Dlog4j2.formatMsgNoLookups=true",
  //"-Dapp.rootScheme=https",
  "-Dotoroshi.revolver=true",
  "-Dotoroshi.env=dev",
  "-Dotoroshi.http.port=9999",
  "-Dotoroshi.https.port=9998",
  "-Dotoroshi.liveJs=false",
  "-Dotoroshi.adminPassword=password",
  "-Dotoroshi.domain=oto.tools",
  "-Dotoroshi.events.maxSize=0",
  "-Dotoroshi.cluster.mode=Leader",
  "-Dotoroshi.cluster.leader.name=otoroshi-leader-dev",
  "-Dotoroshi.tunnels.enabled=false",
  "-Dotoroshi.tunnels.default.enabled=false",
  "-Dotoroshi.tunnels.default.url=http://127.0.0.1:9999",
  "-Dotoroshi.instance.name=dev",
  "-Dotoroshi.vaults.enabled=true",
  "-Dotoroshi.privateapps.session.enabled=true",
  //"-Dotoroshi.loggers.otoroshi-papps-session-manager=DEBUG",
  //"-Dotoroshi.privateapps.subdomain=otoroshi",
  "-Dotoroshi.ssl.fromOutside.clientAuth=Dynamic",
  //"-Dotoroshi.ssl.fromOutside.clientAuth=Need",
  "-Dotoroshi.inmemory.modern=true",
  "-Dotoroshi.wasm.cache.ttl=2000",
  "-Dotoroshi.next.experimental.netty-server.enabled=true",
  "-Dotoroshi.next.experimental.netty-server.accesslog=true",
  "-Dotoroshi.next.experimental.netty-server.wiretap=false",
  "-Dotoroshi.next.experimental.netty-server.http3.enabled=true",
  //"-Dotoroshi.loggers.otoroshi-wasm-debug=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-wasm-vm-pool=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-wasm-integration=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-proxy-wasm=TRACE",
  //"-Dotoroshi.loggers.otoroshi-experimental-netty-http3-client=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-experimental-netty-http3-server=DEBUG",
//  "-Dotoroshi.loggers.otoroshi-plugins-kubernetes-crds-sync=OFF",
//  "-Dotoroshi.loggers.otoroshi-plugins-kubernetes-cert-sync=OFF",
//  "-Dotoroshi.loggers.otoroshi-plugins-kubernetes-crds-controller-job=OFF",
  "-Dotoroshi.options.enable-json-media-type-with-open-charset=true", // now using proper Pekko ParserSettings API instead of reflection
  "-Dotoroshi.next.state-sync-interval=1000",
  // "-Dotoroshi.next.experimental.netty-server.native.driver=IOUring",
  "-DVAULT_VALUE=admin-api-apikey-secret",
  "-Dapp.redis.lettuce.pooling.enabled=true",
  "-Dotoroshi.storage=file",
  //"-Dotoroshi.storage=ext:foo",
  //"-Dotoroshi.storage=lettuce",
  //"-Dotoroshi.storage=inmemory",
  //"-Dotoroshi.storage=pg",
  //"-Dotoroshi.storage=redis",
  "-Dotoroshi.redis.lettuce.uri=redis://localhost:5432/"
)