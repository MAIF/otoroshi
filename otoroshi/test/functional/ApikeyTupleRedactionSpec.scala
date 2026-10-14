package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import otoroshi.models.ApikeyTuple
import otoroshi.utils.syntax.implicits.*

// when no case of the apikey validation matches the extracted credentials, the tuple is written in the rejection
// reason of the gateway event, which is exported. it used to carry the client secret, the bearer and the jwt
class ApikeyTupleRedactionSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  "the redacted apikey tuple" should {
    "say which credentials were sent without carrying any of them" in {
      val jwt      = JWT.create().withClaim("clientId", "the-client-id").sign(Algorithm.HMAC256("the-jwt-secret"))
      val tuple    = ApikeyTuple(
        clientId = "the-client-id",
        clientSecret = Some("the-client-secret"),
        jwtToken = Some(JWT.decode(jwt)),
        location = None,
        otoBearer = Some("the-oto-bearer")
      )
      val redacted = tuple.redactedJson
      val raw      = redacted.stringify
      withClue(s"redacted tuple: $raw ") {
        raw.contains("the-client-secret") mustBe false
        raw.contains("the-oto-bearer") mustBe false
        raw.contains(jwt) mustBe false
        redacted.select("client_id").asString mustBe "the-client-id"
        redacted.select("client_secret").as[Boolean] mustBe true
        redacted.select("jwt_token").as[Boolean] mustBe true
        redacted.select("oto_bearer").as[Boolean] mustBe true
      }
    }
  }
}
