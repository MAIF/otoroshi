package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class Auth0PasswordlessPluginConfigsSpec extends PluginConfigFormatSpec {

  "Auth0PasswordlessAuthConfig" should {

    val format = Auth0PasswordlessAuthConfig.format

    val full = Auth0PasswordlessAuthConfig(
      ref = "auth_mod_auth0",
      connection = Auth0PasswordlessConnectionKind.Sms,
      send = Auth0PasswordlessSendKind.Link,
      audience = Some("https://api.oto.tools")
    )

    "send a code by email, without auth module nor audience, by default" in {
      Auth0PasswordlessAuthConfig.default mustBe Auth0PasswordlessAuthConfig(
        "",
        Auth0PasswordlessConnectionKind.Email,
        Auth0PasswordlessSendKind.Code,
        None
      )
    }

    "write the default config" in {
      format.writes(Auth0PasswordlessAuthConfig.default) mustBe Json.obj(
        "ref"        -> "",
        "connection" -> "email",
        "send"       -> "code",
        "audience"   -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "ref"        -> "auth_mod_auth0",
        "connection" -> "sms",
        "send"       -> "link",
        "audience"   -> "https://api.oto.tools"
      )
    }

    "round trip the default config" in {
      roundTrip(format, Auth0PasswordlessAuthConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an email connection" in {
      val back = roundTrip(format, full.copy(connection = Auth0PasswordlessConnectionKind.Email))
      back.connection mustBe Auth0PasswordlessConnectionKind.Email
    }

    "round trip an sms connection" in {
      val back = roundTrip(format, Auth0PasswordlessAuthConfig.default.copy(connection = Auth0PasswordlessConnectionKind.Sms))
      back.connection mustBe Auth0PasswordlessConnectionKind.Sms
    }

    "round trip a code sending" in {
      roundTrip(format, full.copy(send = Auth0PasswordlessSendKind.Code)).send mustBe Auth0PasswordlessSendKind.Code
    }

    "round trip a link sending" in {
      val back = roundTrip(format, Auth0PasswordlessAuthConfig.default.copy(send = Auth0PasswordlessSendKind.Link))
      back.send mustBe Auth0PasswordlessSendKind.Link
    }

    "round trip a config without audience" in {
      roundTrip(format, full.copy(audience = None)).audience mustBe None
    }

    "read the auth module ref alone and default the other fields" in {
      readOk(format, Json.obj("ref" -> "auth_mod_auth0")) mustBe Auth0PasswordlessAuthConfig.default.copy(
        ref = "auth_mod_auth0"
      )
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("auth_mod_auth0"))
    }

    "not read a json array" in {
      readError(format, Json.arr("auth_mod_auth0"))
    }

    "not read a config without ref" in {
      readError(format, Json.obj("connection" -> "sms", "send" -> "link", "audience" -> "foo"))
    }

    "not read a null ref" in {
      readError(format, Json.obj("ref" -> JsNull))
    }

    "not read a ref that is not a string" in {
      readError(format, Json.obj("ref" -> 42))
    }

    "read the connection without caring about its case" in {
      readOk(format, Json.obj("ref" -> "r", "connection" -> "EMAIL")).connection mustBe Auth0PasswordlessConnectionKind.Email
      readOk(format, Json.obj("ref" -> "r", "connection" -> "Sms")).connection mustBe Auth0PasswordlessConnectionKind.Sms
    }

    "read any unknown connection as an sms connection" in {
      readOk(format, Json.obj("ref" -> "r", "connection" -> "whatsapp")).connection mustBe Auth0PasswordlessConnectionKind.Sms
    }

    "read a connection that is not a string as an email connection" in {
      readOk(format, Json.obj("ref" -> "r", "connection" -> 1)).connection mustBe Auth0PasswordlessConnectionKind.Email
    }

    "read the sending without caring about its case" in {
      readOk(format, Json.obj("ref" -> "r", "send" -> "LINK")).send mustBe Auth0PasswordlessSendKind.Link
      readOk(format, Json.obj("ref" -> "r", "send" -> "Code")).send mustBe Auth0PasswordlessSendKind.Code
    }

    "read any unknown sending as a code sending" in {
      readOk(format, Json.obj("ref" -> "r", "send" -> "magic")).send mustBe Auth0PasswordlessSendKind.Code
    }

    "read a sending that is not a string as a code sending" in {
      readOk(format, Json.obj("ref" -> "r", "send" -> true)).send mustBe Auth0PasswordlessSendKind.Code
    }

    "read a null audience as no audience" in {
      readOk(format, Json.obj("ref" -> "r", "audience" -> JsNull)).audience mustBe None
    }

    "ignore an audience that is not a string" in {
      readOk(format, Json.obj("ref" -> "r", "audience" -> Json.arr("a", "b"))).audience mustBe None
    }

    "keep an empty audience" in {
      readOk(format, Json.obj("ref" -> "r", "audience" -> "")).audience mustBe Some("")
    }

    "name the connection kinds and the send kinds as written in the json" in {
      Auth0PasswordlessConnectionKind.Email.name mustBe "email"
      Auth0PasswordlessConnectionKind.Sms.name mustBe "sms"
      Auth0PasswordlessSendKind.Link.name mustBe "link"
      Auth0PasswordlessSendKind.Code.name mustBe "code"
    }

    "parse the connection kinds and the send kinds from their names" in {
      Auth0PasswordlessConnectionKind("email") mustBe Auth0PasswordlessConnectionKind.Email
      Auth0PasswordlessConnectionKind("sms") mustBe Auth0PasswordlessConnectionKind.Sms
      Auth0PasswordlessConnectionKind("") mustBe Auth0PasswordlessConnectionKind.Sms
      Auth0PasswordlessSendKind("link") mustBe Auth0PasswordlessSendKind.Link
      Auth0PasswordlessSendKind("code") mustBe Auth0PasswordlessSendKind.Code
      Auth0PasswordlessSendKind("") mustBe Auth0PasswordlessSendKind.Code
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, Auth0PasswordlessAuthConfig.default)
    }

    "list every field in its config flow and schema" in {
      Auth0PasswordlessAuthConfig.configFlow mustBe Seq("ref", "connection", "send", "audience")
      Auth0PasswordlessAuthConfig.configSchema.get.keys mustBe Auth0PasswordlessAuthConfig.configFlow.toSet
    }

    "offer the json values of the kinds as options of its config schema" in {
      val schema = Auth0PasswordlessAuthConfig.configSchema.get
      (schema \ "connection" \ "props" \ "options").as[Seq[JsObject]].map(o => (o \ "value").as[String]) mustBe Seq(
        "email",
        "sms"
      )
      (schema \ "send" \ "props" \ "options").as[Seq[JsObject]].map(o => (o \ "value").as[String]) mustBe Seq(
        "code",
        "link"
      )
    }

    "be the default config of Auth0PasswordlessStartFlowEndpoint" in {
      defaultConfigReadsBack(new Auth0PasswordlessStartFlowEndpoint(), format) mustBe Auth0PasswordlessAuthConfig.default
    }

    "be the default config of Auth0PasswordlessEndFlowEndpoint" in {
      defaultConfigReadsBack(new Auth0PasswordlessEndFlowEndpoint(), format) mustBe Auth0PasswordlessAuthConfig.default
    }

    "be the default config of Auth0PasswordlessStartEndFlowEndpoints" in {
      defaultConfigReadsBack(
        new Auth0PasswordlessStartEndFlowEndpoints(),
        format
      ) mustBe Auth0PasswordlessAuthConfig.default
    }

    "be the default config of Auth0PasswordlessFlow" in {
      defaultConfigReadsBack(new Auth0PasswordlessFlow(), format) mustBe Auth0PasswordlessAuthConfig.default
    }
  }
}
