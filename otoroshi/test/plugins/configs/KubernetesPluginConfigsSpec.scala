package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class KubernetesPluginConfigsSpec extends PluginConfigFormatSpec {

  "KubernetesNamespaceScanConfig" should {

    val format = KubernetesNamespaceScanConfig.fmt

    "write the namespaces under namespaces" in {
      format.writes(KubernetesNamespaceScanConfig(Seq("default", "otoroshi"))) mustBe Json.obj(
        "namespaces" -> Json.arr("default", "otoroshi")
      )
    }

    "write the default config as an empty namespaces array" in {
      format.writes(KubernetesNamespaceScanConfig()) mustBe Json.obj("namespaces" -> Json.arr())
    }

    "scan no namespace by default" in {
      KubernetesNamespaceScanConfig().namespaces mustBe empty
    }

    "round trip the default config" in {
      roundTrip(format, KubernetesNamespaceScanConfig())
    }

    "round trip a single namespace" in {
      roundTrip(format, KubernetesNamespaceScanConfig(Seq("kube-system")))
    }

    "round trip several namespaces and keep their order" in {
      roundTrip(format, KubernetesNamespaceScanConfig(Seq("c", "a", "b"))).namespaces mustBe Seq("c", "a", "b")
    }

    "keep duplicated namespaces" in {
      roundTrip(format, KubernetesNamespaceScanConfig(Seq("ns", "ns"))).namespaces mustBe Seq("ns", "ns")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe KubernetesNamespaceScanConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe KubernetesNamespaceScanConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("default")) mustBe KubernetesNamespaceScanConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("default")) mustBe KubernetesNamespaceScanConfig()
    }

    "ignore namespaces written as a string" in {
      readOk(format, Json.obj("namespaces" -> "default")) mustBe KubernetesNamespaceScanConfig()
    }

    "ignore namespaces holding something else than strings" in {
      readOk(format, Json.obj("namespaces" -> Json.arr("default", 1))) mustBe KubernetesNamespaceScanConfig()
    }

    "not read a singular namespace key" in {
      readOk(format, Json.obj("namespace" -> Json.arr("default"))) mustBe KubernetesNamespaceScanConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("namespaces" -> Json.arr("default"), "foo" -> "bar")) mustBe KubernetesNamespaceScanConfig(
        Seq("default")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, KubernetesNamespaceScanConfig(Seq("default")))
    }

    "be the default config of KubernetesNamespaceScanBackend" in {
      defaultConfigReadsBack(new KubernetesNamespaceScanBackend(), format) mustBe KubernetesNamespaceScanConfig()
    }
  }
}
