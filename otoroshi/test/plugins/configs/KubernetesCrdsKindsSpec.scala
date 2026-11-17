package plugins.configs

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.plugins.jobs.kubernetes.{KubernetesCrdsKinds, KubernetesCrdsKindsRule}
import play.api.libs.json.*

// the kinds of entities (crds) the kubernetes job reads, globally and by namespace (#2929)
class KubernetesCrdsKindsSpec extends AnyWordSpec with Matchers {

  def read(crdsKinds: JsValue): KubernetesCrdsKinds = KubernetesCrdsKinds.read(Json.obj("crdsKinds" -> crdsKinds))

  "KubernetesCrdsKindsRule" should {

    "allow every kind by default" in {
      KubernetesCrdsKindsRule.everything.allows("routes") mustBe true
      KubernetesCrdsKindsRule.everything.allows("global-configs") mustBe true
    }

    "allow the included kinds that are not excluded" in {
      val rule = KubernetesCrdsKindsRule(include = Seq("routes", "backends"), exclude = Seq("backends"))
      rule.allows("routes") mustBe true
      rule.allows("backends") mustBe false
      rule.allows("apikeys") mustBe false
    }

    "match patterns of plural names whatever their case" in {
      val rule = KubernetesCrdsKindsRule(include = Seq("*-plugins", "Routes"))
      rule.allows("wasm-plugins") mustBe true
      rule.allows("ROUTES") mustBe true
      rule.allows("route-compositions") mustBe false
    }

    "allow nothing without included kinds" in {
      KubernetesCrdsKindsRule.nothing.allows("routes") mustBe false
    }
  }

  "KubernetesCrdsKinds" should {

    "read every kind, in every namespace, without crdsKinds" in {
      val kinds = KubernetesCrdsKinds.read(Json.obj())
      kinds mustBe KubernetesCrdsKinds.everything
      kinds.readsKind("admins") mustBe true
      kinds.reads("admins", Some("team-a")) mustBe true
      KubernetesCrdsKinds.read(Json.obj("crdsKinds" -> JsNull)) mustBe KubernetesCrdsKinds.everything
    }

    "read every kind but the excluded ones" in {
      val kinds = read(Json.obj("exclude" -> Json.arr("admins", "global-configs")))
      kinds.readsKind("routes") mustBe true
      kinds.readsKind("admins") mustBe false
      kinds.reads("global-configs", Some("team-a")) mustBe false
    }

    "only narrow the global rule with the rules of the namespaces" in {
      val kinds = read(
        Json.obj(
          "exclude"    -> Json.arr("admins"),
          "namespaces" -> Json.obj(
            "team-*"       -> Json.obj("include" -> Json.arr("routes", "backends", "admins")),
            "team-sandbox" -> Json.obj("exclude" -> Json.arr("backends"))
          )
        )
      )
      // a namespace without rule: the global one only
      kinds.reads("apikeys", Some("other")) mustBe true
      kinds.reads("admins", Some("other")) mustBe false
      // a namespace matching one pattern
      kinds.reads("routes", Some("team-a")) mustBe true
      kinds.reads("apikeys", Some("team-a")) mustBe false
      // the namespace cannot read what the global rule excludes
      kinds.reads("admins", Some("team-a")) mustBe false
      // a namespace matching several patterns gets all their rules
      kinds.reads("routes", Some("team-sandbox")) mustBe true
      kinds.reads("backends", Some("team-sandbox")) mustBe false
      // without namespace, the global rule only
      kinds.reads("apikeys", None) mustBe true
      kinds.readsKind("routes") mustBe true
      kinds.readsKind("admins") mustBe false
    }

    "accept a single kind written as a string" in {
      val kinds = read(Json.obj("include" -> "routes"))
      kinds.readsKind("routes") mustBe true
      kinds.readsKind("backends") mustBe false
    }

    "read nothing when it cannot be read" in {
      val kinds = read(Json.obj("include" -> 42))
      kinds.readsKind("routes") mustBe false
      read(Json.obj("namespaces" -> Json.obj("team-a" -> Json.obj("exclude" -> Json.obj())))).readsKind("routes") mustBe false
    }

    "read nothing when it is not written the expected way" in {
      val nothing = KubernetesCrdsKinds(global = KubernetesCrdsKindsRule.nothing)
      // not an object
      read(JsString("routes")) mustBe nothing
      read(JsNumber(42)) mustBe nothing
      read(Json.arr("routes")) mustBe nothing
      // unknown fields
      read(Json.obj("excludes" -> Json.arr("admins"))) mustBe nothing
      read(Json.obj("namespaces" -> Json.obj("team-a" -> Json.obj("included" -> Json.arr("routes"))))) mustBe nothing
      // lists that do not only hold strings
      read(Json.obj("exclude" -> Json.arr(42))) mustBe nothing
      read(Json.obj("exclude" -> Json.arr(Json.arr("admins")))) mustBe nothing
      // namespaces that are not an object of rules
      read(Json.obj("namespaces" -> Json.arr("team-a"))) mustBe nothing
      read(Json.obj("namespaces" -> Json.obj("team-a" -> Json.arr("routes")))) mustBe nothing
      read(Json.obj("namespaces" -> Json.obj("team-a" -> "routes"))) mustBe nothing
    }

    "handle the admins under both names of their crds" in {
      // the job fetches the admins as `simple-admin-users`
      val excluded = read(Json.obj("exclude" -> Json.arr("admins")))
      excluded.readsKind("admins") mustBe false
      excluded.readsKind("simple-admin-users") mustBe false
      excluded.reads("simple-admin-users", Some("team-a")) mustBe false
      val excludedByCrdName = read(Json.obj("exclude" -> Json.arr("simple-admin-users")))
      excludedByCrdName.readsKind("admins") mustBe false
      excludedByCrdName.readsKind("simple-admin-users") mustBe false
      val included = read(Json.obj("include" -> Json.arr("routes", "admins")))
      included.readsKind("simple-admin-users") mustBe true
      included.readsKind("admins") mustBe true
      included.readsKind("apikeys") mustBe false
      val byNamespace = read(Json.obj("namespaces" -> Json.obj("team-*" -> Json.obj("exclude" -> Json.arr("admins")))))
      byNamespace.reads("simple-admin-users", Some("team-a")) mustBe false
      byNamespace.reads("simple-admin-users", Some("other")) mustBe true
    }

    "write the rules it read" in {
      val kinds = read(
        Json.obj(
          "include"    -> Json.arr("*"),
          "exclude"    -> Json.arr("admins"),
          "namespaces" -> Json.obj("team-a" -> Json.obj("include" -> Json.arr("routes"), "exclude" -> Json.arr()))
        )
      )
      KubernetesCrdsKinds.read(Json.obj("crdsKinds" -> kinds.json)) mustBe kinds
    }
  }
}
