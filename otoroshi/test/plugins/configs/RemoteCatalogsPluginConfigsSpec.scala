package plugins.configs

import otoroshi.next.catalogs.*
import play.api.libs.json.*

class RemoteCatalogsPluginConfigsSpec extends PluginConfigFormatSpec {

  "RemoteCatalogDeploySingleConfig" should {

    val format = RemoteCatalogDeploySingleConfig.format

    "wrap an empty object by default" in {
      RemoteCatalogDeploySingleConfig().json mustBe Json.obj()
      RemoteCatalogDeploySingleConfig().catalogRef mustBe ""
    }

    "write the default config as an empty object" in {
      format.writes(RemoteCatalogDeploySingleConfig()) mustBe Json.obj()
    }

    "write the wrapped json as is" in {
      val json = Json.obj("catalog_ref" -> "remote-catalog_1", "foo" -> Json.arr(1, 2))
      format.writes(RemoteCatalogDeploySingleConfig(json)) mustBe json
    }

    "round trip the default config" in {
      roundTrip(format, RemoteCatalogDeploySingleConfig())
    }

    "round trip a catalog reference" in {
      roundTrip(format, RemoteCatalogDeploySingleConfig(Json.obj("catalog_ref" -> "remote-catalog_1")))
        .catalogRef mustBe "remote-catalog_1"
    }

    "keep unknown keys on a round trip" in {
      val back = roundTrip(
        format,
        RemoteCatalogDeploySingleConfig(Json.obj("catalog_ref" -> "remote-catalog_1", "extra" -> Json.obj("a" -> 1)))
      )
      (back.json \ "extra" \ "a").as[Int] mustBe 1
    }

    "read the catalog reference from catalog_ref" in {
      readOk(format, Json.obj("catalog_ref" -> "remote-catalog_2")).catalogRef mustBe "remote-catalog_2"
    }

    "keep the catalog reference verbatim" in {
      readOk(format, Json.obj("catalog_ref" -> " Remote-Catalog_2 ")).catalogRef mustBe " Remote-Catalog_2 "
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RemoteCatalogDeploySingleConfig()
    }

    "read a json null as a config without catalog reference" in {
      val config = readOk(format, JsNull)
      config.json mustBe JsNull
      config.catalogRef mustBe ""
    }

    "read a json string as a config without catalog reference" in {
      val config = readOk(format, JsString("remote-catalog_1"))
      config.json mustBe JsString("remote-catalog_1")
      config.catalogRef mustBe ""
    }

    "read a json array as a config without catalog reference" in {
      readOk(format, Json.arr("remote-catalog_1")).catalogRef mustBe ""
    }

    "write back a non object json as is" in {
      format.writes(readOk(format, Json.arr("remote-catalog_1"))) mustBe Json.arr("remote-catalog_1")
    }

    "have no catalog reference when catalog_ref is not a string" in {
      readOk(format, Json.obj("catalog_ref" -> 42)).catalogRef mustBe ""
    }

    "have no catalog reference when catalog_ref is null" in {
      readOk(format, Json.obj("catalog_ref" -> JsNull)).catalogRef mustBe ""
    }

    "not read the camel case catalogRef key" in {
      readOk(format, Json.obj("catalogRef" -> "remote-catalog_1")).catalogRef mustBe ""
    }

    "not read a list of references" in {
      readOk(format, Json.obj("catalog_refs" -> Json.arr("remote-catalog_1"))).catalogRef mustBe ""
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RemoteCatalogDeploySingleConfig(Json.obj("catalog_ref" -> "remote-catalog_1")))
    }

    "list catalog_ref in its config flow and schema" in {
      RemoteCatalogDeploySingleConfig.configFlow mustBe Seq("catalog_ref")
      RemoteCatalogDeploySingleConfig.configSchema.get.keys mustBe Set("catalog_ref")
    }

    "offer the remote catalogs as a single select in its schema" in {
      val field = (RemoteCatalogDeploySingleConfig.configSchema.get \ "catalog_ref").as[JsObject]
      (field \ "type").as[String] mustBe "select"
      (field \ "props" \ "optionsFrom").as[String] mustBe "/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs"
    }

    "be the default config of RemoteCatalogDeploySingle" in {
      defaultConfigReadsBack(new RemoteCatalogDeploySingle(), format) mustBe RemoteCatalogDeploySingleConfig()
    }

    "be the config flow and schema of RemoteCatalogDeploySingle" in {
      val plugin = new RemoteCatalogDeploySingle()
      plugin.configFlow mustBe RemoteCatalogDeploySingleConfig.configFlow
      plugin.configSchema mustBe RemoteCatalogDeploySingleConfig.configSchema
    }
  }

  "RemoteCatalogDeployManyConfig" should {

    val format = RemoteCatalogDeployManyConfig.format

    "wrap an empty object by default" in {
      RemoteCatalogDeployManyConfig().json mustBe Json.obj()
      RemoteCatalogDeployManyConfig().catalogRefs mustBe empty
    }

    "write the default config as an empty object" in {
      format.writes(RemoteCatalogDeployManyConfig()) mustBe Json.obj()
    }

    "write the wrapped json as is" in {
      val json = Json.obj("catalog_refs" -> Json.arr("a", "b"), "foo" -> "bar")
      format.writes(RemoteCatalogDeployManyConfig(json)) mustBe json
    }

    "round trip the default config" in {
      roundTrip(format, RemoteCatalogDeployManyConfig())
    }

    "round trip several catalog references and keep their order" in {
      roundTrip(format, RemoteCatalogDeployManyConfig(Json.obj("catalog_refs" -> Json.arr("c", "a", "b"))))
        .catalogRefs mustBe Seq("c", "a", "b")
    }

    "round trip an empty list of catalog references" in {
      roundTrip(format, RemoteCatalogDeployManyConfig(Json.obj("catalog_refs" -> Json.arr()))).catalogRefs mustBe empty
    }

    "keep unknown keys on a round trip" in {
      val back = roundTrip(
        format,
        RemoteCatalogDeployManyConfig(Json.obj("catalog_refs" -> Json.arr("a"), "extra" -> true))
      )
      (back.json \ "extra").as[Boolean] mustBe true
    }

    "keep duplicated catalog references" in {
      readOk(format, Json.obj("catalog_refs" -> Json.arr("a", "a"))).catalogRefs mustBe Seq("a", "a")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RemoteCatalogDeployManyConfig()
    }

    "read a json null as a config without catalog reference" in {
      val config = readOk(format, JsNull)
      config.json mustBe JsNull
      config.catalogRefs mustBe empty
    }

    "read a json string as a config without catalog reference" in {
      readOk(format, JsString("a")).catalogRefs mustBe empty
    }

    "read a json array as a config without catalog reference, even an array of references" in {
      readOk(format, Json.arr("a", "b")).catalogRefs mustBe empty
    }

    "have no catalog reference when catalog_refs is a single string" in {
      readOk(format, Json.obj("catalog_refs" -> "a")).catalogRefs mustBe empty
    }

    "have no catalog reference when catalog_refs holds something else than strings" in {
      readOk(format, Json.obj("catalog_refs" -> Json.arr("a", 1))).catalogRefs mustBe empty
    }

    "not read the single catalog_ref key" in {
      readOk(format, Json.obj("catalog_ref" -> "a")).catalogRefs mustBe empty
    }

    "not read the camel case catalogRefs key" in {
      readOk(format, Json.obj("catalogRefs" -> Json.arr("a"))).catalogRefs mustBe empty
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RemoteCatalogDeployManyConfig(Json.obj("catalog_refs" -> Json.arr("a"))))
    }

    "list catalog_refs in its config flow and schema" in {
      RemoteCatalogDeployManyConfig.configFlow mustBe Seq("catalog_refs")
      RemoteCatalogDeployManyConfig.configSchema.get.keys mustBe Set("catalog_refs")
    }

    "offer the remote catalogs as a multi select in its schema" in {
      (RemoteCatalogDeployManyConfig.configSchema.get \ "catalog_refs" \ "type").as[String] mustBe "array-select"
    }

    "be the default config of RemoteCatalogDeployMany" in {
      defaultConfigReadsBack(new RemoteCatalogDeployMany(), format) mustBe RemoteCatalogDeployManyConfig()
    }

    "be the config flow and schema of RemoteCatalogDeployMany" in {
      val plugin = new RemoteCatalogDeployMany()
      plugin.configFlow mustBe RemoteCatalogDeployManyConfig.configFlow
      plugin.configSchema mustBe RemoteCatalogDeployManyConfig.configSchema
    }
  }

  "RemoteCatalogDeployWebhookConfig" should {

    val format = RemoteCatalogDeployWebhookConfig.format

    "wrap an empty object by default, so github webhooks and no catalog" in {
      val config = RemoteCatalogDeployWebhookConfig()
      config.json mustBe Json.obj()
      config.catalogRefs mustBe empty
      config.sourceType mustBe "github"
    }

    "write the default config as an empty object, without the github source type" in {
      format.writes(RemoteCatalogDeployWebhookConfig()) mustBe Json.obj()
    }

    "write the wrapped json as is" in {
      val json = Json.obj("catalog_refs" -> Json.arr("a"), "source_type" -> "gitlab")
      format.writes(RemoteCatalogDeployWebhookConfig(json)) mustBe json
    }

    "round trip the default config" in {
      roundTrip(format, RemoteCatalogDeployWebhookConfig())
    }

    "round trip a config where every field is set" in {
      val back = roundTrip(
        format,
        RemoteCatalogDeployWebhookConfig(Json.obj("catalog_refs" -> Json.arr("a", "b"), "source_type" -> "gitea"))
      )
      back.catalogRefs mustBe Seq("a", "b")
      back.sourceType mustBe "gitea"
    }

    "round trip every source type offered by its schema" in {
      val sourceTypes = (RemoteCatalogDeployWebhookConfig.configSchema.get \ "source_type" \ "props" \ "options")
        .as[Seq[JsObject]]
        .map(o => (o \ "value").as[String])
      sourceTypes mustBe Seq("github", "gitlab", "bitbucket", "bitbucketserver", "gitea", "forgejo", "codeberg")
      sourceTypes.foreach { sourceType =>
        roundTrip(format, RemoteCatalogDeployWebhookConfig(Json.obj("source_type" -> sourceType)))
          .sourceType mustBe sourceType
      }
    }

    "read the catalog references alone, with the github source type" in {
      val config = readOk(format, Json.obj("catalog_refs" -> Json.arr("a")))
      config.catalogRefs mustBe Seq("a")
      config.sourceType mustBe "github"
    }

    "read the source type alone, without catalog" in {
      val config = readOk(format, Json.obj("source_type" -> "codeberg"))
      config.catalogRefs mustBe empty
      config.sourceType mustBe "codeberg"
    }

    "keep an unknown source type as is" in {
      readOk(format, Json.obj("source_type" -> "svn")).sourceType mustBe "svn"
    }

    "keep the case of the source type" in {
      readOk(format, Json.obj("source_type" -> "GitHub")).sourceType mustBe "GitHub"
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RemoteCatalogDeployWebhookConfig()
    }

    "read a json null as a github config without catalog" in {
      val config = readOk(format, JsNull)
      config.json mustBe JsNull
      config.catalogRefs mustBe empty
      config.sourceType mustBe "github"
    }

    "read a json string as a github config without catalog" in {
      val config = readOk(format, JsString("gitlab"))
      config.catalogRefs mustBe empty
      config.sourceType mustBe "github"
    }

    "read a json array as a github config without catalog" in {
      val config = readOk(format, Json.arr("a"))
      config.catalogRefs mustBe empty
      config.sourceType mustBe "github"
    }

    "fall back to github when the source type is not a string" in {
      readOk(format, Json.obj("source_type" -> 1)).sourceType mustBe "github"
    }

    "fall back to github when the source type is null" in {
      readOk(format, Json.obj("source_type" -> JsNull)).sourceType mustBe "github"
    }

    "have no catalog reference when catalog_refs holds something else than strings" in {
      readOk(format, Json.obj("catalog_refs" -> Json.arr(Json.obj("id" -> "a")))).catalogRefs mustBe empty
    }

    "not read the camel case keys" in {
      val config = readOk(format, Json.obj("catalogRefs" -> Json.arr("a"), "sourceType" -> "gitlab"))
      config.catalogRefs mustBe empty
      config.sourceType mustBe "github"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(
        format,
        RemoteCatalogDeployWebhookConfig(Json.obj("catalog_refs" -> Json.arr("a"), "source_type" -> "gitlab"))
      )
    }

    "list catalog_refs and source_type in its config flow and schema" in {
      RemoteCatalogDeployWebhookConfig.configFlow mustBe Seq("catalog_refs", "source_type")
      RemoteCatalogDeployWebhookConfig.configSchema.get.keys mustBe Set("catalog_refs", "source_type")
    }

    "be the default config of RemoteCatalogDeployWebhook" in {
      defaultConfigReadsBack(new RemoteCatalogDeployWebhook(), format) mustBe RemoteCatalogDeployWebhookConfig()
    }

    "be the config flow and schema of RemoteCatalogDeployWebhook" in {
      val plugin = new RemoteCatalogDeployWebhook()
      plugin.configFlow mustBe RemoteCatalogDeployWebhookConfig.configFlow
      plugin.configSchema mustBe RemoteCatalogDeployWebhookConfig.configSchema
    }
  }
}
