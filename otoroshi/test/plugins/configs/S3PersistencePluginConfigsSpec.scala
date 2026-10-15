package plugins.configs

import org.apache.pekko.stream.connectors.s3.headers.CannedAcl
import otoroshi.next.plugins.S3Backend
import otoroshi.storage.drivers.inmemory.S3Configuration
import play.api.libs.json.*

import scala.concurrent.duration.*

class S3PersistencePluginConfigsSpec extends PluginConfigFormatSpec {

  "S3Configuration" should {

    val format = S3Configuration.format

    // the acl stays private: any other acl is lost on the way back (see the report of this spec)
    val full = S3Configuration(
      bucket = "otoroshi-states",
      endpoint = "https://s3.oto.tools",
      region = "us-east-1",
      access = "AKIAXXXX",
      secret = "very-secret",
      key = "states/otoroshi.json",
      chunkSize = 5 * 1024 * 1024,
      v4auth = false,
      writeEvery = 30.seconds,
      acl = CannedAcl.Private,
      pathStyleAccess = true
    )

    "point to no bucket with dummy credentials by default" in {
      val config = S3Configuration.default
      config.bucket mustBe ""
      config.endpoint mustBe ""
      config.region mustBe "eu-west-1"
      config.access mustBe "client"
      config.secret mustBe "secret"
      config.key mustBe ""
      config.chunkSize mustBe 8388608
      config.v4auth mustBe true
      config.writeEvery mustBe 1.minute
      config.acl mustBe CannedAcl.Private
      config.pathStyleAccess mustBe false
    }

    "write the default config with camel case keys" in {
      format.writes(S3Configuration.default) mustBe Json.obj(
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

    "write the acl as its canned acl header value" in {
      (format.writes(S3Configuration.default.copy(acl = CannedAcl.PublicRead)) \ "acl").as[String] mustBe "public-read"
      (format.writes(S3Configuration.default.copy(acl = CannedAcl.BucketOwnerFullControl)) \ "acl")
        .as[String] mustBe "bucket-owner-full-control"
    }

    "write the write interval in milliseconds" in {
      (format.writes(S3Configuration.default.copy(writeEvery = 2.hours)) \ "writeEvery").as[Long] mustBe 7200000L
    }

    "round trip the default config" in {
      roundTrip(format, S3Configuration.default)
    }

    "round trip a config where every field but the acl is set" in {
      roundTrip(format, full)
    }

    "round trip a disabled v4 auth" in {
      roundTrip(format, S3Configuration.default.copy(v4auth = false)).v4auth mustBe false
    }

    "round trip the path style access" in {
      roundTrip(format, S3Configuration.default.copy(pathStyleAccess = true)).pathStyleAccess mustBe true
    }

    "round trip a write interval with a millisecond precision" in {
      roundTrip(format, S3Configuration.default.copy(writeEvery = 1500.millis)).writeEvery mustBe 1500.millis
    }

    "truncate a write interval to the millisecond" in {
      val config = S3Configuration.default.copy(writeEvery = 1500.micros)
      readOk(format, format.writes(config)).writeEvery mustBe 1.millis
    }

    "round trip a key without leading slash" in {
      roundTrip(format, S3Configuration.default.copy(key = "a/b/c.json")).key mustBe "a/b/c.json"
    }

    "strip the leading slash of the key" in {
      readOk(format, Json.obj("key" -> "/states/otoroshi.json")).key mustBe "states/otoroshi.json"
    }

    "strip only one leading slash of the key" in {
      readOk(format, Json.obj("key" -> "//states")).key mustBe "/states"
    }

    "read an empty object as the default config, with secret as access" in {
      readOk(format, Json.obj()) mustBe S3Configuration.default.copy(access = "secret")
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe S3Configuration.default.copy(access = "secret")
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("otoroshi-states")) mustBe S3Configuration.default.copy(access = "secret")
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr()) mustBe S3Configuration.default.copy(access = "secret")
    }

    "read the bucket alone" in {
      readOk(format, Json.obj("bucket" -> "my-bucket")).bucket mustBe "my-bucket"
    }

    "read the path style access alone" in {
      readOk(format, Json.obj("pathStyleAccess" -> true)).pathStyleAccess mustBe true
    }

    "ignore a chunk size written as a string" in {
      readOk(format, Json.obj("chunkSize" -> "1024")).chunkSize mustBe 8388608
    }

    "ignore a write interval written as a duration string" in {
      readOk(format, Json.obj("writeEvery" -> "10s")).writeEvery mustBe 1.minute
    }

    "ignore a write interval that is not a whole number" in {
      readOk(format, Json.obj("writeEvery" -> 1500.5)).writeEvery mustBe 1.minute
    }

    "ignore a v4auth flag written as a string" in {
      readOk(format, Json.obj("v4auth" -> "false")).v4auth mustBe true
    }

    "ignore a credential that is not a string" in {
      val back = readOk(format, Json.obj("access" -> 42, "secret" -> true))
      back.access mustBe "secret"
      back.secret mustBe "secret"
    }

    "not read the snake case keys" in {
      val back = readOk(format, Json.obj("chunk_size" -> 1024, "write_every" -> 1000, "path_style_access" -> true))
      back.chunkSize mustBe 8388608
      back.writeEvery mustBe 1.minute
      back.pathStyleAccess mustBe false
    }

    "read the acls from their pascal case names" in {
      def acl(name: String) = readOk(format, Json.obj("acl" -> name)).acl
      acl("AuthenticatedRead") mustBe CannedAcl.AuthenticatedRead
      acl("AwsExecRead") mustBe CannedAcl.AwsExecRead
      acl("BucketOwnerFullControl") mustBe CannedAcl.BucketOwnerFullControl
      acl("BucketOwnerRead") mustBe CannedAcl.BucketOwnerRead
      acl("Private") mustBe CannedAcl.Private
      acl("PublicRead") mustBe CannedAcl.PublicRead
      acl("PublicReadWrite") mustBe CannedAcl.PublicReadWrite
    }

    "read the private acl it writes" in {
      readOk(format, Json.obj("acl" -> "private")).acl mustBe CannedAcl.Private
    }

    "read an unknown acl as private" in {
      readOk(format, Json.obj("acl" -> "everyone")).acl mustBe CannedAcl.Private
    }

    "read an acl that is not a string as private" in {
      readOk(format, Json.obj("acl" -> 1)).acl mustBe CannedAcl.Private
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of S3Backend" in {
      defaultConfigReadsBack(new S3Backend(), format) mustBe S3Configuration.default
    }
  }
}
