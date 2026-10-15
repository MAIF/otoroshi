package entities

import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.next.models.{
  ApiRef,
  NgPluginInstance,
  NgPluginInstanceConfig,
  NgPlugins,
  NgPluginsWithOverride,
  NgRoute,
  PluginIndex,
  RouteApiRef
}
import otoroshi.next.plugins.{
  AllowedQuota,
  DistributedRedisThrottlingStrategyConfig,
  FixedWindowStrategyConfig,
  LegacyThrottlingStrategyConfig,
  LocalTokensBucketStrategyConfig,
  LuaDistributedRedisThrottlingStrategyConfig
}
import play.api.libs.json.*

class ApikeyEntityFormatSpec extends EntityFormatSpec {

  /** 2100-01-01, a validity date that is still ahead */
  val future: DateTime = new DateTime(4102444800000L)

  /** 2000-01-01, a validity date that is over */
  val past: DateTime = new DateTime(946684800000L)

  val rightsJson: String = """[{"tenant":"tenant-1:r","teams":["team-1:r"]}]"""

  val plugin: NgPluginInstance = NgPluginInstance(
    plugin = "cp:otoroshi.next.plugins.OverrideHost",
    enabled = false,
    debug = true,
    include = Seq("/api/.*"),
    exclude = Seq("/api/private/.*"),
    config = NgPluginInstanceConfig(Json.obj("host" -> "example.com")),
    pluginIndex = Some(PluginIndex(validateAccess = Some(1.0))),
    boundListeners = Seq("listener-1")
  )

  val minimal: ApiKey = ApiKey(
    clientId = "client-1",
    clientSecret = "secret-1",
    clientName = "Client 1",
    authorizedEntities = Seq.empty
  )

  val full: ApiKey = ApiKey(
    clientId = "client-1",
    clientSecret = "secret-1",
    clientName = "Client 1",
    description = "the first apikey",
    authorizedEntities = Seq(
      RouteIdentifier("route-1"),
      ServiceGroupIdentifier("group-1"),
      ApiIdentifier("api-1"),
      RouteCompositionIdentifier("composition-1"),
      ServiceDescriptorIdentifier("service-1")
    ),
    enabled = false,
    readOnly = true,
    allowClientIdOnly = true,
    throttlingStrategy = Some(FixedWindowStrategyConfig(Some("bucket-1"), 5000L, AllowedQuota(10, 100, 1000))),
    throttlingQuota = 10,
    dailyQuota = 100,
    monthlyQuota = 1000,
    constrainedServicesOnly = true,
    restrictions = Restrictions(
      enabled = true,
      allowLast = false,
      allowed = Seq(RestrictionPath("GET", "/apkspec/api/.*")),
      forbidden = Seq(RestrictionPath("DELETE", "/apkspec/.*", Some(RouteIdentifier("route-1")))),
      notFound = Seq(RestrictionPath("*", "/apkspec/admin/.*", Some(ServiceGroupIdentifier("group-1"))))
    ),
    validUntil = Some(future),
    rotation = ApiKeyRotation(enabled = true, rotationEvery = 24, gracePeriod = 2, nextSecret = Some("secret-2")),
    apiRef = Some(ApiRef("api-1", "plan-1", "subscription-1")),
    plugins = Some(NgPluginsWithOverride(NgPlugins(Seq(plugin)), overrides = true)),
    tags = Seq("prod", "eu"),
    metadata = Map("owner" -> "ops", ApiKey.accessRightsMetadataKey -> rightsJson),
    location = customLocation
  )

  /** the smallest json the format reads */
  val minimalJson: JsObject = Json.obj("clientId" -> "client-1", "clientSecret" -> "secret-1", "clientName" -> "Client 1")

  "EntityIdentifier" should {

    "read each kind of prefixed id" in {
      EntityIdentifier("group_g1") mustBe Some(ServiceGroupIdentifier("g1"))
      EntityIdentifier("service_s1") mustBe Some(ServiceDescriptorIdentifier("s1"))
      EntityIdentifier("route_r1") mustBe Some(RouteIdentifier("r1"))
      EntityIdentifier("api_a1") mustBe Some(ApiIdentifier("a1"))
      EntityIdentifier("route-composition_c1") mustBe Some(RouteCompositionIdentifier("c1"))
    }

    "not mistake a route composition for a route" in {
      EntityIdentifier("route-composition_route_1") mustBe Some(RouteCompositionIdentifier("route_1"))
    }

    "read an id without known prefix as a group" in {
      EntityIdentifier("g1") mustBe Some(ServiceGroupIdentifier("g1"))
      EntityIdentifier("plan_p1") mustBe Some(ServiceGroupIdentifier("plan_p1"))
    }

    "strip only the leading prefix" in {
      EntityIdentifier("route_my_route_1") mustBe Some(RouteIdentifier("my_route_1"))
      EntityIdentifier("group_route_1") mustBe Some(ServiceGroupIdentifier("route_1"))
    }

    "write a prefixed id" in {
      RouteIdentifier("r1").str mustBe "route_r1"
      ServiceGroupIdentifier("g1").json mustBe JsString("group_g1")
      RouteCompositionIdentifier("c1").json mustBe JsString("route-composition_c1")
    }

    "write a kind and an id" in {
      ApiIdentifier("a1").modernJson mustBe Json.obj("kind" -> "api", "id" -> "a1")
      ServiceDescriptorIdentifier("s1").modernJson mustBe Json.obj("kind" -> "service", "id" -> "s1")
    }

    "read each kind of kind and id object" in {
      EntityIdentifier.applyModern(Json.obj("kind" -> "group", "id" -> "g1")) mustBe Some(ServiceGroupIdentifier("g1"))
      EntityIdentifier.applyModern(Json.obj("kind" -> "service", "id" -> "s1")) mustBe Some(ServiceDescriptorIdentifier("s1"))
      EntityIdentifier.applyModern(Json.obj("kind" -> "route", "id" -> "r1")) mustBe Some(RouteIdentifier("r1"))
      EntityIdentifier.applyModern(Json.obj("kind" -> "api", "id" -> "a1")) mustBe Some(ApiIdentifier("a1"))
      EntityIdentifier.applyModern(Json.obj("kind" -> "route-composition", "id" -> "c1")) mustBe
        Some(RouteCompositionIdentifier("c1"))
    }

    "read an object of unknown kind or without kind as a group" in {
      EntityIdentifier.applyModern(Json.obj("kind" -> "plan", "id" -> "p1")) mustBe Some(ServiceGroupIdentifier("p1"))
      EntityIdentifier.applyModern(Json.obj("id" -> "x1")) mustBe Some(ServiceGroupIdentifier("x1"))
    }

    "fail on an object without id" in {
      a[Throwable] must be thrownBy EntityIdentifier.applyModern(Json.obj("kind" -> "route"))
    }

    "read back what it writes, prefixed or as an object" in {
      val all = Seq(
        ServiceGroupIdentifier("g1"),
        ServiceDescriptorIdentifier("s1"),
        RouteIdentifier("r1"),
        ApiIdentifier("a1"),
        RouteCompositionIdentifier("c1")
      )
      all.map(id => EntityIdentifier(id.str)) mustBe all.map(Some(_))
      all.map(id => EntityIdentifier.applyModern(id.modernJson.as[JsObject])) mustBe all.map(Some(_))
    }

    "tell its kind" in {
      ServiceGroupIdentifier("x").isGroup mustBe true
      ServiceDescriptorIdentifier("x").isService mustBe true
      RouteIdentifier("x").isRoute mustBe true
      ApiIdentifier("x").isApi mustBe true
      RouteCompositionIdentifier("x").isRouteComposition mustBe true
      RouteIdentifier("x").isGroup mustBe false
      ServiceGroupIdentifier("x").isRoute mustBe false
    }

    "not be equal to an identifier of another kind with the same id" in {
      RouteIdentifier("x") must not be ServiceGroupIdentifier("x")
    }
  }

  "RemainingQuotas" should {

    val format = RemainingQuotas.fmt

    val custom = RemainingQuotas(10, 3, 7, 100, 30, 70, 1000, 300, 700)

    "be unlimited by default" in {
      RemainingQuotas.MaxValue mustBe 10000000L
      RemainingQuotas() mustBe RemainingQuotas(
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue,
        RemainingQuotas.MaxValue
      )
    }

    "write the window, daily and monthly counters" in {
      format.writes(custom) mustBe Json.obj(
        "authorizedCallsPerWindow" -> 10,
        "throttlingCallsPerWindow" -> 3,
        "remainingCallsPerWindow"  -> 7,
        "authorizedCallsPerDay"    -> 100,
        "currentCallsPerDay"       -> 30,
        "remainingCallsPerDay"     -> 70,
        "authorizedCallsPerMonth"  -> 1000,
        "currentCallsPerMonth"     -> 300,
        "remainingCallsPerMonth"   -> 700
      )
    }

    "round trip the default quotas" in {
      roundTrip(format, RemainingQuotas())
    }

    "round trip custom quotas" in {
      roundTrip(format, custom)
    }

    "read the legacy per second keys of the window" in {
      val legacy = format.writes(custom).as[JsObject] - "authorizedCallsPerWindow" - "throttlingCallsPerWindow" ++
        Json.obj("authorizedCallsPerSec" -> 20, "currentCallsPerSec" -> 5)
      val back   = readOk(format, legacy)
      back.authorizedCallsPerWindow mustBe 20
      back.throttlingCallsPerWindow mustBe 5
    }

    "read the remaining calls of the window from the legacy remaining per second key" in {
      val legacy = format.writes(custom).as[JsObject] - "authorizedCallsPerWindow" - "throttlingCallsPerWindow" -
        "remainingCallsPerWindow" ++ Json.obj("authorizedCallsPerSec" -> 20, "currentCallsPerSec" -> 5, "remainingCallsPerSec" -> 15)
      readOk(format, legacy).remainingCallsPerWindow mustBe 15
    }

    "prefer the window keys over the legacy per second keys" in {
      val both = format.writes(custom).as[JsObject] ++ Json.obj("authorizedCallsPerSec" -> 20, "currentCallsPerSec" -> 5)
      readOk(format, both) mustBe custom
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read quotas without the daily counters" in {
      readError(format, format.writes(custom).as[JsObject] - "currentCallsPerDay")
    }

    "not read a counter that is not a number" in {
      readError(format, format.writes(custom).as[JsObject] ++ Json.obj("remainingCallsPerMonth" -> "700"))
    }

    "expose through toJson what the format writes" in {
      custom.toJson mustBe format.writes(custom)
    }
  }

  "ApiKeyRotation" should {

    val format = ApiKeyRotation.fmt

    "be disabled by default, every 31 days with a 7 days grace period" in {
      ApiKeyRotation() mustBe ApiKeyRotation(enabled = false, rotationEvery = 744, gracePeriod = 168, nextSecret = None)
    }

    "write the default rotation with a null next secret" in {
      format.writes(ApiKeyRotation()) mustBe Json.obj(
        "enabled"       -> false,
        "rotationEvery" -> 744,
        "gracePeriod"   -> 168,
        "nextSecret"    -> JsNull
      )
    }

    "round trip the default rotation" in {
      roundTrip(format, ApiKeyRotation())
    }

    "round trip an enabled rotation with a next secret" in {
      roundTrip(format, ApiKeyRotation(enabled = true, rotationEvery = 24, gracePeriod = 2, nextSecret = Some("next")))
    }

    "read an empty object as the default rotation" in {
      readOk(format, Json.obj()) mustBe ApiKeyRotation()
    }

    "read a json null as the default rotation" in {
      readOk(format, JsNull) mustBe ApiKeyRotation()
    }

    "read fields of the wrong type as their defaults" in {
      readOk(
        format,
        Json.obj("enabled" -> "true", "rotationEvery" -> "24", "gracePeriod" -> true, "nextSecret" -> 42)
      ) mustBe ApiKeyRotation()
    }

    "expose through json what the format writes" in {
      val rotation = ApiKeyRotation(enabled = true, nextSecret = Some("next"))
      rotation.json mustBe format.writes(rotation)
    }

    "write a rotation info with its date" in {
      ApiKeyRotationInfo(future, 42L).json mustBe Json.obj("remaining" -> 42, "rotation_at" -> future.toString())
    }
  }

  "RestrictionPath" should {

    val format = RestrictionPath.format

    "write a path without entity with a null entity" in {
      format.writes(RestrictionPath("GET", "/api/.*")) mustBe Json.obj(
        "method"            -> "GET",
        "path"              -> "/api/.*",
        "authorized_entity" -> JsNull
      )
    }

    "write the entity of a path as a kind and an id" in {
      (format.writes(RestrictionPath("GET", "/api/.*", Some(ApiIdentifier("api-1")))) \ "authorized_entity").get mustBe
        Json.obj("kind" -> "api", "id" -> "api-1")
    }

    "round trip a path scoped to each kind of entity" in {
      roundTrip(format, RestrictionPath("GET", "/a", Some(ServiceGroupIdentifier("g1"))))
      roundTrip(format, RestrictionPath("GET", "/a", Some(ServiceDescriptorIdentifier("s1"))))
      roundTrip(format, RestrictionPath("GET", "/a", Some(RouteIdentifier("r1"))))
      roundTrip(format, RestrictionPath("GET", "/a", Some(ApiIdentifier("a1"))))
      roundTrip(format, RestrictionPath("GET", "/a", Some(RouteCompositionIdentifier("c1"))))
    }

    "round trip a path without entity" in {
      roundTrip(format, RestrictionPath("*", "/.*")).authorizedEntity mustBe None
    }

    "not read a path without method" in {
      readError(format, Json.obj("path" -> "/a"))
    }

    "not read a path without path" in {
      readError(format, Json.obj("method" -> "GET"))
    }

    "ignore an entity given as a prefixed string" in {
      readOk(format, Json.obj("method" -> "GET", "path" -> "/a", "authorized_entity" -> "route_r1")).authorizedEntity mustBe
        None
    }

    "read an entity of unknown kind as a group" in {
      readOk(
        format,
        Json.obj("method" -> "GET", "path" -> "/a", "authorized_entity" -> Json.obj("kind" -> "plan", "id" -> "p1"))
      ).authorizedEntity mustBe Some(ServiceGroupIdentifier("p1"))
    }

    "not read an entity without id" in {
      readError(format, Json.obj("method" -> "GET", "path" -> "/a", "authorized_entity" -> Json.obj("kind" -> "route")))
    }
  }

  "Restrictions" should {

    val format = Restrictions.format

    val route = NgRoute.empty.copy(id = "route-1", groups = Seq("group-1"), apiRef = Some(RouteApiRef("api-1")))

    "be disabled by default, the allowed paths coming last" in {
      Restrictions() mustBe Restrictions(enabled = false, allowLast = true, Seq.empty, Seq.empty, Seq.empty)
    }

    "write the default restrictions" in {
      format.writes(Restrictions()) mustBe Json.obj(
        "enabled"   -> false,
        "allowLast" -> true,
        "allowed"   -> Json.arr(),
        "forbidden" -> Json.arr(),
        "notFound"  -> Json.arr()
      )
    }

    "round trip the default restrictions" in {
      roundTrip(format, Restrictions())
    }

    "round trip restrictions where every field is set" in {
      roundTrip(format, full.restrictions)
    }

    "keep the order of the paths" in {
      val paths = Seq(RestrictionPath("GET", "/b"), RestrictionPath("GET", "/a"), RestrictionPath("POST", "/c"))
      roundTrip(format, Restrictions(enabled = true, forbidden = paths)).forbidden mustBe paths
    }

    "read an empty object as the default restrictions" in {
      readOk(format, Json.obj()) mustBe Restrictions()
    }

    "read a json null as the default restrictions" in {
      readOk(format, JsNull) mustBe Restrictions()
    }

    "skip the paths that cannot be read" in {
      readOk(
        format,
        Json.obj(
          "enabled" -> true,
          "allowed" -> Json.arr(
            Json.obj("method" -> "GET"),
            Json.obj("method" -> "GET", "path" -> "/a"),
            Json.obj("method" -> "GET", "path" -> "/b", "authorized_entity" -> Json.obj("kind" -> "route")),
            "GET /c"
          )
        )
      ).allowed mustBe Seq(RestrictionPath("GET", "/a"))
    }

    "read fields of the wrong type as their defaults" in {
      readOk(
        format,
        Json.obj("enabled" -> "true", "allowLast" -> "false", "forbidden" -> Json.obj("method" -> "GET", "path" -> "/a"))
      ) mustBe Restrictions()
    }

    "never match anything while disabled" in {
      val restrictions = Restrictions(
        enabled = false,
        allowed = Seq(RestrictionPath("*", "/.*")),
        forbidden = Seq(RestrictionPath("*", "/.*")),
        notFound = Seq(RestrictionPath("*", "/.*"))
      )
      restrictions.isAllowed("GET", "a.oto.tools", "/x", route, None) mustBe false
      restrictions.isNotAllowed("GET", "a.oto.tools", "/x", route, None) mustBe false
      restrictions.isForbidden("GET", "a.oto.tools", "/x", route, None) mustBe false
      restrictions.isNotFound("GET", "a.oto.tools", "/x", route, None) mustBe false
    }

    "match the method, whatever its case, and the path as a regex" in {
      val restrictions = Restrictions(enabled = true, forbidden = Seq(RestrictionPath(" Delete ", "/apkspec/users/.*")))
      restrictions.isForbidden("DELETE", "a.oto.tools", "/apkspec/users/1", route, None) mustBe true
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/users/1", route, None) mustBe false
      restrictions.isForbidden("DELETE", "a.oto.tools", "/apkspec/groups/1", route, None) mustBe false
    }

    "match any method with a wildcard" in {
      val restrictions = Restrictions(enabled = true, notFound = Seq(RestrictionPath("*", "/apkspec/hidden")))
      restrictions.isNotFound("PATCH", "a.oto.tools", "/apkspec/hidden", route, None) mustBe true
    }

    "match a path that does not start with a slash against the domain and the path" in {
      val restrictions = Restrictions(enabled = true, forbidden = Seq(RestrictionPath("GET", "apkspec\\.oto\\.tools/admin/.*")))
      restrictions.isForbidden("GET", "apkspec.oto.tools", "/admin/x", route, None) mustBe true
      restrictions.isForbidden("GET", "other.oto.tools", "/admin/x", route, None) mustBe false
    }

    "allow only the allowed paths" in {
      val restrictions = Restrictions(enabled = true, allowed = Seq(RestrictionPath("GET", "/apkspec/public/.*")))
      restrictions.isAllowed("GET", "a.oto.tools", "/apkspec/public/x", route, None) mustBe true
      restrictions.isNotAllowed("GET", "a.oto.tools", "/apkspec/public/x", route, None) mustBe false
      restrictions.isNotAllowed("GET", "a.oto.tools", "/apkspec/private/x", route, None) mustBe true
    }

    "match a path scoped to a route or a service on the id of the route only" in {
      val restrictions = Restrictions(
        enabled = true,
        forbidden = Seq(RestrictionPath("*", "/apkspec/.*", Some(RouteIdentifier("route-1")))),
        notFound = Seq(RestrictionPath("*", "/apkspec/.*", Some(ServiceDescriptorIdentifier("route-1"))))
      )
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, None) mustBe true
      restrictions.isNotFound("GET", "a.oto.tools", "/apkspec/x", route, None) mustBe true
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route.copy(id = "route-2"), None) mustBe false
    }

    "match a path scoped to a group when the route is in the group and the apikey authorized on it" in {
      val restrictions = Restrictions(
        enabled = true,
        forbidden = Seq(RestrictionPath("*", "/apkspec/.*", Some(ServiceGroupIdentifier("group-1"))))
      )
      val onGroup      = minimal.copy(authorizedEntities = Seq(ServiceGroupIdentifier("group-1")))
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, Some(onGroup)) mustBe true
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, Some(minimal)) mustBe false
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, None) mustBe false
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route.copy(groups = Seq("group-2")), Some(onGroup)) mustBe
        false
    }

    "match a path scoped to an api on the api of the route" in {
      val restrictions =
        Restrictions(enabled = true, forbidden = Seq(RestrictionPath("*", "/apkspec/.*", Some(ApiIdentifier("api-1")))))
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, None) mustBe true
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route.copy(apiRef = None), None) mustBe false
    }

    "never match a path scoped to a route composition" in {
      val restrictions = Restrictions(
        enabled = true,
        forbidden = Seq(RestrictionPath("*", "/apkspec/.*", Some(RouteCompositionIdentifier("route-1"))))
      )
      restrictions.isForbidden("GET", "a.oto.tools", "/apkspec/x", route, Some(full)) mustBe false
    }

    "expose through json what the format writes" in {
      full.restrictions.json mustBe format.writes(full.restrictions)
    }
  }

  "ApiRef and NgPluginsWithOverride" should {

    "write an api reference with the subscription under sub" in {
      ApiRef.format.writes(ApiRef("api-1", "plan-1", "subscription-1")) mustBe Json.obj(
        "api"  -> "api-1",
        "plan" -> "plan-1",
        "sub"  -> "subscription-1"
      )
    }

    "round trip an api reference" in {
      roundTrip(ApiRef.format, ApiRef("api-1", "plan-1", "subscription-1"))
    }

    "not read an api reference without subscription" in {
      readError(ApiRef.format, Json.obj("api" -> "api-1", "plan" -> "plan-1"))
    }

    "write plugins with their override flag" in {
      NgPluginsWithOverride.format.writes(NgPluginsWithOverride(NgPlugins(Seq(plugin)), overrides = true)) mustBe Json.obj(
        "plugins"   -> Json.arr(plugin.json),
        "overrides" -> true
      )
    }

    "round trip plugins with their override flag" in {
      roundTrip(NgPluginsWithOverride.format, NgPluginsWithOverride(NgPlugins(Seq(plugin)), overrides = true))
    }

    "read an empty object as no plugin that does not override" in {
      readOk(NgPluginsWithOverride.format, Json.obj()) mustBe NgPluginsWithOverride(NgPlugins.empty, overrides = false)
    }

    "not read plugins when a slot has no plugin id" in {
      readError(
        NgPluginsWithOverride.format,
        Json.obj("plugins" -> Json.arr(plugin.json, Json.obj("enabled" -> true)), "overrides" -> true)
      )
    }
  }

  "ApiKey format" should {

    val format = ApiKey._fmt

    "write a minimal apikey" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                    -> defaultLocationJson,
        "clientId"                -> "client-1",
        "clientSecret"            -> "secret-1",
        "clientName"              -> "Client 1",
        "description"             -> "",
        "authorizedGroup"         -> JsNull,
        "authorizedEntities"      -> Json.arr(),
        "authorizations"          -> Json.arr(),
        "enabled"                 -> true,
        "readOnly"                -> false,
        "allowClientIdOnly"       -> false,
        "throttlingStrategy"      -> JsNull,
        "throttlingQuota"         -> 10000000L,
        "dailyQuota"              -> 10000000L,
        "monthlyQuota"            -> 10000000L,
        "constrainedServicesOnly" -> false,
        "restrictions"            -> Restrictions.format.writes(Restrictions()),
        "rotation"                -> ApiKeyRotation.fmt.writes(ApiKeyRotation()),
        "apiRef"                  -> JsNull,
        "plugins"                 -> JsNull,
        "validUntil"              -> JsNull,
        "tags"                    -> Json.arr(),
        "metadata"                -> Json.obj()
      )
    }

    "write the same keys whatever the fields" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write the authorized entities as prefixed ids and as kinds and ids" in {
      val obj = written(format, full)
      (obj \ "authorizedEntities").get mustBe Json.arr(
        "route_route-1",
        "group_group-1",
        "api_api-1",
        "route-composition_composition-1",
        "service_service-1"
      )
      (obj \ "authorizations").get mustBe Json.arr(
        Json.obj("kind" -> "route", "id" -> "route-1"),
        Json.obj("kind" -> "group", "id" -> "group-1"),
        Json.obj("kind" -> "api", "id" -> "api-1"),
        Json.obj("kind" -> "route-composition", "id" -> "composition-1"),
        Json.obj("kind" -> "service", "id" -> "service-1")
      )
    }

    "write the first authorized group as the legacy authorizedGroup" in {
      val apikey =
        minimal.copy(authorizedEntities = Seq(RouteIdentifier("r1"), ServiceGroupIdentifier("g1"), ServiceGroupIdentifier("g2")))
      (written(format, apikey) \ "authorizedGroup").get mustBe JsString("g1")
    }

    "write a null legacy authorizedGroup when no group is authorized" in {
      (written(format, minimal.copy(authorizedEntities = Seq(RouteIdentifier("r1")))) \ "authorizedGroup").get mustBe JsNull
    }

    "write the validity date as epoch milliseconds" in {
      (written(format, full) \ "validUntil").get mustBe JsNumber(4102444800000L)
    }

    "write the throttling strategy, the api reference and the plugins with their own formats" in {
      val obj = written(format, full)
      (obj \ "throttlingStrategy").get mustBe full.throttlingStrategy.get.json
      (obj \ "apiRef").get mustBe Json.obj("api" -> "api-1", "plan" -> "plan-1", "sub" -> "subscription-1")
      (obj \ "plugins").get mustBe Json.obj("plugins" -> Json.arr(plugin.json), "overrides" -> true)
    }

    "write the enabled flag of an apikey that is still valid as is" in {
      (written(format, minimal.copy(enabled = true, validUntil = Some(future))) \ "enabled").get mustBe JsBoolean(true)
      (written(format, minimal.copy(enabled = false, validUntil = Some(future))) \ "enabled").get mustBe JsBoolean(false)
    }

    "write an expired apikey as disabled" in {
      (written(format, minimal.copy(enabled = true, validUntil = Some(past))) \ "enabled").get mustBe JsBoolean(false)
    }

    "not write metadata whose key is empty" in {
      (written(format, minimal.copy(metadata = Map("" -> "x", "a" -> "b"))) \ "metadata").get mustBe Json.obj("a" -> "b")
    }

    "round trip a minimal apikey" in {
      roundTrip(format, minimal)
    }

    "round trip an apikey where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an apikey authorized on several groups" in {
      roundTrip(format, minimal.copy(authorizedEntities = Seq(ServiceGroupIdentifier("g2"), ServiceGroupIdentifier("g1"))))
    }

    "keep the order of the authorized entities" in {
      val entities = Seq(ApiIdentifier("a1"), ServiceGroupIdentifier("g1"), RouteIdentifier("r1"))
      roundTrip(format, minimal.copy(authorizedEntities = entities)).authorizedEntities mustBe entities
    }

    "read duplicated authorized entities once" in {
      val apikey = minimal.copy(authorizedEntities = Seq(RouteIdentifier("r1"), RouteIdentifier("r1")))
      readOk(format, format.writes(apikey)).authorizedEntities mustBe Seq(RouteIdentifier("r1"))
    }

    "keep an entity of each kind sharing the same id" in {
      val entities = Seq(RouteIdentifier("x"), ServiceGroupIdentifier("x"), ApiIdentifier("x"))
      roundTrip(format, minimal.copy(authorizedEntities = entities)).authorizedEntities mustBe entities
    }

    "round trip each kind of throttling strategy" in {
      val quota = AllowedQuota(1, 2, 3)
      roundTrip(format, minimal.copy(throttlingStrategy = Some(LocalTokensBucketStrategyConfig("bucket-1", 10, 100, 5, quota))))
      roundTrip(format, minimal.copy(throttlingStrategy = Some(LegacyThrottlingStrategyConfig(quota))))
      roundTrip(format, minimal.copy(throttlingStrategy = Some(FixedWindowStrategyConfig(None, 1000L, quota))))
      roundTrip(format, minimal.copy(throttlingStrategy = Some(DistributedRedisThrottlingStrategyConfig(Some("b"), quota))))
      roundTrip(format, minimal.copy(throttlingStrategy = Some(LuaDistributedRedisThrottlingStrategyConfig(None, quota))))
    }

    "round trip an enabled rotation without next secret" in {
      roundTrip(format, minimal.copy(rotation = ApiKeyRotation(enabled = true))).rotation.nextSecret mustBe None
    }

    "round trip plugins without api reference" in {
      roundTrip(format, minimal.copy(plugins = Some(NgPluginsWithOverride(NgPlugins.empty, overrides = false))))
    }

    "round trip the admin api rights kept in the metadata" in {
      roundTrip(format, full).metadata.get(ApiKey.accessRightsMetadataKey) mustBe Some(rightsJson)
    }

    "round trip zero and negative quotas" in {
      roundTrip(format, minimal.copy(throttlingQuota = 0, dailyQuota = -1, monthlyQuota = 0))
    }

    "turn an expired apikey into a disabled one on a round trip" in {
      val back = readOk(format, format.writes(minimal.copy(enabled = true, validUntil = Some(past))))
      back.enabled mustBe false
      back.validUntil mustBe Some(past)
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read an apikey without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read the smallest json with the defaults of every field" in {
      readOk(format, minimalJson) mustBe minimal
    }

    "not read an apikey without clientId" in {
      readError(format, minimalJson - "clientId")
    }

    "not read an apikey without clientSecret" in {
      readError(format, minimalJson - "clientSecret")
    }

    "not read an apikey without clientName" in {
      readError(format, minimalJson - "clientName")
    }

    "not read a blank clientId, clientSecret or clientName" in {
      readError(format, minimalJson ++ Json.obj("clientId" -> "  "))
      readError(format, minimalJson ++ Json.obj("clientSecret" -> ""))
      readError(format, minimalJson ++ Json.obj("clientName" -> "\t"))
    }

    "not read a clientId that is not a string" in {
      readError(format, minimalJson ++ Json.obj("clientId" -> 42))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read the authorizations given as kinds and ids" in {
      readOk(
        format,
        minimalJson ++ Json.obj(
          "authorizations" -> Json.arr(Json.obj("kind" -> "route", "id" -> "r1"), Json.obj("kind" -> "api", "id" -> "a1"))
        )
      ).authorizedEntities mustBe Seq(RouteIdentifier("r1"), ApiIdentifier("a1"))
    }

    "read the authorizations given as prefixed ids" in {
      readOk(format, minimalJson ++ Json.obj("authorizations" -> Json.arr("route_r1", "group_g1"))).authorizedEntities mustBe
        Seq(RouteIdentifier("r1"), ServiceGroupIdentifier("g1"))
    }

    "skip the authorizations that are neither strings nor objects" in {
      readOk(
        format,
        minimalJson ++ Json.obj("authorizations" -> Json.arr(1, true, JsNull, Json.obj("kind" -> "route", "id" -> "r1")))
      ).authorizedEntities mustBe Seq(RouteIdentifier("r1"))
    }

    "not read an apikey with an authorization without id" in {
      readError(format, minimalJson ++ Json.obj("authorizations" -> Json.arr(Json.obj("kind" -> "route"))))
    }

    "read an authorization of unknown kind as a group" in {
      readOk(
        format,
        minimalJson ++ Json.obj("authorizations" -> Json.arr(Json.obj("kind" -> "plan", "id" -> "p1")))
      ).authorizedEntities mustBe Seq(ServiceGroupIdentifier("p1"))
    }

    "read the legacy authorizedEntities alone" in {
      readOk(format, minimalJson ++ Json.obj("authorizedEntities" -> Json.arr("service_s1", "api_a1"))).authorizedEntities mustBe
        Seq(ServiceDescriptorIdentifier("s1"), ApiIdentifier("a1"))
    }

    "read a legacy authorized entity without prefix as a group" in {
      readOk(format, minimalJson ++ Json.obj("authorizedEntities" -> Json.arr("g1"))).authorizedEntities mustBe
        Seq(ServiceGroupIdentifier("g1"))
    }

    "read the legacy authorizedGroup alone as a group, even when it looks prefixed" in {
      readOk(format, minimalJson ++ Json.obj("authorizedGroup" -> "route_r1")).authorizedEntities mustBe
        Seq(ServiceGroupIdentifier("route_r1"))
    }

    "read a json null authorizedGroup as no group" in {
      readOk(format, minimalJson ++ Json.obj("authorizedGroup" -> JsNull)).authorizedEntities mustBe empty
    }

    "merge the authorizations, the legacy entities and the legacy group, in this order" in {
      readOk(
        format,
        minimalJson ++ Json.obj(
          "authorizedGroup"    -> "g1",
          "authorizedEntities" -> Json.arr("api_a1", "route_r1"),
          "authorizations"     -> Json.arr(Json.obj("kind" -> "route", "id" -> "r1"))
        )
      ).authorizedEntities mustBe Seq(RouteIdentifier("r1"), ApiIdentifier("a1"), ServiceGroupIdentifier("g1"))
    }

    // the three keys are merged: an entity removed from the authorizations only is still authorized
    "keep an entity that is still listed under a legacy key" in {
      val json = written(format, minimal.copy(authorizedEntities = Seq(ServiceGroupIdentifier("g1")))) ++
        Json.obj("authorizations" -> Json.arr())
      readOk(format, json).authorizedEntities mustBe Seq(ServiceGroupIdentifier("g1"))
    }

    "ignore the whole legacy authorizedEntities when one of them is not a string" in {
      readOk(format, minimalJson ++ Json.obj("authorizedEntities" -> Json.arr("route_r1", 1))).authorizedEntities mustBe empty
    }

    "read an enabled flag that is not a boolean as enabled" in {
      readOk(format, minimalJson ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "read an apikey that has expired as disabled" in {
      val back = readOk(format, minimalJson ++ Json.obj("enabled" -> true, "validUntil" -> past.getMillis))
      back.enabled mustBe false
      back.validUntil mustBe Some(past)
    }

    "read a validity date that is not a number as no validity date" in {
      val back = readOk(format, minimalJson ++ Json.obj("validUntil" -> "2000-01-01T00:00:00Z"))
      back.validUntil mustBe None
      back.enabled mustBe true
    }

    "read flags that are not booleans as their defaults" in {
      val back = readOk(
        format,
        minimalJson ++ Json.obj("readOnly" -> "true", "allowClientIdOnly" -> 1, "constrainedServicesOnly" -> "yes")
      )
      back.readOnly mustBe false
      back.allowClientIdOnly mustBe false
      back.constrainedServicesOnly mustBe false
    }

    "read quotas that are not integers as unlimited" in {
      val back = readOk(format, minimalJson ++ Json.obj("throttlingQuota" -> "10", "dailyQuota" -> 10.5, "monthlyQuota" -> JsNull))
      back.throttlingQuota mustBe RemainingQuotas.MaxValue
      back.dailyQuota mustBe RemainingQuotas.MaxValue
      back.monthlyQuota mustBe RemainingQuotas.MaxValue
    }

    "ignore a throttling strategy of unknown kind" in {
      readOk(
        format,
        minimalJson ++ Json.obj("throttlingStrategy" -> Json.obj("id" -> "Unknown", "quota" -> AllowedQuota().json))
      ).throttlingStrategy mustBe None
    }

    "ignore a throttling strategy that requires a quota and has none" in {
      readOk(
        format,
        minimalJson ++ Json.obj("throttlingStrategy" -> Json.obj("id" -> "LegacyThrottlingStrategyConfig"))
      ).throttlingStrategy mustBe None
    }

    "read a tokens bucket strategy without quota with an unlimited quota" in {
      readOk(
        format,
        minimalJson ++ Json.obj("throttlingStrategy" -> Json.obj("id" -> "LocalTokensBucketStrategyConfig", "bucketKey" -> "b"))
      ).throttlingStrategy mustBe Some(LocalTokensBucketStrategyConfig(bucketKey = "b"))
    }

    "read restrictions and a rotation that are not objects as their defaults" in {
      val back = readOk(format, minimalJson ++ Json.obj("restrictions" -> "none", "rotation" -> Json.arr()))
      back.restrictions mustBe Restrictions()
      back.rotation mustBe ApiKeyRotation()
    }

    "ignore an incomplete api reference" in {
      readOk(format, minimalJson ++ Json.obj("apiRef" -> Json.obj("api" -> "api-1", "plan" -> "plan-1"))).apiRef mustBe None
    }

    "ignore all the plugins when one slot has no plugin id" in {
      readOk(
        format,
        minimalJson ++ Json.obj("plugins" -> Json.obj("plugins" -> Json.arr(plugin.json, Json.obj("enabled" -> true))))
      ).plugins mustBe None
    }

    "ignore plugins given as an array" in {
      readOk(format, minimalJson ++ Json.obj("plugins" -> Json.arr(plugin.json))).plugins mustBe None
    }

    "ignore all the metadata, the admin api rights included, when one value is not a string" in {
      readOk(
        format,
        minimalJson ++ Json.obj("metadata" -> Json.obj(ApiKey.accessRightsMetadataKey -> rightsJson, "count" -> 1))
      ).metadata mustBe Map.empty
    }

    "drop the metadata whose key is empty" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("" -> "x", "a" -> "b"))).metadata mustBe Map("a" -> "b")
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("foo" -> "bar", "bearer" -> "otoapk_x")) mustBe full
    }

    "expose its fields through the generic accessors, with the client id and name" in {
      hasAccessors(
        full,
        "client-1",
        "Client 1",
        "the first apikey",
        Seq("prod", "eu"),
        Map("owner" -> "ops", ApiKey.accessRightsMetadataKey -> rightsJson)
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
      ApiKey.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      ApiKey.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without secret" in {
      a[Throwable] must be thrownBy ApiKey.fromJsons(minimalJson - "clientSecret")
    }

    "be read safely from its json" in {
      ApiKey.fromJsonSafe(full.json) mustBe JsSuccess(full)
    }
  }

  "ApiKey bearer" should {

    "be made of the client id and a signature" in {
      val bearer = minimal.toBearer()
      bearer must startWith("otoapk_client-1_")
      bearer.stripPrefix("otoapk_client-1_") must fullyMatch regex "[0-9a-f]{64}"
    }

    "be signed with the client secret" in {
      minimal.toBearer() mustBe minimal.copy(clientName = "other").toBearer()
      minimal.toBearer() must not be minimal.copy(clientSecret = "secret-2").toBearer()
    }

    "be signed with the next secret once there is one" in {
      minimal.toNextBearer() mustBe minimal.toBearer()
      full.toNextBearer() mustBe full.copy(clientSecret = "secret-2").toBearer()
    }

    "be checked against the current and the next secret" in {
      full.checkBearer(full.toBearer()) mustBe true
      full.checkBearer(full.toNextBearer()) mustBe true
      full.checkBearer(full.copy(clientSecret = "secret-3").toBearer()) mustBe false
      full.checkBearer(full.toBearer() + "0") mustBe false
    }

    // unlike isValid, checkBearer does not look at the rotation flag
    "be checked against the next secret even when the rotation is disabled" in {
      val apikey = minimal.copy(rotation = ApiKeyRotation(enabled = false, nextSecret = Some("secret-2")))
      apikey.checkBearer(minimal.copy(clientSecret = "secret-2").toBearer()) mustBe true
    }

    "be added to the json of the apikey" in {
      val json = minimal.jsonWithBearer
      (json \ "bearer").get mustBe JsString(minimal.toBearer())
      (json \ "rotation" \ "bearer").toOption mustBe None
    }

    "add the next bearer to the rotation when the rotation is enabled with a next secret" in {
      (full.jsonWithBearer \ "rotation" \ "bearer").get mustBe JsString(full.toNextBearer())
      (full.copy(rotation = full.rotation.copy(enabled = false)).jsonWithBearer \ "rotation" \ "bearer").toOption mustBe None
    }

    "be ignored when the json is read back" in {
      readOk(ApiKey._fmt, full.jsonWithBearer) mustBe full
    }
  }

  "ApiKey helpers" should {

    "be active while enabled and not expired" in {
      minimal.isActive() mustBe true
      minimal.copy(validUntil = Some(future)).isActive() mustBe true
      minimal.copy(validUntil = Some(past)).isActive() mustBe false
      minimal.copy(enabled = false).isActive() mustBe false
      minimal.copy(validUntil = Some(past)).isInactive() mustBe true
    }

    "validate the client secret of an enabled apikey" in {
      minimal.isValid("secret-1") mustBe true
      minimal.isValid("secret-2") mustBe false
      minimal.isInvalid("secret-2") mustBe true
      minimal.copy(enabled = false).isValid("secret-1") mustBe false
    }

    "validate the next secret only while the rotation is enabled" in {
      full.copy(enabled = true).isValid("secret-2") mustBe true
      full.copy(enabled = true, rotation = full.rotation.copy(enabled = false)).isValid("secret-2") mustBe false
    }

    "tell the entities it is authorized on" in {
      full.authorizedOnRoute("route-1") mustBe true
      full.authorizedOnGroup("group-1") mustBe true
      full.authorizedOnService("service-1") mustBe true
      full.authorizedOnRouteComposition("composition-1") mustBe true
      full.authorizedOn(ApiIdentifier("api-1")) mustBe true
      full.authorizedOnRoute("group-1") mustBe false
      full.authorizedOnGroup("route-1") mustBe false
    }

    "be authorized on the api of its plan" in {
      val apikey = minimal.copy(apiRef = Some(ApiRef("api-2", "plan-1", "subscription-1")))
      apikey.authorizedOnApi("api-2") mustBe true
      apikey.authorizedOnApi("api-3") mustBe false
      minimal.copy(authorizedEntities = Seq(ApiIdentifier("api-3"))).authorizedOnApi("api-3") mustBe true
    }

    "be authorized on a route, an api or a composition id, or one of the groups" in {
      val onGroup = minimal.copy(authorizedEntities = Seq(ServiceGroupIdentifier("g1")))
      onGroup.authorizedOnServiceOrGroups("route-x", Seq("g0", "g1")) mustBe true
      onGroup.authorizedOnServiceOrGroups("route-x", Seq("g0")) mustBe false
      minimal.copy(authorizedEntities = Seq(RouteIdentifier("r1"))).authorizedOnServiceOrGroups("r1", Seq.empty) mustBe true
      minimal.copy(authorizedEntities = Seq(ApiIdentifier("r1"))).authorizedOnServiceOrGroups("r1", Seq.empty) mustBe true
      minimal.authorizedOnServiceOrGroups("r1", Seq("g1")) mustBe false
    }

    "be authorized on one group of a list" in {
      full.authorizedOnOneGroupFrom(Seq("group-0", "group-1")) mustBe true
      full.authorizedOnOneGroupFrom(Seq("route-1")) mustBe false
      full.authorizedOnOneGroupFrom(Seq.empty) mustBe false
    }

    "give its quotas as the allowed quota without throttling strategy" in {
      full.copy(throttlingStrategy = None).allowedQuota mustBe AllowedQuota(window = 10, daily = 100, monthly = 1000)
      minimal.allowedQuota mustBe AllowedQuota()
    }

    "give the quota of its throttling strategy over its own quotas" in {
      minimal
        .copy(throttlingQuota = 1, throttlingStrategy = Some(LegacyThrottlingStrategyConfig(AllowedQuota(5, 6, 7))))
        .allowedQuota mustBe AllowedQuota(5, 6, 7)
    }

    "write a light json without secret" in {
      full.lightJson mustBe Json.obj(
        "clientId"   -> "client-1",
        "clientName" -> "Client 1",
        "metadata"   -> Json.obj("owner" -> "ops", ApiKey.accessRightsMetadataKey -> rightsJson),
        "tags"       -> Json.arr("prod", "eu")
      )
    }

    "write its metadata as a json object" in {
      minimal.copy(metadata = Map("a" -> "b")).metadataJson mustBe Json.obj("a" -> "b")
    }
  }

  "ApiKey routing match" should {

    val apikey = minimal.copy(tags = Seq("a", "b"), metadata = Map("env" -> "prod", "team" -> "ops"))

    "match a route without routing constraint, unless constrained to constrained routes" in {
      apikey.matchRouting(ApiKeyRouteMatcher()) mustBe true
      apikey.copy(constrainedServicesOnly = true).matchRouting(ApiKeyRouteMatcher()) mustBe false
    }

    "match on one tag" in {
      apikey.matchRouting(ApiKeyRouteMatcher(oneTagIn = Seq("b", "z"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(oneTagIn = Seq("z"))) mustBe false
    }

    "match on all the tags" in {
      apikey.matchRouting(ApiKeyRouteMatcher(allTagsIn = Seq("a", "b"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(allTagsIn = Seq("a", "z"))) mustBe false
    }

    "never match all the tags of a list holding duplicates" in {
      apikey.matchRouting(ApiKeyRouteMatcher(allTagsIn = Seq("a", "a"))) mustBe false
    }

    "match on none of the tags" in {
      apikey.matchRouting(ApiKeyRouteMatcher(noneTagIn = Seq("z"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(noneTagIn = Seq("a", "z"))) mustBe false
    }

    "match on one, all or none of the metadata entries" in {
      apikey.matchRouting(ApiKeyRouteMatcher(oneMetaIn = Map("env" -> "prod", "x" -> "y"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(oneMetaIn = Map("env" -> "dev"))) mustBe false
      apikey.matchRouting(ApiKeyRouteMatcher(allMetaIn = Map("env" -> "prod", "team" -> "ops"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(allMetaIn = Map("env" -> "prod", "team" -> "dev"))) mustBe false
      apikey.matchRouting(ApiKeyRouteMatcher(noneMetaIn = Map("env" -> "dev"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(noneMetaIn = Map("env" -> "prod"))) mustBe false
    }

    "match on one, all or none of the metadata keys" in {
      apikey.matchRouting(ApiKeyRouteMatcher(oneMetaKeyIn = Seq("team", "x"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(oneMetaKeyIn = Seq("x"))) mustBe false
      apikey.matchRouting(ApiKeyRouteMatcher(allMetaKeysIn = Seq("env", "team"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(allMetaKeysIn = Seq("env", "x"))) mustBe false
      apikey.matchRouting(ApiKeyRouteMatcher(noneMetaKeysIn = Seq("x"))) mustBe true
      apikey.matchRouting(ApiKeyRouteMatcher(noneMetaKeysIn = Seq("env"))) mustBe false
    }

    "require every constraint to match" in {
      apikey.matchRouting(ApiKeyRouteMatcher(oneTagIn = Seq("a"), oneMetaKeyIn = Seq("x"))) mustBe false
      apikey.matchRouting(ApiKeyRouteMatcher(oneTagIn = Seq("a"), oneMetaKeyIn = Seq("env"))) mustBe true
    }

    "match the routing of apikey constraints" in {
      apikey.matchRouting(ApiKeyConstraints(routing = ApiKeyRouteMatcher(oneTagIn = Seq("a")))) mustBe true
      apikey.matchRouting(ApiKeyConstraints(routing = ApiKeyRouteMatcher(oneTagIn = Seq("z")))) mustBe false
    }
  }

  "ApikeyTuple" should {

    val tuple = ApikeyTuple(
      clientId = "client-1",
      clientSecret = Some("secret-1"),
      location = Some(ApikeyLocation(ApikeyLocationKind.Header, "Otoroshi-Client-Id")),
      otoBearer = None
    )

    "write the credentials it was given" in {
      tuple.json mustBe Json.obj(
        "client_id"     -> "client-1",
        "client_secret" -> "secret-1",
        "jwt_token"     -> JsNull,
        "oto_bearer"    -> JsNull,
        "location"      -> Json.obj("name" -> "Otoroshi-Client-Id", "kind" -> "Header")
      )
    }

    "write only which credentials were given in its redacted json" in {
      tuple.copy(otoBearer = Some("otoapk_x")).redactedJson mustBe Json.obj(
        "client_id"     -> "client-1",
        "client_secret" -> true,
        "jwt_token"     -> false,
        "oto_bearer"    -> true,
        "location"      -> Json.obj("name" -> "Otoroshi-Client-Id", "kind" -> "Header")
      )
    }

    "be read back from its json" in {
      ApikeyTuple.fromJson(tuple.json) mustBe Right(tuple)
      ApikeyTuple.fromJson(tuple.copy(clientSecret = None, location = None, otoBearer = Some("otoapk_x")).json) mustBe
        Right(tuple.copy(clientSecret = None, location = None, otoBearer = Some("otoapk_x")))
    }

    "read each kind of location, an unknown one as a header" in {
      def kindOf(kind: String): Option[ApikeyLocationKind] =
        ApikeyTuple
          .fromJson(Json.obj("client_id" -> "c", "location" -> Json.obj("name" -> "n", "kind" -> kind)))
          .toOption
          .flatMap(_.location.map(_.kind))
      kindOf("Query") mustBe Some(ApikeyLocationKind.Query)
      kindOf("Cookie") mustBe Some(ApikeyLocationKind.Cookie)
      kindOf("Path") mustBe Some(ApikeyLocationKind.Path)
      kindOf("Header") mustBe Some(ApikeyLocationKind.Header)
      kindOf("Body") mustBe Some(ApikeyLocationKind.Header)
    }

    "not be read without client id" in {
      ApikeyTuple.fromJson(Json.obj("client_secret" -> "secret-1")).isLeft mustBe true
    }
  }
}
