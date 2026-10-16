package functional

import org.mindrot.jbcrypt.BCrypt
import otoroshi.utils.crypto.BCryptHelper
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*

// the admins api stores the admin it is given: a password sent in clear must be stored hashed, like
// POST /api/admins/simple does, and a bcrypt hash must be kept as is
class AdminsApiPasswordSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private val path = "/apis/security.otoroshi.io/v1/admins"

  private def admin(username: String, password: String): JsObject = Json.obj(
    "username" -> username,
    "password" -> password,
    "label"    -> username,
    "type"     -> "SIMPLE"
  )

  private def storedPassword(username: String): Option[String] =
    otoroshiApiCall("GET", s"$path/$username").futureValue match {
      case (json, 200) => json.select("password").asOpt[String]
      case _           => None
    }

  "The admins api" should {
    "warm up" in {
      startOtoroshi()
    }
    "store a password sent in clear hashed" in {
      val username       = "clear-password@oto.tools"
      val (json, status) = otoroshiApiCall("POST", path, Some(admin(username, "a-clear-password"))).futureValue
      try {
        withClue(json.stringify)(status mustBe 201)
        json.select("password").asString must not be "a-clear-password"
        val stored = storedPassword(username).get
        stored must not be "a-clear-password"
        BCryptHelper.isHash(stored) mustBe true
        BCryptHelper.checkpw("a-clear-password", stored) mustBe true
      } finally {
        otoroshiApiCall("DELETE", s"$path/$username").futureValue
      }
    }
    "keep a bcrypt hash as is" in {
      val username       = "hashed-password@oto.tools"
      val hash           = BCrypt.hashpw("a-password", BCrypt.gensalt())
      val (json, status) = otoroshiApiCall("POST", path, Some(admin(username, hash))).futureValue
      try {
        withClue(json.stringify)(status mustBe 201)
        storedPassword(username) mustBe Some(hash)
      } finally {
        otoroshiApiCall("DELETE", s"$path/$username").futureValue
      }
    }
    "refuse an empty password" in {
      val username       = "empty-password@oto.tools"
      val (json, status) = otoroshiApiCall("POST", path, Some(admin(username, ""))).futureValue
      try {
        withClue(json.stringify)(status mustBe 400)
        storedPassword(username) mustBe None
      } finally {
        otoroshiApiCall("DELETE", s"$path/$username").futureValue
      }
    }
    "serve a template without password, or with the hash of the raw password given" in {
      val (template, status) = otoroshiApiCall("GET", s"$path/_template").futureValue
      status mustBe 200
      (template \ "password").as[String] mustBe ""
      // created as it is, the admin of the template is refused until a password is set
      val username       = "from-template@oto.tools"
      val created        = template.as[JsObject] ++ Json.obj("username" -> username)
      otoroshiApiCall("POST", path, Some(created)).futureValue._2 mustBe 400
      val (withRaw, _)   = otoroshiApiCall("GET", s"$path/_template?rawPassword=a-raw-password").futureValue
      val hash           = (withRaw \ "password").as[String]
      BCryptHelper.isHash(hash) mustBe true
      BCryptHelper.checkpw("a-raw-password", hash) mustBe true
    }
    "shutdown" in {
      stopAll()
    }
  }
}
