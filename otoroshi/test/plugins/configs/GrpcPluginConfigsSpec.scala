package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class GrpcPluginConfigsSpec extends PluginConfigFormatSpec {

  "GrpcWebConfig" should {

    val format = GrpcWebConfig.fmt

    val full = GrpcWebConfig(
      allowServices = Seq("helloworld.Greeter", "Health"),
      allowMethods = Seq("SayHello", "Check"),
      blockedMethods = Seq("Delete")
    )

    "write the default config as three empty lists" in {
      format.writes(GrpcWebConfig()) mustBe Json.obj(
        "allow_services"  -> Json.arr(),
        "allow_methods"   -> Json.arr(),
        "blocked_methods" -> Json.arr()
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "allow_services"  -> Json.arr("helloworld.Greeter", "Health"),
        "allow_methods"   -> Json.arr("SayHello", "Check"),
        "blocked_methods" -> Json.arr("Delete")
      )
    }

    "round trip the default config" in {
      roundTrip(format, GrpcWebConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip allowed services alone" in {
      roundTrip(format, GrpcWebConfig(allowServices = Seq("helloworld.Greeter")))
    }

    "round trip allowed methods alone" in {
      roundTrip(format, GrpcWebConfig(allowMethods = Seq("SayHello")))
    }

    "round trip blocked methods alone" in {
      roundTrip(format, GrpcWebConfig(blockedMethods = Seq("Delete", "Drop")))
    }

    "keep the order of the lists" in {
      roundTrip(format, GrpcWebConfig(allowMethods = Seq("C", "A", "B"))).allowMethods mustBe Seq("C", "A", "B")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe GrpcWebConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe GrpcWebConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("helloworld.Greeter")) mustBe GrpcWebConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("helloworld.Greeter")) mustBe GrpcWebConfig()
    }

    "read blocked_methods alone" in {
      readOk(format, Json.obj("blocked_methods" -> Json.arr("Delete"))) mustBe GrpcWebConfig(
        blockedMethods = Seq("Delete")
      )
    }

    "ignore allow_services when it is a string" in {
      readOk(format, Json.obj("allow_services" -> "helloworld.Greeter")) mustBe GrpcWebConfig()
    }

    "ignore allow_methods when it holds something else than strings" in {
      readOk(
        format,
        Json.obj("allow_methods" -> Json.arr("SayHello", 42), "blocked_methods" -> Json.arr("Delete"))
      ) mustBe GrpcWebConfig(blockedMethods = Seq("Delete"))
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "allowServices"  -> Json.arr("helloworld.Greeter"),
        "allowMethods"   -> Json.arr("SayHello"),
        "blockedMethods" -> Json.arr("Delete")
      )
      readOk(format, json) mustBe GrpcWebConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("allow_methods" -> Json.arr("SayHello"), "foo" -> "bar")) mustBe GrpcWebConfig(
        allowMethods = Seq("SayHello")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of GrpcWebProxyPlugin" in {
      defaultConfigReadsBack(new GrpcWebProxyPlugin(), format) mustBe GrpcWebConfig()
    }
  }

  "GrpcWebConfig.validateGrpcPath" should {

    import GrpcWebConfig.validateGrpcPath

    "accept any service and method with the default config" in {
      validateGrpcPath("/helloworld.Greeter/SayHello", GrpcWebConfig()) mustBe true
    }

    "reject a path without method" in {
      validateGrpcPath("/helloworld.Greeter", GrpcWebConfig()) mustBe false
      validateGrpcPath("/", GrpcWebConfig()) mustBe false
      validateGrpcPath("", GrpcWebConfig()) mustBe false
    }

    "ignore empty segments and the segments after the method" in {
      validateGrpcPath("//helloworld.Greeter//SayHello/extra", GrpcWebConfig(allowMethods = Seq("SayHello"))) mustBe true
    }

    "reject a blocked method" in {
      validateGrpcPath("/helloworld.Greeter/Delete", GrpcWebConfig(blockedMethods = Seq("Delete"))) mustBe false
    }

    "reject a method both allowed and blocked" in {
      val config = GrpcWebConfig(allowMethods = Seq("SayHello"), blockedMethods = Seq("SayHello"))
      validateGrpcPath("/helloworld.Greeter/SayHello", config) mustBe false
    }

    "allow a service by its full name" in {
      validateGrpcPath("/helloworld.Greeter/SayHello", GrpcWebConfig(allowServices = Seq("helloworld.Greeter"))) mustBe true
    }

    "allow a service by its short name" in {
      validateGrpcPath("/helloworld.Greeter/SayHello", GrpcWebConfig(allowServices = Seq("Greeter"))) mustBe true
    }

    "allow the services of an allowed package" in {
      validateGrpcPath("/helloworld.v1.Greeter/SayHello", GrpcWebConfig(allowServices = Seq("helloworld"))) mustBe true
    }

    "not allow a service through a partial package name" in {
      validateGrpcPath("/helloworld.Greeter/SayHello", GrpcWebConfig(allowServices = Seq("hello"))) mustBe false
    }

    "reject a service that is not allowed" in {
      validateGrpcPath("/helloworld.Greeter/SayHello", GrpcWebConfig(allowServices = Seq("other.Service"))) mustBe false
    }

    "allow a service without package" in {
      validateGrpcPath("/Greeter/SayHello", GrpcWebConfig(allowServices = Seq("Greeter"))) mustBe true
    }

    "reject a method that is not allowed" in {
      validateGrpcPath("/helloworld.Greeter/SayGoodbye", GrpcWebConfig(allowMethods = Seq("SayHello"))) mustBe false
    }

    "match the methods with their case" in {
      validateGrpcPath("/helloworld.Greeter/sayhello", GrpcWebConfig(allowMethods = Seq("SayHello"))) mustBe false
      validateGrpcPath("/helloworld.Greeter/delete", GrpcWebConfig(blockedMethods = Seq("Delete"))) mustBe true
    }

    "require both the service and the method to be allowed" in {
      val config = GrpcWebConfig(allowServices = Seq("Greeter"), allowMethods = Seq("SayHello"))
      validateGrpcPath("/helloworld.Greeter/SayHello", config) mustBe true
      validateGrpcPath("/helloworld.Other/SayHello", config) mustBe false
      validateGrpcPath("/helloworld.Greeter/SayGoodbye", config) mustBe false
    }
  }
}
