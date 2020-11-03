package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.jobs.updates.Version

class VersionSpec extends WordSpec with MustMatchers with OptionValues {
  "Version api" should {
    "work" in {
      Version("1.0.0").isEquals(Version("1.1.0")) mustBe false
      Version("1.0.0").isBefore(Version("1.1.0")) mustBe true
      Version("1.0.0").isBefore(Version("1.0.0")) mustBe true
      Version("1.0.0").isEquals(Version("1.0.0")) mustBe true
      Version("1.0.0").isAfter(Version("1.0.1")) mustBe false
      Version("1.0.1").isAfter(Version("1.0.0")) mustBe true
      Version("1.0.1-dev").isAfter(Version("1.0.1")) mustBe false
      Version("1.0.1-snapshot").isAfter(Version("1.0.1")) mustBe false
      Version("1.0.1-alpha01").isAfter(Version("1.0.1")) mustBe false
      Version("1.0.1-beta12").isAfter(Version("1.0.1")) mustBe false
      Version("1.0.1-rc-12").isAfter(Version("1.0.1")) mustBe false
      Version("1.5.0-alpha01").isAfter(Version("1.4.22")) mustBe true
      Version("1.5.0-alpha02").isAfter(Version("1.5.0-alpha01")) mustBe true
      Version("1.5.0-beta-2").isAfter(Version("1.5.0-alpha01")) mustBe true
      Version("1.5.0-rc-2").isAfter(Version("1.5.0-beta3")) mustBe true
      Version("1.5.0-alpha01").isEquals(Version("1.5.0-alpha-1")) mustBe true
      Version("1.5.0-alpha01").isEquals(Version("1.5.0-alpha1")) mustBe true
    }
  }
}
