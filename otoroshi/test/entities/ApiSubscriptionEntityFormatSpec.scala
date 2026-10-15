package entities

import org.joda.time.DateTime
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import play.api.libs.json.*

class ApiSubscriptionEntityFormatSpec extends EntityFormatSpec {

  private val dates = ApiSubscriptionDates(
    created_at = new DateTime(1700000000000L),
    processed_at = new DateTime(1700000001000L),
    started_at = new DateTime(1700000002000L),
    paused_at = new DateTime(1700000003000L),
    ending_at = new DateTime(1700000004000L),
    closed_at = new DateTime(1700000005000L)
  )

  private val datesJson = Json.obj(
    "created_at"   -> 1700000000000L,
    "processed_at" -> 1700000001000L,
    "started_at"   -> 1700000002000L,
    "paused_at"    -> 1700000003000L,
    "ending_at"    -> 1700000004000L,
    "closed_at"    -> 1700000005000L
  )

  private val full = ApiSubscription(
    location = customLocation,
    id = "api-subscription_1",
    name = "gold subscription",
    description = "the gold subscription of the mobile app",
    tags = Seq("mobile", "gold"),
    metadata = Map("contract" -> "c-42"),
    status = ApiSubscriptionEnabled,
    dates = dates,
    ownerRef = "client-app-1",
    planRef = "plan-1",
    apiRef = "api-1",
    paymentRef = Json.obj("stripe" -> "sub_123"),
    subscriptionKind = ApiKind.Apikey,
    tokenRefs = Seq(Json.obj("apikey" -> "client-id-1"))
  )

  private val minimalJson = Json.obj(
    "location"          -> defaultLocationJson,
    "id"                -> "sub-1",
    "name"              -> "sub",
    "description"       -> "",
    "dates"             -> datesJson,
    "owner_ref"         -> "client-1",
    "plan_ref"          -> "plan-1",
    "api_ref"           -> "api-1",
    "subscription_kind" -> "apikey"
  )

  "ApiSubscription" should {

    val format = ApiSubscription.format

    "write a subscription" in {
      format.writes(full) mustBe Json.obj(
        "location"          -> customLocationJson,
        "id"                -> "api-subscription_1",
        "name"              -> "gold subscription",
        "description"       -> "the gold subscription of the mobile app",
        "tags"              -> Json.arr("mobile", "gold"),
        "metadata"          -> Json.obj("contract" -> "c-42"),
        "status"            -> "enabled",
        "dates"             -> datesJson,
        "owner_ref"         -> "client-app-1",
        "plan_ref"          -> "plan-1",
        "payment_ref"       -> Json.obj("stripe" -> "sub_123"),
        "api_ref"           -> "api-1",
        "subscription_kind" -> "apikey",
        "token_refs"        -> Json.arr(Json.obj("apikey" -> "client-id-1"))
      )
    }

    "write a subscription without tags, metadata, payment nor token" in {
      val json = written(format, full.copy(tags = Seq.empty, metadata = Map.empty, paymentRef = Json.obj(), tokenRefs = Seq.empty))
      (json \ "tags").as[JsArray] mustBe Json.arr()
      (json \ "metadata").as[JsObject] mustBe Json.obj()
      (json \ "payment_ref").as[JsObject] mustBe Json.obj()
      (json \ "token_refs").as[JsArray] mustBe Json.arr()
    }

    "round trip a subscription where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a subscription without tags, metadata, payment nor token" in {
      roundTrip(format, full.copy(tags = Seq.empty, metadata = Map.empty, paymentRef = Json.obj(), tokenRefs = Seq.empty))
    }

    "round trip every status" in {
      Seq(ApiSubscriptionPending, ApiSubscriptionEnabled, ApiSubscriptionDisabled, ApiSubscriptionDeprecated).foreach { status =>
        roundTrip(format, full.copy(status = status)).status mustBe status
      }
    }

    "round trip a custom status" in {
      roundTrip(format, full.copy(status = ApiSubscriptionCustom("suspended"))).status mustBe ApiSubscriptionCustom("suspended")
    }

    "round trip every kind" in {
      Seq(ApiKind.Apikey, ApiKind.Mtls, ApiKind.Keyless, ApiKind.OAuth2Local, ApiKind.OAuth2Remote, ApiKind.JWT).foreach { kind =>
        roundTrip(format, full.copy(subscriptionKind = kind)).subscriptionKind mustBe kind
      }
    }

    "round trip several token references of any shape in order" in {
      val refs = Seq(Json.obj("apikey" -> "b"), Json.obj("apikey" -> "a"), JsString("cert-1"), Json.obj("cert" -> Json.obj("dn" -> "CN=x")))
      roundTrip(format, full.copy(tokenRefs = refs)).tokenRefs mustBe refs
    }

    "round trip its custom location" in {
      roundTrip(format, full).location mustBe customLocation
    }

    "read a minimal subscription with the defaults" in {
      readOk(format, minimalJson) mustBe ApiSubscription(
        location = EntityLocation(),
        id = "sub-1",
        name = "sub",
        description = "",
        tags = Seq.empty,
        metadata = Map.empty,
        status = ApiSubscriptionDisabled,
        dates = dates,
        ownerRef = "client-1",
        planRef = "plan-1",
        apiRef = "api-1",
        paymentRef = Json.obj(),
        subscriptionKind = ApiKind.Apikey,
        tokenRefs = Seq.empty
      )
    }

    "not read a subscription without location" in {
      readError(format, minimalJson - "location")
    }

    "not read the location from the _loc key" in {
      readError(format, (minimalJson - "location") ++ Json.obj("_loc" -> customLocationJson))
    }

    "read a null location as the default location" in {
      readOk(format, minimalJson ++ Json.obj("location" -> JsNull)).location mustBe EntityLocation()
    }

    "not read a subscription without one of its mandatory keys" in {
      Seq("id", "name", "description", "dates", "owner_ref", "plan_ref", "api_ref", "subscription_kind").foreach { key =>
        readError(format, minimalJson - key)
      }
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 1))
    }

    "read a status that is not a string as disabled" in {
      readOk(format, minimalJson ++ Json.obj("status" -> true)).status mustBe ApiSubscriptionDisabled
    }

    "read an unknown status as a custom one" in {
      readOk(format, minimalJson ++ Json.obj("status" -> "suspended")).status mustBe ApiSubscriptionCustom("suspended")
    }

    "read the status case sensitively" in {
      readOk(format, minimalJson ++ Json.obj("status" -> "Enabled")).status mustBe ApiSubscriptionCustom("Enabled")
    }

    "read back a custom status named like a known one as the known one" in {
      readOk(format, format.writes(full.copy(status = ApiSubscriptionCustom("enabled")))).status mustBe ApiSubscriptionEnabled
    }

    "read the kind case insensitively" in {
      readOk(format, minimalJson ++ Json.obj("subscription_kind" -> "OAuth2-Local")).subscriptionKind mustBe ApiKind.OAuth2Local
    }

    "not read an unknown kind" in {
      readError(format, minimalJson ++ Json.obj("subscription_kind" -> "basic"))
    }

    "not read a kind that is not a string" in {
      readError(format, minimalJson ++ Json.obj("subscription_kind" -> 1))
    }

    "read a payment reference that is not an object as an empty one" in {
      readOk(format, minimalJson ++ Json.obj("payment_ref" -> "sub_123")).paymentRef mustBe Json.obj()
    }

    "read token references that are not an array as none" in {
      readOk(format, minimalJson ++ Json.obj("token_refs" -> Json.obj("apikey" -> "a"))).tokenRefs mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("tags" -> "mobile")).tags mustBe empty
    }

    "not read the references from camel case keys" in {
      readError(format, (minimalJson - "plan_ref") ++ Json.obj("planRef" -> "plan-1"))
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "api-subscription_1",
        "gold subscription",
        "the gold subscription of the mobile app",
        Seq("mobile", "gold"),
        Map("contract" -> "c-42")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      ApiSubscription.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy ApiSubscription.fromJsons(minimalJson - "id")
    }

    "name the apikey metadata and tags it manages" in {
      ApiSubscription.PLAN_METADATA_KEY mustBe "plan_metadata"
      ApiSubscription.SUBSCRIPTION_METADATA_KEY mustBe "subscription_metadata"
      ApiSubscription.PLAN_TAGS_KEY mustBe "plan_tags"
      ApiSubscription.SUBSCRIPTION_TAGS_KEY mustBe "subscription_tags"
      ApiSubscription.METADATA_AND_TAGS_SEPARATOR mustBe " | "
      ApiSubscription.CORE_METADATA mustBe Seq("updated_at")
    }
  }

  "ApiSubscriptionState" should {

    "name each state" in {
      Seq(ApiSubscriptionPending, ApiSubscriptionEnabled, ApiSubscriptionDisabled, ApiSubscriptionDeprecated)
        .map(_.name) mustBe Seq("pending", "enabled", "disabled", "deprecated")
    }

    "name a custom state after its value" in {
      ApiSubscriptionCustom("suspended").name mustBe "suspended"
    }
  }

  "ApiSubscriptionDates" should {

    val format = ApiSubscriptionDates._fmt

    "write the dates as epoch milliseconds" in {
      format.writes(dates) mustBe datesJson
    }

    "round trip dates" in {
      roundTrip(format, dates)
    }

    "read the missing dates as now" in {
      val before = System.currentTimeMillis()
      val back   = readOk(format, datesJson - "paused_at" - "closed_at")
      val after  = System.currentTimeMillis()
      back.created_at mustBe dates.created_at
      back.paused_at.getMillis must (be >= before and be <= after)
      back.closed_at.getMillis must (be >= before and be <= after)
    }

    "read dates that are not numbers as now" in {
      val before = System.currentTimeMillis()
      readOk(format, datesJson ++ Json.obj("ending_at" -> "2030-01-01")).ending_at.getMillis must be >= before
    }

    "read an empty object as dates of now" in {
      val before = System.currentTimeMillis()
      readOk(format, Json.obj()).started_at.getMillis must be >= before
    }
  }
}
