package plugins

import functional.PluginsTestSpecBase
import play.api.mvc.Cookie

// a private apps session id is only accepted with the signature otoroshi gave it
class PrivateSessionSignatureTests(parent: PluginsTestSpecBase) {
  import parent.*

  private val sessionId = "a-session-id"
  private val signature = env.sign(sessionId)

  env.extractPrivateSessionIdFromString(s"$signature::$sessionId") mustBe Some(sessionId)
  env.extractPrivateSessionId(Cookie("oto-papps", s"$signature::$sessionId")) mustBe Some(sessionId)

  env.extractPrivateSessionIdFromString(s"${env.sign("another-session-id")}::$sessionId") mustBe None
  env.extractPrivateSessionIdFromString(s"${signature.dropRight(1)}::$sessionId") mustBe None
  env.extractPrivateSessionIdFromString(s"::$sessionId") mustBe None
  env.extractPrivateSessionIdFromString(sessionId) mustBe None
  env.extractPrivateSessionId(Cookie("oto-papps", s"${env.sign("another-session-id")}::$sessionId")) mustBe None
}
