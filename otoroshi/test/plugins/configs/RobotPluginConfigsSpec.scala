package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class RobotPluginConfigsSpec extends PluginConfigFormatSpec {

  "RobotConfig" should {

    val format = RobotConfig.format

    val defaultRobotsTxt = "User-agent: *\nDisallow: /\n"

    val full = RobotConfig(
      robotEnabled = false,
      robotTxtContent = "User-agent: Googlebot\nAllow: /public\n",
      metaEnabled = false,
      metaContent = "noindex",
      headerEnabled = false,
      headerContent = "none"
    )

    "disallow every robot everywhere by default" in {
      val config = RobotConfig()
      config.robotEnabled mustBe true
      config.robotTxtContent mustBe defaultRobotsTxt
      config.metaEnabled mustBe true
      config.metaContent mustBe "noindex,nofollow,noarchive"
      config.headerEnabled mustBe true
      config.headerContent mustBe "noindex, nofollow, noarchive"
    }

    "write the default config" in {
      format.writes(RobotConfig()) mustBe Json.obj(
        "robot_txt_enabled" -> true,
        "robot_txt_content" -> defaultRobotsTxt,
        "meta_enabled"      -> true,
        "meta_content"      -> "noindex,nofollow,noarchive",
        "header_enabled"    -> true,
        "header_content"    -> "noindex, nofollow, noarchive"
      )
    }

    "write the robots.txt flag under robot_txt_enabled" in {
      (format.writes(full) \ "robot_txt_enabled").as[Boolean] mustBe false
    }

    "round trip the default config" in {
      roundTrip(format, RobotConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the robots.txt disabled alone" in {
      roundTrip(format, RobotConfig(robotEnabled = false))
    }

    "round trip the meta tag disabled alone" in {
      roundTrip(format, RobotConfig(metaEnabled = false))
    }

    "round trip the header disabled alone" in {
      roundTrip(format, RobotConfig(headerEnabled = false))
    }

    "round trip empty contents" in {
      roundTrip(format, RobotConfig(robotTxtContent = "", metaContent = "", headerContent = ""))
    }

    "round trip a robots.txt with comments, unicode and windows line breaks" in {
      roundTrip(format, RobotConfig(robotTxtContent = "# robots été\r\nUser-agent: *\r\nDisallow: /admin\r\n"))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RobotConfig()
    }

    "read the default robots.txt without the margin of the source code" in {
      readOk(format, Json.obj()).robotTxtContent mustBe defaultRobotsTxt
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RobotConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("User-agent: *")) mustBe RobotConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(true)) mustBe RobotConfig()
    }

    "read the robots.txt content alone and default the other fields" in {
      readOk(format, Json.obj("robot_txt_content" -> "Disallow: /x")) mustBe RobotConfig(
        robotTxtContent = "Disallow: /x"
      )
    }

    "read the header fields alone and default the other fields" in {
      readOk(format, Json.obj("header_enabled" -> false, "header_content" -> "none")) mustBe RobotConfig(
        headerEnabled = false,
        headerContent = "none"
      )
    }

    "ignore a flag written as a string" in {
      readOk(format, Json.obj("meta_enabled" -> "false")).metaEnabled mustBe true
    }

    "ignore a content that is not a string" in {
      readOk(format, Json.obj("meta_content" -> Json.arr("noindex"))).metaContent mustBe "noindex,nofollow,noarchive"
    }

    "not read the robot_enabled key the case class is named after" in {
      readOk(format, Json.obj("robot_enabled" -> false)).robotEnabled mustBe true
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("robotEnabled" -> false, "metaContent" -> "x", "headerEnabled" -> false)) mustBe
        RobotConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of Robots" in {
      defaultConfigReadsBack(new Robots(), format) mustBe RobotConfig()
    }
  }
}
