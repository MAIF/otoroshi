import xerial.sbt.Sonatype._

name := """otoroshi"""
organization := "fr.maif"
version := "1.5.0-dev"
scalaVersion := scalaLangVersion

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
  .enablePlugins(PlayScala, PlayAkkaHttp2Support)
  .disablePlugins(PlayFilters)

lazy val scalaLangVersion    = "2.12.13"
// lazy val scalaLangVersion = "2.13.2"
// * https://github.com/propensive/kaleidoscope/issues/24
// * https://github.com/risksense/ipaddr/issues/11
lazy val metricsVersion      = "4.1.31"
lazy val acme4jVersion       = "2.11"
lazy val prometheusVersion   = "0.15.0"
lazy val playJsonVersion     = "2.8.1"
lazy val webAuthnVersion     = "1.7.0" // breaks jackson modules at 1.7.0
lazy val kubernetesVersion   = "8.0.2"
lazy val bouncyCastleVersion = "1.68"
lazy val pulsarVersion       = "2.6.3"
lazy val excludesJackson     = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.softwaremill.macwire"        %% "macros"                         % "2.3.7" % "provided",
  "com.typesafe.play"               %% "play-json"                      % playJsonVersion,
  "com.typesafe.play"               %% "play-json-joda"                 % playJsonVersion,
  "com.github.etaty"                %% "rediscala"                      % "1.9.0",
  "com.github.gphat"                %% "censorinus"                     % "2.1.16",
  "com.typesafe.akka"               %% "akka-stream-kafka"              % "2.0.7",
  "com.lightbend.akka"              %% "akka-stream-alpakka-s3"         % "2.0.2",
  "com.typesafe.akka"               %% "akka-http-xml"                  % "10.1.15",
  "com.spotify.metrics"              % "semantic-metrics-core"          % "1.1.11",
//  "io.dropwizard.metrics"    % "metrics-jvm"                 % metricsVersion,    // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-jmx"                    % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-json"                   % metricsVersion, // Apache 2.0
  "io.prometheus"                    % "simpleclient_common"            % prometheusVersion, // Apache 2.0
  "io.prometheus"                    % "simpleclient_dropwizard"        % prometheusVersion, // Apache 2.0
  "com.auth0"                        % "java-jwt"                       % "3.14.0" excludeAll (excludesJackson: _*),
  "com.auth0"                        % "jwks-rsa"                       % "0.17.0" excludeAll (excludesJackson: _*), // https://github.com/auth0/jwks-rsa-java
  "com.nimbusds"                     % "nimbus-jose-jwt"                % "9.7",
  "de.svenkubiak"                    % "jBCrypt"                        % "0.4.3",
  "com.propensive"                  %% "kaleidoscope"                   % "0.1.0",
  "io.github.classgraph"             % "classgraph"                     % "4.8.102",
  "com.risksense"                   %% "ipaddr"                         % "1.0.2",
  "com.yubico"                       % "webauthn-server-core"           % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.yubico"                       % "webauthn-server-attestation"    % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.yubico"                       % "yubico-util"                    % webAuthnVersion excludeAll (excludesJackson: _*),
  "com.maxmind.geoip2"               % "geoip2"                         % "2.13.1",
  "com.blueconic"                    % "browscap-java"                  % "1.3.3",
  "javax.xml.bind"                   % "jaxb-api"                       % "2.3.1", // https://stackoverflow.com/questions/48204141/replacements-for-deprecated-jpms-modules-with-java-ee-apis/48204154#48204154
  "com.sun.xml.bind"                 % "jaxb-core"                      % "2.3.0.1",
  //"com.sun.xml.bind"         % "jaxb-impl"                   % "2.3.2",
  "com.github.blemale"              %% "scaffeine"                      % "4.0.2",
  "org.shredzone.acme4j"             % "acme4j-client"                  % acme4jVersion,
  "org.shredzone.acme4j"             % "acme4j-utils"                   % acme4jVersion,
  "org.shredzone.acme4j"             % "acme4j"                         % acme4jVersion,
  "io.lettuce"                       % "lettuce-core"                   % "6.0.2.RELEASE",
  "io.vertx"                         % "vertx-pg-client"                % "4.0.3",
  "com.ongres.scram"                 % "common"                         % "2.1",
  "com.ongres.scram"                 % "client"                         % "2.1",
  "com.jayway.jsonpath"              % "json-path"                      % "2.5.0",
  "com.cronutils"                    % "cron-utils"                     % "9.1.3",
  "commons-lang"                     % "commons-lang"                   % "2.6",
  "com.datastax.oss"                 % "java-driver-core"               % "4.5.1",
  "org.gnieh"                       %% "diffson-play-json"              % "4.0.3" excludeAll ExclusionRule(organization = "com.typesafe.akka"),
  "org.scala-lang"                   % "scala-compiler"                 % scalaLangVersion,
  "org.scala-lang"                   % "scala-library"                  % scalaLangVersion,
  "io.kubernetes"                    % "client-java"                    % kubernetesVersion,
  "io.kubernetes"                    % "client-java-extended"           % kubernetesVersion,
  "org.bouncycastle"                 % "bcpkix-jdk15on"                 % bouncyCastleVersion,
  "org.bouncycastle"                 % "bcprov-ext-jdk15on"             % bouncyCastleVersion,
  "org.bouncycastle"                 % "bcprov-jdk15on"                 % bouncyCastleVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-play-json"             % pulsarVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-core"                  % pulsarVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-akka-streams"          % pulsarVersion,
  "org.jsoup"                        % "jsoup"                          % "1.14.3",
  "com.clever-cloud"                 % "biscuit-java"                   % "2.2.0",
  "org.opensaml"                     % "opensaml-core"                  % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-api"              % "4.0.1",
  //"org.opensaml"                     % "opensaml-xmlsec-impl"        % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-impl"             % "4.0.1",
  "org.openjdk.jol"                  % "jol-core"                       % "0.16",
  "org.typelevel"                   %% "squants"                        % "1.6.0",
  // fix multiple CVEs
  "com.fasterxml.jackson.core"       % "jackson-databind"               % "2.10.5.1",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"        % "2.10.5",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"        % "2.10.5",
  "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"          % "2.10.5",
  "org.yaml"                         % "snakeyaml"                      % "1.28",
  "com.arakelian"                    % "java-jq"                        % "1.2.0",
  "io.opentelemetry"                 % "opentelemetry-api"              % "1.11.0",
  "io.opentelemetry"                 % "opentelemetry-sdk"              % "1.11.0",
  "io.opentelemetry"                 % "opentelemetry-exporter-logging" % "1.11.0",
  "io.opentelemetry"                 % "opentelemetry-exporter-zipkin"  % "1.11.0",
  "io.opentelemetry"                 % "opentelemetry-exporter-jaeger"  % "1.11.0",
  "com.amazonaws"                    % "aws-java-sdk-secretsmanager"    % "1.12.173" excludeAll (excludesJackson: _*),
  "io.projectreactor.netty"           % "reactor-netty-core"            % "1.0.22",
  "io.projectreactor.netty"           % "reactor-netty-http"            % "1.0.22",
  "io.projectreactor.netty.incubator" % "reactor-netty-incubator-quic"  % "0.0.12",
  "io.netty"                          % "netty-transport-native-kqueue" % "4.1.81.Final",
  "io.netty"                          % "netty-transport-native-kqueue" % "4.1.81.Final" classifier "osx-aarch_64" classifier "osx-x86_64",
  "io.netty"                          % "netty-transport-native-epoll"  % "4.1.81.Final",
  "io.netty"                          % "netty-transport-native-epoll"  % "4.1.81.Final" classifier "linux-x86_64" classifier "linux-aarch_64",
  "io.netty.incubator"                % "netty-incubator-transport-native-io_uring" % "0.0.15.Final",
  "io.netty.incubator"                % "netty-incubator-transport-native-io_uring" % "0.0.15.Final" classifier "linux-x86_64" classifier "linux-aarch_64",
  // tests
  "org.scalatestplus.play"          %% "scalatestplus-play"             % "5.1.0" % Test,
  // do not update because the feature is deprecated and will be removed
  "org.reactivemongo"               %% "reactivemongo"                  % "0.20.13" excludeAll ExclusionRule(organization =
    "org.apache.logging.log4j"
  ),
  "org.iq80.leveldb"                 % "leveldb"                        % "0.12",
  "org.apache.logging.log4j"         % "log4j-api"                      % "2.17.1",
  "org.sangria-graphql"             %% "sangria"                        % "3.0.0"
  /*"org.sangria-graphql"             %% "sangria-play-json"              % "2.0.1" excludeAll ExclusionRule(
    organization = "com.typesafe.play"
  )*/ // TODO - check if needed
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

// sources in (Compile, doc) := Seq.empty
// publishArtifact in (Compile, packageDoc) := false
// scalafmtVersion in ThisBuild := "1.2.0"

parallelExecution in Test := false

usePgpKeyHex("555B52C0AEAF5DE0D95F0F69DFA62E480E20755C")
sonatypeProjectHosting := Some(GitHubHosting("MAIF", "otoroshi", "mathieu.ancelin@serli.com"))
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
sonatypeCredentialHost := "s01.oss.sonatype.org"
licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

// assembly
mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}
assemblyJarName in assembly := "otoroshi.jar"
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)
assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", "logging", xs @ _*)       => MergeStrategy.first
  case PathList("org", "apache", "commons", "lang", xs @ _*)          => MergeStrategy.first
  case PathList("org", "apache", "commons", "collections", xs @ _*)   => MergeStrategy.first
  case PathList("io", "sundr", xs @ _*)                               => MergeStrategy.first
  case PathList("com", "sun", "xml", xs @ _*)                         => MergeStrategy.first
  case PathList("com", "sun", "istack", xs @ _*)                      => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf")   => MergeStrategy.concat
  case PathList(ps @ _*) if ps.contains("field_mask.proto")           => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("plugin.proto")               =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
  case PathList(ps @ _*) if ps.contains("descriptor.proto")           =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
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
  case PathList(ps @ _*) if ps.contains("public-suffix-list.txt")     => MergeStrategy.first
  case path if path.contains("org/bouncycastle")                      => MergeStrategy.first
  case PathList("javax", xs @ _*)                                     => MergeStrategy.first
  case x                                                              =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}

import play.sbt.PlayImport.PlayKeys._

packagedArtifacts in publish := {
  val artifacts: Map[sbt.Artifact, java.io.File] = (packagedArtifacts in publishLocal).value
  val assets: java.io.File                       = (playPackageAssets in Compile).value
  artifacts + (Artifact(moduleName.value, "jar", "jar", "assets") -> assets)
}

bashScriptExtraDefines += """
addJava "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED"
addJava "--add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED"
addJava "--add-exports=java.base/sun.security.x509=ALL-UNNAMED" 
addJava "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED" 
addJava "-Dlog4j2.formatMsgNoLookups=true"
"""

// run with: ~reStart
reStart / mainClass := Some("play.core.server.ProdServerStart")
reStart / javaOptions ++= Seq(
  "-Xms2g",
  "-Xmx8g",
  "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED",
  "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
  "-Dlog4j2.formatMsgNoLookups=true",
  "-Dotoroshi.revolver=true",
  "-Dotoroshi.env=dev",
  "-Dotoroshi.http.port=9999",
  "-Dotoroshi.https.port=9998",
  "-Dotoroshi.storage=file",
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
  "-Dotoroshi.ssl.fromOutside.clientAuth=Want",
  "-Dotoroshi.inmemory.modern=true",
  "-Dotoroshi.next.experimental.netty-server.enabled=true",
  "-Dotoroshi.next.experimental.netty-server.accesslog=true",
  "-Dotoroshi.next.experimental.netty-server.wiretap=false",
  // "-Dotoroshi.storage=experimental-pg",
  // "-Dotoroshi.storage=redis",
  // "-Dotoroshi.storage=lettuce",
  // "-Dotoroshi.redis.lettuce.uri=redis://localhost:6379/",
)
