package plugins.configs

import org.apache.pekko.stream.connectors.s3.headers.CannedAcl
import otoroshi.next.plugins.*
import otoroshi.storage.drivers.inmemory.S3Configuration
import play.api.libs.json.*

import scala.concurrent.duration.*

class FilesPluginConfigsSpec extends PluginConfigFormatSpec {

  "StaticBackendConfig" should {

    val format = StaticBackendConfig.format

    "write the root path under root_path" in {
      format.writes(StaticBackendConfig("/var/www")) mustBe Json.obj("root_path" -> "/var/www")
    }

    "write the default config of StaticBackend with /tmp as root path" in {
      new StaticBackend().defaultConfig mustBe Some(Json.obj("root_path" -> "/tmp"))
    }

    "round trip the default root path" in {
      roundTrip(format, StaticBackendConfig("/tmp"))
    }

    "round trip a nested absolute path" in {
      roundTrip(format, StaticBackendConfig("/var/www/site/public")).rootPath mustBe "/var/www/site/public"
    }

    "round trip a relative path as is" in {
      roundTrip(format, StaticBackendConfig("./public/../static")).rootPath mustBe "./public/../static"
    }

    "round trip a path with a trailing slash as is" in {
      roundTrip(format, StaticBackendConfig("/var/www/")).rootPath mustBe "/var/www/"
    }

    "round trip a windows path" in {
      roundTrip(format, StaticBackendConfig("C:\\sites\\www"))
    }

    "round trip a path with spaces and unicode" in {
      roundTrip(format, StaticBackendConfig("/srv/mon site/été"))
    }

    "round trip an empty root path" in {
      roundTrip(format, StaticBackendConfig(""))
    }

    "refuse an empty object because the root path is mandatory" in {
      readError(format, Json.obj())
    }

    "refuse a json null" in {
      readError(format, JsNull)
    }

    "refuse a json string" in {
      readError(format, JsString("/var/www"))
    }

    "refuse a json array" in {
      readError(format, Json.arr("/var/www"))
    }

    "refuse a null root path" in {
      readError(format, Json.obj("root_path" -> JsNull))
    }

    "refuse a root path that is not a string" in {
      readError(format, Json.obj("root_path" -> Json.arr("/var/www")))
    }

    "refuse the camel case key" in {
      readError(format, Json.obj("rootPath" -> "/var/www"))
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("root_path" -> "/var/www", "index" -> "index.html")) mustBe StaticBackendConfig("/var/www")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, StaticBackendConfig("/var/www"))
    }

    "be the default config of StaticBackend" in {
      defaultConfigReadsBack(new StaticBackend(), format) mustBe StaticBackendConfig("/tmp")
    }
  }

  "S3BackendConfig" should {

    "nest the s3 configuration under s3" in {
      S3BackendConfig(S3Configuration.default).json mustBe Json.obj("s3" -> S3Configuration.default.json)
    }

    "not be the default config of S3Backend, that uses the bare s3 configuration" in {
      new S3Backend().defaultConfigObject mustBe Some(S3Configuration.default)
    }
  }

  "S3Configuration, the config of S3Backend" should {

    val format = S3Configuration.format

    val default = S3Configuration.default

    // every field set to something else than its default
    val full = S3Configuration(
      bucket = "my-bucket",
      endpoint = "https://s3.oto.tools",
      region = "us-east-1",
      access = "my-access",
      secret = "my-secret",
      key = "site/public",
      chunkSize = 1024,
      v4auth = false,
      writeEvery = 30.seconds,
      acl = CannedAcl.PublicRead,
      pathStyleAccess = true
    )

    "write the default config with all its keys" in {
      format.writes(default) mustBe Json.obj(
        "bucket"          -> "",
        "endpoint"        -> "",
        "region"          -> "eu-west-1",
        "access"          -> "client",
        "secret"          -> "secret",
        "key"             -> "",
        "chunkSize"       -> 8388608,
        "v4auth"          -> true,
        "writeEvery"      -> 60000,
        "acl"             -> "private",
        "pathStyleAccess" -> false
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full.copy(acl = CannedAcl.PublicRead)) mustBe Json.obj(
        "bucket"          -> "my-bucket",
        "endpoint"        -> "https://s3.oto.tools",
        "region"          -> "us-east-1",
        "access"          -> "my-access",
        "secret"          -> "my-secret",
        "key"             -> "site/public",
        "chunkSize"       -> 1024,
        "v4auth"          -> false,
        "writeEvery"      -> 30000,
        "acl"             -> "public-read",
        "pathStyleAccess" -> true
      )
    }

    "round trip the path style access alone" in {
      roundTrip(format, default.copy(pathStyleAccess = true)).pathStyleAccess mustBe true
    }

    "read an empty object with secret as access key instead of the client of the default config" in {
      readOk(format, Json.obj()) mustBe default.copy(access = "secret")
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe default.copy(access = "secret")
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("my-bucket")) mustBe default.copy(access = "secret")
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr()) mustBe default.copy(access = "secret")
    }

    "read the bucket alone and default the rest" in {
      readOk(format, Json.obj("bucket" -> "b", "access" -> "client")) mustBe default.copy(bucket = "b")
    }

    "read the write interval in milliseconds" in {
      readOk(format, Json.obj("writeEvery" -> 1500)).writeEvery mustBe 1500.millis
    }

    "strip the leading slash of the key" in {
      readOk(format, Json.obj("key" -> "/site/public")).key mustBe "site/public"
    }

    "strip only one leading slash of the key" in {
      readOk(format, Json.obj("key" -> "//site")).key mustBe "/site"
    }

    "keep a key without leading slash as is" in {
      readOk(format, Json.obj("key" -> "site/")).key mustBe "site/"
    }

    "read every acl from its canned acl name" in {
      def acl(name: String) = readOk(format, Json.obj("acl" -> name)).acl
      acl("AuthenticatedRead") mustBe CannedAcl.AuthenticatedRead
      acl("AwsExecRead") mustBe CannedAcl.AwsExecRead
      acl("BucketOwnerFullControl") mustBe CannedAcl.BucketOwnerFullControl
      acl("BucketOwnerRead") mustBe CannedAcl.BucketOwnerRead
      acl("Private") mustBe CannedAcl.Private
      acl("PublicRead") mustBe CannedAcl.PublicRead
      acl("PublicReadWrite") mustBe CannedAcl.PublicReadWrite
    }

    "read an unknown acl as private" in {
      readOk(format, Json.obj("acl" -> "Everyone")).acl mustBe CannedAcl.Private
    }

    "round trip every canned acl" in {
      Seq(
        CannedAcl.AuthenticatedRead,
        CannedAcl.AwsExecRead,
        CannedAcl.BucketOwnerFullControl,
        CannedAcl.BucketOwnerRead,
        CannedAcl.Private,
        CannedAcl.PublicRead,
        CannedAcl.PublicReadWrite
      ).foreach { acl =>
        roundTrip(format, S3Configuration.default.copy(acl = acl)).acl mustBe acl
      }
    }

    "read every acl from its s3 header value" in {
      def acl(value: String) = readOk(format, Json.obj("acl" -> value)).acl
      acl("authenticated-read") mustBe CannedAcl.AuthenticatedRead
      acl("aws-exec-read") mustBe CannedAcl.AwsExecRead
      acl("bucket-owner-full-control") mustBe CannedAcl.BucketOwnerFullControl
      acl("bucket-owner-read") mustBe CannedAcl.BucketOwnerRead
      acl("private") mustBe CannedAcl.Private
      acl("public-read") mustBe CannedAcl.PublicRead
      acl("public-read-write") mustBe CannedAcl.PublicReadWrite
    }

    "ignore an acl that is not a string" in {
      readOk(format, Json.obj("acl" -> 1)).acl mustBe CannedAcl.Private
    }

    "ignore a chunk size written as a string" in {
      readOk(format, Json.obj("chunkSize" -> "1024")).chunkSize mustBe 8388608
    }

    "ignore a chunk size that does not fit an int" in {
      readOk(format, Json.obj("chunkSize" -> 5000000000L)).chunkSize mustBe 8388608
    }

    "ignore a write interval written as a string" in {
      readOk(format, Json.obj("writeEvery" -> "1000")).writeEvery mustBe 1.minute
    }

    "ignore a v4auth flag written as a string" in {
      readOk(format, Json.obj("v4auth" -> "false")).v4auth mustBe true
    }

    "ignore a path style access flag written as a number" in {
      readOk(format, Json.obj("pathStyleAccess" -> 1)).pathStyleAccess mustBe false
    }

    "not read the snake case keys" in {
      val back = readOk(
        format,
        Json.obj("chunk_size" -> 1024, "write_every" -> 1000, "path_style_access" -> true, "access" -> "client")
      )
      back mustBe default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of S3Backend" in {
      defaultConfigReadsBack(new S3Backend(), format) mustBe default
    }
  }
}
