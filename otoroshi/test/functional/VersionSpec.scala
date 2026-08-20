package functional

import org.scalatest.{OptionValues}
import otoroshi.jobs.updates.Version

class VersionSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers with OptionValues {
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
      Version("v1.5.0-alpha01").isEquals(Version("1.5.0-alpha1")) mustBe true
      Version("v1alpha1").isEquals(Version("1.0.0-alpha01")) mustBe true
      Version("v1alpha1").isEquals(Version("1.0.0-alpha1")) mustBe true
      Version("v1alpha1").isEquals(Version("1.0.0-alpha-1")) mustBe true
      Version("v1alpha1").isEquals(Version("1.0.0-alpha.1")) mustBe true
      Version("v1alpha1").isEquals(Version("1.0.0-a.1")) mustBe true
    }
    "support preview versions" in {
      Version("18.0.0-preview1").isAfter(Version("18.0.0")) mustBe false
      Version("18.0.0").isAfter(Version("18.0.0-preview1")) mustBe true
      Version("18.0.0-preview1").isAfter(Version("17.14.0")) mustBe true
      Version("18.0.0-preview2").isAfter(Version("18.0.0-preview1")) mustBe true
      Version("18.0.0-preview1").isAfter(Version("18.0.0-beta3")) mustBe true
      Version("18.0.0-preview1").isAfter(Version("18.0.0-alpha3")) mustBe true
      Version("18.0.0-rc1").isAfter(Version("18.0.0-preview3")) mustBe true
      Version("18.0.0-preview1").isAfter(Version("18.0.0-rc1")) mustBe false
      Version("18.0.0-preview1-dev").isAfter(Version("18.0.0-preview1")) mustBe false
      Version("18.0.0-preview1").isEquals(Version("18.0.0-preview01")) mustBe true
      Version("18.0.0-preview1").isEquals(Version("18.0.0-preview-1")) mustBe true
      Version("18.0.0-preview1").isEquals(Version("18.0.0-preview.1")) mustBe true
      Version("v18preview1").isEquals(Version("18.0.0-preview1")) mustBe true
      Version("18.0.0-preview1").stringify() mustBe "18.0.0-preview.1"
      Version("18.0.0-preview1").json.toString().nonEmpty mustBe true
    }
  }
}
