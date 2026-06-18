import xerial.sbt.Sonatype._

name := """otoroshi"""
organization := "fr.maif"
version := "18.0.0-dev"
scalaVersion := scalaLangVersion

ThisBuild / evictionErrorLevel := Level.Warn

inThisBuild(
  List(
    description := "Lightweight api management on top of a modern http reverse proxy",
    startYear := Some(2017),
    organization := "fr.maif",
    homepage := Some(url("https://github.com/MAIF/otoroshi")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
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

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)

lazy val scalaLangVersion        = "3.8.3"
lazy val metricsVersion          = "4.2.12"
lazy val acme4jVersion           = "3.2.1" // "2.14"
lazy val prometheusVersion       = "0.16.0"
lazy val playJsonVersion         = "3.0.6"
lazy val webAuthnVersion         = "2.1.0" //"1.7.0" //"2.1.0"
lazy val kubernetesVersion       = "16.0.1"
lazy val bouncyCastleVersion     = "1.77"
lazy val pulsarVersion           = "2.10.0"
lazy val openTelemetryVersion    = "1.28.0"
lazy val jacksonVersion          = "2.21.3"
lazy val jacksonAnnotationVersion = "2.21" // jackson-annotations is versioned at the minor level only

lazy val pekkoVersion            = "1.6.0"
lazy val pekkoHttpVersion        = "1.3.0"
lazy val pekkoConnectorsS3Version = "1.3.0"
lazy val pekkoConnectorsKafkaVersion = "1.1.0"
lazy val reactorNettyVersion     = "1.1.18"
lazy val nettyVersion            = "4.1.119.Final"
lazy val excludesJackson         = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)
lazy val excludeScalaJava8Compat = Seq(
  ExclusionRule(organization = "org.scala-lang.modules")
)
lazy val excludeSlf4jAndJackson  = excludesJackson ++ Seq(
  ExclusionRule(organization = "org.slf4j")
)

// Pekko 1.6 upstream is used directly (no more patched akka-stream from the lib directory):
// the TLS 1.3 handshake session fix has been part of Pekko since 1.1.0.

// Pin every Pekko artifact to the same version to avoid mixed-version conflicts.
dependencyOverrides ++= Seq(
  "org.apache.pekko" %% "pekko-actor"                 % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"                % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"                 % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-typed"           % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
  "org.apache.pekko" %% "pekko-protobuf-v3"           % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"                  % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-core"             % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-xml"              % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-parsing"               % pekkoHttpVersion,
  "org.scala-lang.modules" %% "scala-xml"             % "2.3.0"
)

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.softwaremill.macwire"        %% "macros"                               % "2.6.7"  % "provided",
  "org.playframework"               %% "play-json"                            % playJsonVersion,
  "org.playframework"               %% "play-json-joda"                       % playJsonVersion,
  "io.github.rediscala"             %% "rediscala"                            % "2.0.2",
  ("com.github.gphat"               %% "censorinus"                           % "2.1.16").cross(CrossVersion.for3Use2_13),
  "org.apache.pekko"                %% "pekko-actor"                          % pekkoVersion,
  "org.apache.pekko"                %% "pekko-stream"                         % pekkoVersion,
  "org.apache.pekko"                %% "pekko-slf4j"                          % pekkoVersion,
  "org.apache.pekko"                %% "pekko-actor-typed"                    % pekkoVersion,
  "org.apache.pekko"                %% "pekko-serialization-jackson"          % pekkoVersion,
  "org.apache.pekko"                %% "pekko-http"                           % pekkoHttpVersion,
  "org.apache.pekko"                %% "pekko-http-xml"                       % pekkoHttpVersion,
  "org.apache.pekko"                %% "pekko-connectors-kafka"               % pekkoConnectorsKafkaVersion,
  "org.apache.pekko"                %% "pekko-connectors-s3"                  % pekkoConnectorsS3Version,
  "com.spotify.metrics"              % "semantic-metrics-core"                % "1.1.11",
  "io.dropwizard.metrics"            % "metrics-jmx"                          % metricsVersion excludeAll (excludesJackson: _*), // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-json"                         % metricsVersion excludeAll (excludesJackson: _*), // Apache 2.0
  "io.prometheus"                    % "simpleclient_common"                  % prometheusVersion excludeAll (excludesJackson: _*), // Apache 2.0
  "io.prometheus"                    % "simpleclient_dropwizard"              % prometheusVersion excludeAll (excludesJackson: _*), // Apache 2.0
  "com.auth0"                        % "java-jwt"                             % "4.2.0" excludeAll (excludesJackson: _*),
  "com.auth0"                        % "jwks-rsa"                             % "0.21.2" excludeAll (excludesJackson: _*), // https://github.com/auth0/jwks-rsa-java
  "com.nimbusds"                     % "nimbus-jose-jwt"                      % "9.39.1",
  "de.svenkubiak"                    % "jBCrypt"                              % "0.4.3",
  "io.github.classgraph"             % "classgraph"                           % "4.8.149" excludeAll (excludesJackson: _*),
  "com.comcast"                     %% "ip4s-core"                            % "3.2.0",
  "com.yubico"                       % "webauthn-server-core"                 % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.yubico"                       % "webauthn-server-attestation"          % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.yubico"                       % "yubico-util"                          % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.maxmind.geoip2"               % "geoip2"                               % "3.0.1",
  "com.blueconic"                    % "browscap-java"                        % "1.4.3",
  "javax.xml.bind"                   % "jaxb-api"                             % "2.3.1", // https://stackoverflow.com/questions/48204141/replacements-for-deprecated-jpms-modules-with-java-ee-apis/48204154#48204154
  "com.sun.xml.bind"                 % "jaxb-core"                            % "2.3.0.1",
  "com.github.blemale"              %% "scaffeine"                            % "5.3.0",
  "org.shredzone.acme4j"             % "acme4j-client"                        % acme4jVersion excludeAll (excludeSlf4jAndJackson: _*),
  "io.lettuce"                       % "lettuce-core"                         % "6.8.1.RELEASE" excludeAll (excludesJackson: _*),
  "io.vertx"                         % "vertx-pg-client"                      % "4.5.22",
  "com.ongres.scram"                 % "common"                               % "2.1",
  "com.ongres.scram"                 % "client"                               % "2.1",
  "com.jayway.jsonpath"              % "json-path"                            % "2.7.0",
  "com.cronutils"                    % "cron-utils"                           % "9.2.0",
  "commons-lang"                     % "commons-lang"                         % "2.6",
  "com.datastax.oss"                 % "java-driver-core"                     % "4.15.0" excludeAll (excludesJackson: _*),
  "org.gnieh"                       %% "diffson-play-json"                    % "4.7.0" excludeAll ExclusionRule(organization = "org.apache.pekko"),
  "io.kubernetes"                    % "client-java"                          % kubernetesVersion excludeAll (excludesJackson: _*),
  "io.kubernetes"                    % "client-java-extended"                 % kubernetesVersion excludeAll (excludesJackson: _*),
  "org.bouncycastle"                 % "bcpkix-jdk18on"                       % bouncyCastleVersion excludeAll (excludesJackson: _*),
  "org.bouncycastle"                 % "bcprov-ext-jdk18on"                   % bouncyCastleVersion excludeAll (excludesJackson: _*),
  "org.bouncycastle"                 % "bcprov-jdk18on"                       % bouncyCastleVersion excludeAll (excludesJackson: _*),
  "com.clever-cloud.pulsar4s"       %% "pulsar4s-play-json"                   % pulsarVersion excludeAll (excludesJackson: _*),
  "com.clever-cloud.pulsar4s"       %% "pulsar4s-core"                        % pulsarVersion excludeAll (excludesJackson: _*),
  "com.clever-cloud.pulsar4s"       %% "pulsar4s-pekko-streams"               % pulsarVersion excludeAll (excludesJackson: _*),
  "org.jsoup"                        % "jsoup"                                % "1.15.3",
  "org.biscuitsec"                   % "biscuit"                              % "4.0.0",
  "org.opensaml"                     % "opensaml-core"                        % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-api"                    % "4.0.1",
  //"org.opensaml"                     % "opensaml-xmlsec-impl"        % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-impl"                   % "4.0.1",
  "org.openjdk.jol"                  % "jol-core"                             % "0.16",
  "org.typelevel"                   %% "squants"                              % "1.8.3" excludeAll (excludesJackson: _*),
  // fix multiple CVEs
  "com.fasterxml.jackson.core"       % "jackson-core"                         % jacksonVersion,
  "com.fasterxml.jackson.core"       % "jackson-annotations"                  % jacksonAnnotationVersion,
  "com.fasterxml.jackson.core"       % "jackson-databind"                     % jacksonVersion,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"              % jacksonVersion,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"              % jacksonVersion,
  "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"                % jacksonVersion,
  "com.fasterxml.jackson.module"    %% "jackson-module-scala"                 % jacksonVersion,
  "org.yaml"                         % "snakeyaml"                            % "1.33" excludeAll (excludesJackson: _*),
  // "com.arakelian"                    % "java-jq"                                   % "1.3.0" excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-api"                    % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-bom"                    % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-context"                % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-sdk"                    % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-sdk-common"             % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-sdk-logs"               % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-sdk-metrics"            % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-sdk-trace"              % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-exporter-logging"       % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-exporter-otlp"          % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-exporter-zipkin"        % openTelemetryVersion excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-exporter-sender-okhttp" % openTelemetryVersion excludeAll (excludesJackson: _*),
  // "io.opentelemetry"                 % "opentelemetry-exporter-prometheus"         % "1.28.0-alpha" excludeAll (excludesJackson: _*),
  "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"   % "1.28.0-alpha" excludeAll (excludesJackson: _*),
  "com.amazonaws"                    % "aws-java-sdk-secretsmanager"          % "1.12.326" excludeAll (excludesJackson: _*),
  "org.apache.logging.log4j"         % "log4j-api"                            % "2.19.0",
  "org.sangria-graphql"             %% "sangria"                              % "4.2.18",
  "org.bigtesting"                   % "routd"                                % "1.0.7",
  "com.nixxcode.jvmbrotli"           % "jvmbrotli"                            % "0.2.0",
  "io.azam.ulidj"                    % "ulidj"                                % "1.0.4",
  "fr.maif"                         %% "wasm4s"                               % "5.0.3" classifier "bundle",
  "com.google.crypto.tink"           % "tink"                                 % "1.16.0",
  "com.google.auth"                  % "google-auth-library-oauth2-http"      % "1.40.0",
  // included in libs as jitpack is not stable at all
  // "com.github.Opetushallitus"        % "scala-schema"                              % "2.34.0_2.12" excludeAll (
  //   ExclusionRule("com.github.spotbugs", "spotbugs-annotations"),
  //   ExclusionRule("ch.qos.logback"),
  //   ExclusionRule("org.slf4j"),
  // ),
  // "org.reflections"                  % "reflections"                          % "0.10.2",
  // "org.json4s"                       % "json4s-jackson_2.12"                  % "4.0.7",
  // "org.json4s"                       % "json4s-ast_2.12"                      % "4.0.7",
  // "org.json4s"                       % "json4s-ext_2.12"                      % "4.0.7",
  "io.swagger.core.v3"               % "swagger-core-jakarta"                 % "2.2.49" excludeAll (
    ExclusionRule("org.slf4j"),
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat"),
    ExclusionRule(organization = "org.playframework", name = "play-json"),
  ),
  // swagger-scala-module dropped: no Scala 3 build and pulls Akka. We keep the Java swagger-core
  // above; api.scala already has a fallback when the Scala converter is not registered.
  "org.scala-lang.modules"          %% "scala-java8-compat"                   % "1.0.2",
  /*"org.sangria-graphql"             %% "sangria-play-json"              % "2.0.1" excludeAll ExclusionRule(
    organization = "com.typesafe.play"
  )*/ // TODO - check if needed
  // new http stack ;)
  "io.projectreactor.netty"          % "reactor-netty-core"                   % reactorNettyVersion,
  "io.projectreactor.netty"          % "reactor-netty-http"                   % reactorNettyVersion,
  "io.netty"                         % "netty-transport-native-kqueue"        % nettyVersion,
  "io.netty"                         % "netty-transport-native-kqueue"        % nettyVersion classifier "osx-aarch_64" classifier "osx-x86_64",
  "io.netty"                         % "netty-transport-native-epoll"         % nettyVersion,
  "io.netty"                         % "netty-transport-native-epoll"         % nettyVersion classifier "linux-x86_64" classifier "linux-aarch_64",
  //"io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.25.Final",
  //"io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.25.Final" classifier "linux-x86_64" classifier "linux-aarch_64",
  "io.netty.incubator"               % "netty-incubator-codec-native-quic"    % "0.0.62.Final",
  "io.netty.incubator"               % "netty-incubator-codec-native-quic"    % "0.0.62.Final" classifier "linux-x86_64" classifier "osx-x86_64",
  "io.netty.incubator"               % "netty-incubator-codec-http3"          % "0.0.28.Final",
  // tests
  "org.scalatestplus.play"          %% "scalatestplus-play"                   % "7.0.2"  % Test,
  "com.networknt"                    % "json-schema-validator"                % "1.3.0" excludeAll (
    ExclusionRule("org.slf4j"),
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
  ),
  "jakarta.jms"                      % "jakarta.jms-api"                      % "3.1.0",
  "org.apache.activemq"              % "artemis-jakarta-client"               % "2.41.0" excludeAll (
    ExclusionRule("org.slf4j"),
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
  ),
  "com.dimafeng"                    %% "testcontainers-scala-scalatest"       % "0.44.1" % Test,
  "com.microsoft.playwright"         % "playwright"                           % "1.47.0" % Test
  // https://github.com/mvel/mvel
  // "org.mvel"                         % "mvel2"                                     % "2.5.2.Final"
)

scalacOptions ++= Seq(
  "-feature",
  // accept Scala 2.13 syntax (e.g. un-parenthesized typed lambda params) as warnings during the
  // migration, to keep the diff minimal instead of rewriting hundreds of call-sites
  "-source:3.0-migration",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

// resolvers += "jitpack" at "https://jitpack.io"

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

// Twirl 2.x still hardcodes `import scala.language.adhocExtensions` in generated template wrappers,
// but the marker val was removed from scala.runtime.stdLibPatches.language in Scala 3.8. Stripping
// the now-defunct import from the generated sources produces identical bytecode.
Compile / TwirlKeys.compileTemplates := {
  val generated = (Compile / TwirlKeys.compileTemplates).value
  generated.foreach { f =>
    val orig    = IO.read(f)
    val patched = orig.replace("import scala.language.adhocExtensions", "")
    if (patched != orig) IO.write(f, patched)
  }
  generated
}

// sources in (Compile, doc) := Seq.empty
// publishArtifact in (Compile, packageDoc) := false
// scalafmtVersion in ThisBuild := "1.2.0"

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
//githubOwner := "MAIF"
//githubRepository := "otoroshi"
// githubTokenSource := TokenSource.Environment("GITHUB_PACKAGES_TOKEN")

// assembly
assembly / mainClass := Some("play.core.server.ProdServerStart")
assembly / test := {}
assembly / assemblyJarName := "otoroshi.jar"
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)
assembly / assemblyMergeStrategy := { e =>
  e match {
    case path if path.contains("com/upokecenter/util")                  => MergeStrategy.first
    case path if path.contains("org/slf4j/impl")                        => MergeStrategy.first
    case path if path.contains("edu/umd/cs/findbugs/annotations")       => MergeStrategy.first
    case PathList("scala", xs @ _*)                                     => MergeStrategy.first
    case PathList("org", "apache", "commons", "logging", xs @ _*)       => MergeStrategy.first
    case PathList("org", "apache", "commons", "lang", xs @ _*)          => MergeStrategy.first
    case PathList("org", "apache", "commons", "collections", xs @ _*)   => MergeStrategy.first
    case PathList(
          "org",
          "apache",
          "maven",
          "surefire",
          "shade",
          "org",
          "apache",
          "maven",
          "shared",
          "utils",
          "StringUtils.class"
        ) =>
      MergeStrategy.first
    case PathList("io", "sundr", xs @ _*)                               => MergeStrategy.first
    case PathList("com", "sun", "xml", xs @ _*)                         => MergeStrategy.first
    case PathList("com", "sun", "istack", xs @ _*)                      => MergeStrategy.first
    case PathList("com", "sun", "activation", xs @ _*)                  => MergeStrategy.first
    case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("reference-overrides.conf")   => MergeStrategy.concat
    case PathList(ps @ _*) if ps.contains("field_mask.proto")           => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("plugin.proto")               =>
      MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
    case PathList(ps @ _*) if ps.contains("descriptor.proto")           =>
      MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
    case PathList(ps @ _*) if ps.contains("mailcap.default")            => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("module-info.class")          => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("ModuleUtil.class")           => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("GuardedBy.class")            => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("nowarn$.class")              => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("nowarn.class")               => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("reflection-config.json")     => MergeStrategy.first // ???
    case PathList(ps @ _*) if ps.contains("metadata.json")              => MergeStrategy.first // ??? hope webauthn comes first
    case PathList(ps @ _*) if ps.contains("version.conf")               => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("any.proto")                  => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("api.proto")                  => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("duration.proto")             => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("empty.proto")                => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("struct.proto")               => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("type.proto")                 => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("timestamp.proto")            => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("wrappers.proto")             => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("source_context.proto")       => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("native-image.properties")    => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("library.properties")         => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("public-suffix-list.txt")     => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("jna")                        => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("findbugsExclude.xml")        => MergeStrategy.first
    case PathList(ps @ _*) if ps.contains("okio.kotlin_module")         => MergeStrategy.first
    case path if path.contains("pekko/stream")                          => MergeStrategy.first
    case path if path.contains("org/bouncycastle")                      => MergeStrategy.first
    case PathList("javax", xs @ _*)                                     => MergeStrategy.first
    case x                                                              =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (Compile / dist).value
  (Compile / assembly).value
}

import play.sbt.PlayImport.PlayKeys._

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

// run with: ~reStart
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
  "-Dotoroshi.liveJs=true",
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
  "-Dotoroshi.options.enable-json-media-type-with-open-charset=true",
  "-Dotoroshi.next.state-sync-interval=1000",
  // "-Dotoroshi.next.experimental.netty-server.native.driver=IOUring",
  "-DVAULT_VALUE=admin-api-apikey-secret",
  "-Dapp.redis.lettuce.pooling.enabled=true",
  "-Dotoroshi.storage=file"
  //"-Dotoroshi.storage=ext:foo",
//  "-Dotoroshi.storage=lettuce",
//  "-Dapp.redis.lettuce.uri=redis-sentinel://masterpassword@localhost:26379?sentinelMasterId=mymaster",
//  "-Dapp.redis.lettuce.sentinels.password=sentinelpassword",
//  "-Dapp.redis.lettuce.sentinels.username=default"
  //"-Dotoroshi.storage=inmemory",
  //"-Dotoroshi.storage=pg",
  //"-Dotoroshi.storage=redis",
)
