package functional

import java.util.Date
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import models.{SecComVersion, ServiceDescriptor, Target}
import org.joda.time.DateTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import security.IdGenerator

import scala.util.{Failure, Try}

class BasicSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec {

  lazy val serviceHost = "basictest.oto.tools"

  override def getTestConfiguration(configuration: Configuration) = {
    Configuration(
      ConfigFactory
        .parseString("{}")
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  s"[$name] Otoroshi" should {

    val callCounter           = new AtomicInteger(0)
    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter.incrementAndGet()
      basicTestExpectedBody
    }).await()
    val initialDescriptor = ServiceDescriptor(
      id = "basic-test",
      name = "basic-test",
      env = "prod",
      subdomain = "basictest",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer.port}",
          scheme = "http"
        )
      ),
      localHost = s"127.0.0.1:${basicTestServer.port}",
      forceHttps = false,
      enforceSecureCommunication = false,
      publicPatterns = Seq("/.*")
    )

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen {
        case Failure(e) => e.printStackTrace()
      }.futureValue // WARM UP
    }

    s"return only one service descriptor after startup (for admin API)" in {
      val services = getOtoroshiServices().futureValue
      services.size mustBe 1
    }

    s"route a basic http call" in {

      val (_, creationStatus) = createOtoroshiService(initialDescriptor).futureValue

      creationStatus mustBe 201

      val basicTestResponse1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse1.status mustBe 200
      basicTestResponse1.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 1
    }

    "provide a way to disable a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(enabled = false)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 404
      callCounter.get() mustBe 1

      updateOtoroshiService(initialDescriptor.copy(enabled = true)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 2
    }

    "provide a way to pass a service descriptor in maintenance mode" in {

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service in maintenance mode") mustBe true
      callCounter.get() mustBe 2

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 3
    }

    "provide a way to pass a service descriptor in build mode" in {

      updateOtoroshiService(initialDescriptor.copy(buildMode = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service under construction") mustBe true
      callCounter.get() mustBe 3

      updateOtoroshiService(initialDescriptor.copy(buildMode = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 4
    }

    "provide a way to force https for a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(forceHttps = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 303
      basicTestResponse2.header("Location") mustBe Some("https://basictest.oto.tools/api")
      callCounter.get() mustBe 4

      updateOtoroshiService(initialDescriptor.copy(forceHttps = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 5
    }

    "send specific headers back" in {

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 200
      basicTestResponse2.header("Otoroshi-Request-Id").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Proxy-Latency").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Upstream-Latency").isDefined mustBe true
      callCounter.get() mustBe 6

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.header("Otoroshi-Request-Id").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Proxy-Latency").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Upstream-Latency").isEmpty mustBe true
      callCounter.get() mustBe 7

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = true)).futureValue
    }

    "Send additionnal headers to target" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { r =>
        r.headers.find(_.name() == "X-Foo").map(_.value()) mustBe Some("Bar")
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "header-test",
        name = "header-test",
        env = "prod",
        subdomain = "header",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        additionalHeaders = Map("X-Foo" -> "Bar")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "header.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if header is present" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "match-test",
        name = "match-test",
        env = "prod",
        subdomain = "match",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingHeaders = Map("X-Foo" -> "Bar")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "match.oto.tools"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"  -> "match.oto.tools",
          "X-Foo" -> "Bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 404
      resp2.status mustBe 200
      resp2.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if header is matching" in {

      val allBotUserAgents = Seq(
        "Googlebot/2.1 (+http://www.google.com/bot.html)",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 6_0 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10A5376e Safari/8536.25 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_3 like Mac OS X) AppleWebKit/537.36 (KHTML, like Gecko) Version/8.0 Mobile/12F70 Safari/600.1.4 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_3 like Mac OS X) AppleWebKit/600.1.4 (KHTML, like Gecko) Version/8.0 Mobile/12F70 Safari/600.1.4 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/27.0.1453 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2272.96 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Safari/537.36",
        "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; Google Web Preview Analytics) Chrome/27.0.1453 Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "DoCoMo/2.0 N905i(c100;TB;W24H16) (compatible; Googlebot-Mobile/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 6_0 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10A5376e Safari/8536.25 (compatible; Googlebot-Mobile/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; U; CPU iPhone OS 4_1 like Mac OS X; en-us) AppleWebKit/532.9 (KHTML, like Gecko) Version/4.0.5 Mobile/8B117 Safari/6531.22.7 (compatible; Googlebot-Mobile/2.1; +http://www.google.com/bot.html)",
        "Nokia6820/2.0 (4.83) Profile/MIDP-1.0 Configuration/CLDC-1.0 (compatible; Googlebot-Mobile/2.1; +http://www.google.com/bot.html)",
        "SAMSUNG-SGH-E250/1.0 Profile/MIDP-2.0 Configuration/CLDC-1.1 UP.Browser/6.2.3.3.c.1.101 (GUI) MMP/2.0 (compatible; Googlebot-Mobile/2.1; +http://www.google.com/bot.html)",
        "Googlebot-Image/1.0",
        "Googlebot-News",
        "Googlebot-Video/1.0",
        "AdsBot-Google (+http://www.google.com/adsbot.html)",
        "AdsBot-Google-Mobile-Apps",
        "Mozilla/5.0 (Linux; Android 5.0; SM-G920A) AppleWebKit (KHTML, like Gecko) Chrome Mobile Safari (compatible; AdsBot-Google-Mobile; +http://www.google.com/mobile/adsbot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1 (compatible; AdsBot-Google-Mobile; +http://www.google.com/mobile/adsbot.html)",
        "Feedfetcher-Google; (+http://www.google.com/feedfetcher.html; 1 subscribers; feed-id=728742641706423)",
        "Mediapartners-Google",
        "Mozilla/5.0 (compatible; MSIE or Firefox mutant; not on Windows server;) Daumoa/4.0 (Following Mediapartners-Google)",
        "Mozilla/5.0 (iPhone; U; CPU iPhone OS 10_0 like Mac OS X; en-us) AppleWebKit/602.1.38 (KHTML, like Gecko) Version/10.0 Mobile/14A5297c Safari/602.1 (compatible; Mediapartners-Google/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; U; CPU iPhone OS 4_1 like Mac OS X; en-us) AppleWebKit/532.9 (KHTML, like Gecko) Version/4.0.5 Mobile/8B117 Safari/6531.22.7 (compatible; Mediapartners-Google/2.1; +http://www.google.com/bot.html)",
        "APIs-Google (+https://developers.google.com/webmasters/APIs-Google.html)",
        "Mozilla/5.0 (Windows Phone 8.1; ARM; Trident/7.0; Touch; rv:11.0; IEMobile/11.0; NOKIA; Lumia 530) like Gecko (compatible; adidxbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; adidxbot/2.0;  http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; adidxbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; bingbot/2.0;  http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm) SitemapProbe",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53 (compatible; adidxbot/2.0;  http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53 (compatible; adidxbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53 (compatible; bingbot/2.0;  http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (seoanalyzer; compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; Yahoo! Slurp/3.0; http://help.yahoo.com/help/us/ysearch/slurp)",
        "Mozilla/5.0 (compatible; Yahoo! Slurp; http://help.yahoo.com/help/us/ysearch/slurp)",
        "Mozilla/5.0 (compatible; Yahoo! Slurp China; http://misc.yahoo.com.cn/help.html)",
        "WGETbot/1.0 (+http://wget.alanreed.org)",
        "eCairn-Grabber/1.0 (+http://ecairn.com/grabber) curl/7.15",
        "LinkedInBot/1.0 (compatible; Mozilla/5.0; Jakarta Commons-HttpClient/3.1 +http://www.linkedin.com)",
        "LinkedInBot/1.0 (compatible; Mozilla/5.0; Jakarta Commons-HttpClient/4.3 +http://www.linkedin.com)",
        "amibot - http://www.amidalla.de - tech@amidalla.com libwww-perl/5.831",
        "NutchCVS/0.7.1 (Nutch; http://lucene.apache.org/nutch/bot.html; nutch-agent@lucene.apache.org)",
        "istellabot-nutch/Nutch-1.10",
        "phpcrawl",
        "adidxbot/1.1 (+http://search.msn.com/msnbot.htm)",
        "adidxbot/2.0 (+http://search.msn.com/msnbot.htm)",
        "librabot/1.0 (+http://search.msn.com/msnbot.htm)",
        "librabot/2.0 (+http://search.msn.com/msnbot.htm)",
        "msnbot-NewsBlogs/2.0b (+http://search.msn.com/msnbot.htm)",
        "msnbot-UDiscovery/2.0b (+http://search.msn.com/msnbot.htm)",
        "msnbot-media/1.0 (+http://search.msn.com/msnbot.htm)",
        "msnbot-media/1.1 (+http://search.msn.com/msnbot.htm)",
        "msnbot-media/2.0b (+http://search.msn.com/msnbot.htm)",
        "msnbot/1.0 (+http://search.msn.com/msnbot.htm)",
        "msnbot/1.1 (+http://search.msn.com/msnbot.htm)",
        "msnbot/2.0b (+http://search.msn.com/msnbot.htm)",
        "msnbot/2.0b (+http://search.msn.com/msnbot.htm).",
        "msnbot/2.0b (+http://search.msn.com/msnbot.htm)._",
        "FAST-WebCrawler/3.6/FirstPage (atw-crawler at fast dot no;http://fast.no/support/crawler.asp)",
        "FAST-WebCrawler/3.7 (atw-crawler at fast dot no; http://fast.no/support/crawler.asp)",
        "FAST-WebCrawler/3.7/FirstPage (atw-crawler at fast dot no;http://fast.no/support/crawler.asp)",
        "FAST-WebCrawler/3.8",
        "FAST Enterprise Crawler 6 / Scirus scirus-crawler@fast.no; http://www.scirus.com/srsapp/contactus/",
        "FAST Enterprise Crawler 6 used by Schibsted (webcrawl@schibstedsok.no)",
        "ConveraCrawler/0.9e (+http://ews.converasearch.com/crawl.htm)",
        "Seekbot/1.0 (http://www.seekbot.net/bot.html) RobotsTxtFetcher/1.2",
        "Gigabot/1.0",
        "Gigabot/2.0 (http://www.gigablast.com/spider.html)",
        "Mozilla/5.0 (compatible; Alexabot/1.0; +http://www.alexa.com/help/certifyscan; certifyscan@alexa.com)",
        "Mozilla/5.0 (compatible; Exabot PyExalead/3.0; +http://www.exabot.com/go/robot)",
        "Mozilla/5.0 (compatible; Exabot-Images/3.0; +http://www.exabot.com/go/robot)",
        "Mozilla/5.0 (compatible; Exabot/3.0 (BiggerBetter); +http://www.exabot.com/go/robot)",
        "Mozilla/5.0 (compatible; Exabot/3.0; +http://www.exabot.com/go/robot)",
        "ia_archiver (+http://www.alexa.com/site/help/webmasters; crawler@alexa.com)",
        "GingerCrawler/1.0 (Language Assistant for Dyslexics; www.gingersoftware.com/crawler_agent.htm; support at ginger software dot com)",
        "Mozilla/4.0 (compatible; grub-client-0.3.0; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.0.4; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.0.5; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.0.6; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.0.7; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.1.1; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.2.1; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.3.1; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.3.7; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.4.3; Crawl your own stuff with http://grub.org)",
        "Mozilla/4.0 (compatible; grub-client-1.5.3; Crawl your own stuff with http://grub.org)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US) Speedy Spider (http://www.entireweb.com/about/search_tech/speedy_spider/)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US) Speedy Spider for SpeedyAds (http://www.entireweb.com/about/search_tech/speedy_spider/)",
        "Mozilla/5.0 (compatible; Speedy Spider; http://www.entireweb.com/about/search_tech/speedy_spider/)",
        "Speedy Spider (Entireweb; Beta/1.2; http://www.entireweb.com/about/search_tech/speedyspider/)",
        "Speedy Spider (http://www.entireweb.com/about/search_tech/speedy_spider/)",
        "Mozilla/5.0 (compatible; bnf.fr_bot; +http://bibnum.bnf.fr/robot/bnf.html)",
        "yacybot (-global; amd64 FreeBSD 9.2-RELEASE-p10; java 1.7.0_65; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 2.6.32-042stab111.11; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 2.6.32-042stab116.1; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.10.0-229.4.2.el7.x86_64; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.10.0-229.4.2.el7.x86_64; java 1.8.0_45; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.13.0-61-generic; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.14.32-xxxx-grs-ipv6-64; java 1.8.0_111; Europe/de) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_75; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.19.0-15-generic; java 1.8.0_45-internal; Europe/de) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.2.0-4-amd64; java 1.7.0_65; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.2.0-4-amd64; java 1.7.0_67; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 4.4.0-57-generic; java 9-internal; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Windows 8 6.2; java 1.7.0_55; Europe/de) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Windows 8.1 6.3; java 1.7.0_55; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 FreeBSD 10.3-RELEASE-p7; java 1.7.0_95; GMT/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 FreeBSD 10.3-RELEASE; java 1.8.0_77; GMT/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 2.6.32-042stab093.4; java 1.7.0_65; Etc/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 2.6.32-042stab094.8; java 1.7.0_79; America/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 2.6.32-042stab108.8; java 1.7.0_91; America/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 2.6.32-573.3.1.el6.x86_64; java 1.7.0_85; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.10.0-229.7.2.el7.x86_64; java 1.8.0_45; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.10.0-327.22.2.el7.x86_64; java 1.7.0_101; Etc/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.11.10-21-desktop; java 1.7.0_51; America/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.12.1; java 1.7.0_65; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-042stab093.4; java 1.7.0_79; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-042stab093.4; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-45-generic; java 1.7.0_75; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-74-generic; java 1.7.0_91; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-83-generic; java 1.7.0_95; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-83-generic; java 1.7.0_95; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-85-generic; java 1.7.0_101; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-85-generic; java 1.7.0_95; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.13.0-88-generic; java 1.7.0_101; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.14-0.bpo.1-amd64; java 1.7.0_55; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.14.32-xxxx-grs-ipv6-64; java 1.7.0_75; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16-0.bpo.2-amd64; java 1.7.0_65; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_111; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_75; America/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_75; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_79; Europe/de) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_91; Europe/de) http://yacy.net/bot.html",
        "yacybot (-global; amd64 FreeBSD 9.2-RELEASE-p10; java 1.7.0_65; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 2.6.32-042stab111.11; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 2.6.32-042stab116.1; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (-global; amd64 Linux 3.10.0-229.4.2.el7.x86_64; java 1.7.0_79; Europe/en) http://yacy.net/bot.html",
        "yacybot (/global; amd64 Linux 3.16.0-4-amd64; java 1.7.0_95; Europe/en) http://yacy.net/bot.html",
        "MJ12bot/v1.2.0 (http://majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.2.1; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.2.3; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.2.4; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.2.5; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.3.0; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.3.1; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.3.2; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.3.3; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.0; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.1; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.2; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.3; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.4 (domain ownership verifier); http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.4; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.5; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.6; http://mj12bot.com/)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.7; http://mj12bot.com/)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.7; http://www.majestic12.co.uk/bot.php?+)",
        "Mozilla/5.0 (compatible; MJ12bot/v1.4.8; http://mj12bot.com/)",
        "Mozilla/5.0 (compatible; woriobot +http://worio.com)",
        "Mozilla/5.0 (compatible; woriobot support [at] zite [dot] com +http://zite.com)",
        "Yanga WorldSearch Bot v1.1/beta (http://www.yanga.co.uk/)",
        "Buzzbot/1.0 (Buzzbot; http://www.buzzstream.com; buzzbot@buzzstream.com)",
        "MLBot (www.metadatalabs.com/mlbot)",
        "Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)",
        "Mozilla/5.0 (compatible; YandexWebmaster/2.0; +http://yandex.com/bots)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_1 like Mac OS X) AppleWebKit/600.1.4 (KHTML, like Gecko) Version/8.0 Mobile/12B411 Safari/600.1.4 (compatible; YandexMobileBot/3.0; +http://yandex.com/bots)",
        "Linguee Bot (http://www.linguee.com/bot)",
        "Linguee Bot (http://www.linguee.com/bot; bot@linguee.com)",
        "CyberPatrol SiteCat Webbot (http://www.cyberpatrol.com/cyberpatrolcrawler.asp)",
        "Mozilla/5.0 (Windows NT 5.1; U; Win64; fr; rv:1.8.1) VoilaBot BETA 1.2 (support.voilabot@orange-ftgroup.com)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; fr; rv:1.8.1) VoilaBot BETA 1.2 (support.voilabot@orange-ftgroup.com)",
        "Mozilla/5.0 (compatible; OrangeBot/2.0; support.voilabot@orange.com)",
        "Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)",
        "Mozilla/5.0 (compatible; spbot/1.0; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/1.1; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/1.2; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/2.0.1; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/2.0.2; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/2.0.3; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/2.0.4; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/2.0; +http://www.seoprofiler.com/bot/ )",
        "Mozilla/5.0 (compatible; spbot/2.1; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/3.0; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/3.1; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.1; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.2; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.3; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.4; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.5; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.6; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.7; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.7; +https://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.8; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0.9; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0a; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.0b; +http://www.seoprofiler.com/bot )",
        "Mozilla/5.0 (compatible; spbot/4.1.0; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.2.0; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.3.0; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.4.0; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.4.1; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/4.4.2; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/5.0.1; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/5.0.2; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/5.0.3; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; spbot/5.0; +http://OpenLinkProfiler.org/bot )",
        "Mozilla/5.0 (compatible; Whoiswebsitebot/0.1; +http://www.whoiswebsite.net)",
        "Mozilla/5.0 (compatible; linkdexbot/2.0; +http://www.linkdex.com/about/bots/)",
        "Mozilla/5.0 (compatible; linkdexbot/2.0; +http://www.linkdex.com/bots/)",
        "Mozilla/5.0 (compatible; linkdexbot/2.1; +http://www.linkdex.com/about/bots/)",
        "Mozilla/5.0 (compatible; linkdexbot/2.1; +http://www.linkdex.com/bots/)",
        "Mozilla/5.0 (compatible; linkdexbot/2.2; +http://www.linkdex.com/bots/)",
        "Mozilla/5.0 (compatible; Blekkobot; ScoutJet; +http://blekko.com/about/blekkobot)",
        "Mozilla/5.0 (compatible; Ezooms/1.0; ezooms.bot@gmail.com)",
        "Mozilla/5.0 (compatible; DotBot/1.1; http://www.opensiteexplorer.org/dotbot, help@moz.com)",
        "dotbot",
        "Mozilla/5.0 (compatible; Linux x86_64; Mail.RU_Bot/2.0; +http://go.mail.ru/",
        "Mozilla/5.0 (compatible; Mail.RU_Bot/2.0; +http://go.mail.ru/",
        "Mozilla/5.0 (compatible; discobot/1.0; +http://discoveryengine.com/discobot.html)",
        "Mozilla/5.0 (compatible; discobot/2.0; +http://discoveryengine.com/discobot.html)",
        "mozilla/5.0 (compatible; discobot/1.1; +http://discoveryengine.com/discobot.html)",
        "Mozilla/5.0 (compatible; archive.org_bot/heritrix-1.15.4 +http://www.archive.org)",
        "Mozilla/5.0 (compatible; sukibot_heritrix/3.1.1 +http://suki.ling.helsinki.fi/eng/webmasters.html)",
        "Mozilla/5.0 (compatible; NerdByNature.Bot; http://www.nerdbynature.net/bot)",
        "Mozilla/5.0 (compatible; AhrefsBot/5.2; News; +http://ahrefs.com/robot/)",
        "Mozilla/5.0 (compatible; AhrefsBot/5.2; +http://ahrefs.com/robot/)",
        "Mozilla/5.0 (compatible; AhrefsSiteAudit/5.2; +http://ahrefs.com/robot/)",
        "fuelbot",
        "CrunchBot/1.0 (+http://www.leadcrunch.com/crunchbot)",
        "Mozilla/5.0 (compatible; Go-http-client/1.1; +centurybot9@gmail.com)",
        "Mozilla/5.0 (Windows NT 6.1; rv:38.0) Gecko/20100101 Firefox/38.0 (IndeedBot 1.1)",
        "Mozilla/5.0 (compatible; Mappy/1.0; +http://mappydata.net/bot/)",
        "woobot",
        "ZoominfoBot (zoominfobot at zoominfo dot com)",
        "Mozilla/5.0 (compatible; PrivacyAwareBot/1.1; +http://www.privacyaware.org)",
        "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Multiviewbot",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/45.0.2454.101 Safari/537.36 SWIMGBot",
        "Mozilla/5.0 (compatible; Grobbot/2.2; +https://grob.it)",
        "Mozilla/5.0 (compatible; eright/1.0; +bot@eright.com)",
        "Mozilla/5.0 (compatible; Apercite; +http://www.apercite.fr/robot/index.html)",
        "semanticbot",
        "semanticbot (info@semanticaudience.com)",
        "Aboundex/0.2 (http://www.aboundex.com/crawler/)",
        "Aboundex/0.3 (http://www.aboundex.com/crawler/)",
        "CipaCrawler/3.0 (info@domaincrawler.com; http://www.domaincrawler.com/www.example.com)",
        "CCBot/2.0 (http://commoncrawl.org/faq/)",
        "Mozilla/5.0 (compatible; SeznamBot/3.2-test1-1; +http://napoveda.seznam.cz/en/seznambot-intro/)",
        "Mozilla/5.0 (compatible; SeznamBot/3.2-test1; +http://napoveda.seznam.cz/en/seznambot-intro/)",
        "Mozilla/5.0 (compatible; SeznamBot/3.2-test2; +http://napoveda.seznam.cz/en/seznambot-intro/)",
        "Mozilla/5.0 (compatible; SeznamBot/3.2-test4; +http://napoveda.seznam.cz/en/seznambot-intro/)",
        "Mozilla/5.0 (compatible; SeznamBot/3.2; +http://napoveda.seznam.cz/en/seznambot-intro/)",
        "Mozilla/5.0 (compatible; aiHitBot/2.9; +https://www.aihitdata.com/about)",
        "Mozilla/5.0 (compatible; Yeti/1.1; +http://naver.me/bot)",
        "Wotbox/2.0 (bot@wotbox.com; http://www.wotbox.com)",
        "Wotbox/2.01 (+http://www.wotbox.com/bot/)",
        "DuckDuckBot/1.0; (+http://duckduckgo.com/duckduckbot.html)",
        "DuckDuckBot/1.1; (+http://duckduckgo.com/duckduckbot.html)",
        "www.integromedb.org/Crawler",
        "it2media-domain-crawler/1.0 on crawler-prod.it2media.de",
        "it2media-domain-crawler/2.0",
        "Mozilla/5.0 (compatible; proximic; +http://www.proximic.com/info/spider.php)",
        "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1;  http://www.changedetection.com/bot.html )",
        "WeSEE:Search/0.1 (Alpha, http://www.wesee.com/en/support/bot/)",
        "Mozilla/5.0 (compatible; rogerBot/1.0; UrlCrawler; http://www.seomoz.org/dp/rogerbot)",
        "rogerbot/1.0 (http://moz.com/help/pro/what-is-rogerbot-, rogerbot-crawler+partager@moz.com)",
        "rogerbot/1.0 (http://moz.com/help/pro/what-is-rogerbot-, rogerbot-crawler+shiny@moz.com)",
        "rogerbot/1.0 (http://moz.com/help/pro/what-is-rogerbot-, rogerbot-wherecat@moz.com",
        "rogerbot/1.0 (http://moz.com/help/pro/what-is-rogerbot-, rogerbot-wherecat@moz.com)",
        "rogerbot/1.0 (http://www.moz.com/dp/rogerbot, rogerbot-crawler@moz.com)",
        "rogerbot/1.0 (http://www.seomoz.org/dp/rogerbot, rogerbot-crawler+shiny@seomoz.org)",
        "rogerbot/1.0 (http://www.seomoz.org/dp/rogerbot, rogerbot-crawler@seomoz.org)",
        "rogerbot/1.0 (http://www.seomoz.org/dp/rogerbot, rogerbot-wherecat@moz.com)",
        "rogerbot/1.1 (http://moz.com/help/guides/search-overview/crawl-diagnostics#more-help, rogerbot-crawler+pr2-crawler-05@moz.com)",
        "rogerbot/1.1 (http://moz.com/help/guides/search-overview/crawl-diagnostics#more-help, rogerbot-crawler+pr4-crawler-11@moz.com)",
        "rogerbot/1.1 (http://moz.com/help/guides/search-overview/crawl-diagnostics#more-help, rogerbot-crawler+pr4-crawler-15@moz.com)",
        "rogerbot/1.2 (http://moz.com/help/pro/what-is-rogerbot-, rogerbot-crawler+phaser-testing-crawler-01@moz.com)",
        "psbot-image (+http://www.picsearch.com/bot.html)",
        "psbot-page (+http://www.picsearch.com/bot.html)",
        "psbot/0.1 (+http://www.picsearch.com/bot.html)",
        "Mozilla/5.0 (compatible; GrapeshotCrawler/2.0; +http://www.grapeshot.co.uk/crawler.php)",
        "Mozilla/5.0 (compatible; URLAppendBot/1.0; +http://www.profound.net/urlappendbot.html)",
        "Mozilla/5.0 (compatible; fr-crawler/1.1)",
        "SimpleCrawler/0.1",
        "Twitterbot/0.1",
        "Twitterbot/1.0",
        "cXensebot/1.1a",
        "Mozilla/5.0 (compatible; SMTBot/1.0; +http://www.similartech.com/smtbot)",
        "SMTBot (similartech.com/smtbot)",
        "Mozilla/5.0 (compatible; bnf.fr_bot; +http://www.bnf.fr/fr/outils/a.dl_web_capture_robot.html)",
        "Facebot/1.0",
        "Mozilla/5.0 (compatible; OrangeBot/2.0; support.orangebot@orange.com",
        "Mozilla/5.0 (compatible; memorybot/1.21.14 +http://mignify.com/bot.html)",
        "Mozilla/5.0 (compatible; AdvBot/2.0; +http://advbot.net/bot.html)",
        "Mozilla/5.0 (compatible; MegaIndex.ru/2.0; +https://www.megaindex.ru/?tab=linkAnalyze)",
        "SemanticScholarBot/1.0 (+http://s2.allenai.org/bot.html)",
        "nerdybot",
        "Mozilla/5.0 (compatible; XoviBot/2.0; +http://www.xovibot.net/)",
        "Mozilla/5.0 (compatible; archive.org_bot +http://www.archive.org/details/archive.org_bot)",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/600.2.5 (KHTML, like Gecko) Version/8.0.2 Safari/600.2.5 (Applebot/0.1)",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/600.2.5 (KHTML, like Gecko) Version/8.0.2 Safari/600.2.5 (Applebot/0.1; +http://www.apple.com/go/applebot)",
        "Mozilla/5.0 (compatible; Applebot/0.3; +http://www.apple.com/go/applebot)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 6_0 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10A5376e Safari/8536.25 (compatible; Applebot/0.3; +http://www.apple.com/go/applebot)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_1 like Mac OS X) AppleWebKit/600.1.4 (KHTML, like Gecko) Version/8.0 Mobile/12B410 Safari/600.1.4 (Applebot/0.1; +http://www.apple.com/go/applebot)",
        "Mozilla/5.0 (TweetmemeBot/4.0; +http://datasift.com/bot.html) Gecko/20100101 Firefox/31.0",
        "crawler4j (http://code.google.com/p/crawler4j/)",
        "Mozilla/5.0 (compatible; Findxbot/1.0; +http://www.findxbot.com)",
        "Mozilla/5.0 (compatible; SemrushBot/0.98~bl; +http://www.semrush.com/bot.html)",
        "SEMrushBot",
        "Mozilla/5.0 (compatible; yoozBot-2.2; http://yooz.ir; info@yooz.ir)",
        "Y!J-ASR/0.1 crawler (http://www.yahoo-help.jp/app/answers/detail/p/595/a_id/42716/)",
        "Y!J-BRJ/YATS crawler (http://help.yahoo.co.jp/help/jp/search/indexing/indexing-15.html)",
        "Y!J-PSC/1.0 crawler (http://help.yahoo.co.jp/help/jp/search/indexing/indexing-15.html)",
        "Y!J-BRW/1.0 crawler (http://help.yahoo.co.jp/help/jp/search/indexing/indexing-15.html)",
        "Mozilla/5.0 (iPhone; Y!J-BRY/YATSH crawler; http://help.yahoo.co.jp/help/jp/search/indexing/indexing-15.html)",
        "Mozilla/5.0 (compatible; Y!J SearchMonkey/1.0 (Y!J-AGENT; http://help.yahoo.co.jp/help/jp/search/indexing/indexing-15.html))",
        "Domain Re-Animator Bot (http://domainreanimator.com) - support@domainreanimator.com",
        "AddThis.com robot tech.support@clearspring.com",
        "LivelapBot/0.2 (http://site.livelap.com/crawler)",
        "Livelapbot/0.1",
        "Mozilla/5.0 (compatible; OpenHoseBot/2.1; +http://www.openhose.org/bot.html)",
        "Mozilla/5.0 (compatible; IstellaBot/1.23.15 +http://www.tiscali.it/)",
        "Mozilla/5.0 (compatible; DeuSu/5.0.2; +https://deusu.de/robot.html)",
        "Cliqzbot/0.1 (+http://cliqz.com +cliqzbot@cliqz.com)",
        "Cliqzbot/0.1 (+http://cliqz.com/company/cliqzbot)",
        "Mozilla/5.0 (compatible; Cliqzbot/0.1 +http://cliqz.com/company/cliqzbot)",
        "Mozilla/5.0 (compatible; Cliqzbot/1.0 +http://cliqz.com/company/cliqzbot)",
        "MojeekBot/0.2 (archi; http://www.mojeek.com/bot.html)",
        "Mozilla/5.0 (compatible; MojeekBot/0.2; http://www.mojeek.com/bot.html#relaunch)",
        "Mozilla/5.0 (compatible; MojeekBot/0.2; http://www.mojeek.com/bot.html)",
        "Mozilla/5.0 (compatible; MojeekBot/0.5; http://www.mojeek.com/bot.html)",
        "Mozilla/5.0 (compatible; MojeekBot/0.6; +https://www.mojeek.com/bot.html)",
        "Mozilla/5.0 (compatible; MojeekBot/0.6; http://www.mojeek.com/bot.html)",
        "netEstate NE Crawler (+http://www.sengine.info/)",
        "netEstate NE Crawler (+http://www.website-datenbank.de/)",
        "SafeSearch microdata crawler (https://safesearch.avira.com, safesearch-abuse@avira.com)",
        "Mozilla/5.0 (compatible; Gluten Free Crawler/1.0; +http://glutenfreepleasure.com/)",
        "Mozilla/5.0 (compatible; Sonic/1.0; http://www.yama.info.waseda.ac.jp/~crawler/info.html)",
        "Mozzila/5.0 (compatible; Sonic/1.0; http://www.yama.info.waseda.ac.jp/~crawler/info.html)",
        "Slack-ImgProxy (+https://api.slack.com/robots)",
        "Slack-ImgProxy 0.59 (+https://api.slack.com/robots)",
        "Slack-ImgProxy 0.66 (+https://api.slack.com/robots)",
        "Slack-ImgProxy 1.106 (+https://api.slack.com/robots)",
        "Slack-ImgProxy 1.138 (+https://api.slack.com/robots)",
        "Slack-ImgProxy 149 (+https://api.slack.com/robots)",
        "Mozilla/5.0 (compatible; RankActiveLinkBot; +https://rankactive.com/resources/rankactive-linkbot)",
        "SafeDNSBot (https://www.safedns.com/searchbot)",
        "Mozilla/5.0 (compatible; Veoozbot/1.0; +http://www.veooz.com/veoozbot.html)",
        "Slackbot-LinkExpanding (+https://api.slack.com/robots)",
        "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
        "Mozilla/5.0 (compatible; redditbot/1.0; +http://www.reddit.com/feedback)",
        "datagnionbot (+http://www.datagnion.com/bot.html)",
        "Google-Adwords-Instant (+http://www.google.com/adsbot.html)",
        "Mozilla/5.0 (compatible; adbeat_bot; +support@adbeat.com; support@adbeat.com)",
        "adbeat_bot",
        "Mozilla/5.0 (compatible;contxbot/1.0)",
        "Pinterest/0.2 (+http://www.pinterest.com/bot.html)",
        "Mozilla/5.0 (compatible; electricmonk/3.2.0 +https://www.duedil.com/our-crawler/)",
        "GarlikCrawler/1.2 (http://garlik.com/, crawler@garlik.com)",
        "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/534+ (KHTML, like Gecko) BingPreview/1.0b",
        "Mozilla/5.0 (Windows NT 6.3; WOW64; Trident/7.0; rv:11.0; BingPreview/1.0b) like Gecko",
        "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.2; Trident/6.0;  WOW64;  Trident/6.0;  BingPreview/1.0b)",
        "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0;  WOW64;  Trident/5.0;  BingPreview/1.0b)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53 BingPreview/1.0b",
        "Mozilla/5.0 (compatible; vebidoobot/1.0; +https://blog.vebidoo.de/vebidoobot/",
        "Mozilla/5.0 (compatible; FemtosearchBot/1.0; http://femtosearch.com)",
        "Mozilla/5.0 (compatible; MetaJobBot; http://www.metajob.de/crawler)",
        "DomainStatsBot/1.0 (http://domainstats.io/our-bot)",
        "mindUpBot (datenbutler.de)",
        "Jugendschutzprogramm-Crawler; Info: http://www.jugendschutzprogramm.de",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/40.0.2214.111 Safari/537.36 moatbot",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) AppleWebKit/600.1.3 (KHTML, like Gecko) Version/8.0 Mobile/12A4345d Safari/600.1.4 moatbot",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/44.0.2403.125 Safari/537.36 (compatible; KosmioBot/1.0; +http://kosm.io/bot.html)",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/59.0.3071.109 Chrome/59.0.3071.109 Safari/537.36 PingdomPageSpeed/1.0 (pingbot/2.0; +http://www.pingdom.com/)",
        "Mozilla/5.0 (compatible; pingbot/2.0; +http://www.pingdom.com/)",
        "Mozilla/5.0 (Unknown; Linux x86_64) AppleWebKit/538.1 (KHTML, like Gecko) PhantomJS/2.1.1 Safari/538.1 bl.uk_lddc_renderbot/2.0.0 (+ http://www.bl.uk/aboutus/legaldeposit/websites/websites/faqswebmaster/index.html)",
        "Mozilla/5.0 (compatible; Gowikibot/1.0; +http://www.gowikibot.com)",
        "Mozilla/5.0+(compatible;+PiplBot;+http://www.pipl.com/bot/)",
        "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
        "TelegramBot (like TwitterBot)",
        "Mozilla/5.0 (compatible; Jetslide; +http://jetsli.de/crawler)",
        "Mozilla/5.0 (compatible; NewShareCounts.com/1.0; +http://newsharecounts.com/crawler)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.6) Gecko/20070725 Firefox/2.0.0.6 - James BOT - WebCrawler http://cognitiveseo.com/bot.html",
        "Barkrowler/0.5.1 (experimenting / debugging - sorry for your logs ) http://www.exensa.com/crawl - admin@exensa.com -- based on BuBiNG",
        "Barkrowler/0.7 (+http://www.exensa.com/crawl)",
        "Mozilla/5.0 (compatible; TinEye-bot/1.31; +http://www.tineye.com/crawler.html)",
        "TinEye/1.1 (http://tineye.com/crawler.html)",
        "SocialRankIOBot; http://socialrank.io/about",
        "Mozilla/5.0 (Windows; U; Windows NT 6.0; en-GB; rv:1.0; trendictionbot0.5.0; trendiction search; http://www.trendiction.de/bot; please let us know of any problems; web at trendiction.com) Gecko/20071127 Firefox/3.0.0.11",
        "Ocarinabot",
        "Mozilla/5.0 (compatible; epicbot; +http://www.epictions.com/epicbot)",
        "Mozilla/5.0 (compatible; Primalbot; +https://www.primal.com;)",
        "Mozilla/5.0 (compatible; DuckDuckGo-Favicons-Bot/1.0; +http://duckduckgo.com)",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:49.0) Gecko/20100101 Firefox/49.0 / GnowitNewsbot / Contact information at http://www.gnowit.com",
        "Mozilla/5.0 (Windows NT 6.3;compatible; Leikibot/1.0; +http://www.leiki.com)",
        "@LinkArchiver twitter bot",
        "Mozilla/5.0 (compatible; YaK/1.0; http://linkfluence.com/; bot@linkfluence.com)",
        "Mozilla/5.0 (compatible; PaperLiBot/2.1; http://support.paper.li/entries/20023257-what-is-paper-li)",
        "Mozilla/5.0 (compatible; PaperLiBot/2.1; https://support.paper.li/entries/20023257-what-is-paper-li)",
        "dcrawl/1.0",
        "Mozilla/5.0 (compatible; AndersPinkBot/1.0; +http://anderspink.com/bot.html)",
        "Fyrebot/1.0",
        "Mozilla/5.0 (compatible; EveryoneSocialBot/1.0; support@everyonesocial.com http://everyonesocial.com/)",
        "Mediatoolkitbot (complaints@mediatoolkit.com)",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.13 (KHTML, like Gecko) Chrome/30.0.1599.66 Safari/537.13 Luminator-robots/2.0",
        "Mozilla/5.0 (compatible; ExtLinksBot/1.5 +https://extlinks.com/Bot.html)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; en; rv:1.9.0.13) Gecko/2009073022 Firefox/3.5.2 (.NET CLR 3.5.30729) SurveyBot/2.3 (DomainTools)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 8_3 like Mac OS X) AppleWebKit/600.1.4 (KHTML, like Gecko) Version/8.0 Mobile/12F70 Safari/600.1.4 (compatible; Laserlikebot/0.1)",
        "Mozilla/5.0 (compatible; DnyzBot/1.0)",
        "Mozilla/5.0 (compatible; DnyzBot/1.0) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/64.0.3282.167 Safari/537.36",
        "Mozilla/5.0 (compatible; DnyzBot/1.0) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/64.0.3264.0 Safari/537.36",
        "Mozilla/5.0 (compatible; botify; http://botify.com)",
        "Mozilla/5.0 (compatible; 007ac9 Crawler; http://crawler.007ac9.net/)",
        "Mozilla/5.0 (compatible; BehloolBot/beta; +http://www.webeaver.com/bot)",
        "Mozilla/5.0 (Windows NT 6.1; compatible; BDCbot/1.0; +http://bigweb.bigdatacorp.com.br/faq.aspx) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2272.118 Safari/537.36",
        "Mozilla/5.0 (compatible; ZumBot/1.0; http://help.zum.com/inquiry)",
        "ICC-Crawler/2.0 (Mozilla-compatible; ; http://ucri.nict.go.jp/en/icccrawler.html)",
        "ArchiveTeam ArchiveBot/20170106.02 (wpull 2.0.2)",
        "LCC (+http://corpora.informatik.uni-leipzig.de/crawler_faq.html)",
        "Mozilla/5.0 (compatible; oBot/2.3.1; +http://filterdb.iss.net/crawler/)",
        "BLP_bbot/0.1",
        "Mozilla/5.0 (compatible; BomboraBot/1.0; +http://www.bombora.com/bot)",
        "Companybook-Crawler (+https://www.companybooknetworking.com/)",
        "magpie-crawler/1.1 (U; Linux amd64; en-GB; +http://www.brandwatch.net)",
        "Mozilla/5.0 (compatible; StorygizeBot; http://www.storygize.com)",
        "Mozilla/5.0+(compatible; UptimeRobot/2.0; http://www.uptimerobot.com/)",
        "OutclicksBot/2 +https://www.outclicks.net/agent/VjzDygCuk4ubNmg40ZMbFqT0sIh7UfOKk8s8ZMiupUR",
        "OutclicksBot/2 +https://www.outclicks.net/agent/gIYbZ38dfAuhZkrFVl7sJBFOUhOVct6J1SvxgmBZgCe",
        "OutclicksBot/2 +https://www.outclicks.net/agent/PryJzTl8POCRHfvEUlRN5FKtZoWDQOBEvFJ2wh6KH5J",
        "OutclicksBot/2 +https://www.outclicks.net/agent/p2i4sNUh7eylJF1S6SGgRs5mP40ExlYvsr9GBxVQG6h",
        "Mozilla/5.0 (compatible; seoscanners.net/1; +spider@seoscanners.net)",
        "Hatena::Russia::Crawler/0.01",
        "MauiBot (crawler.feedback+wc@gmail.com)",
        "Mozilla/5.0 (compatible; AlphaBot/3.2; +http://alphaseobot.com/bot.html)",
        "SBL-BOT (http://sbl.net)",
        "IAS crawler (ias_crawler; http://integralads.com/site-indexing-policy/)",
        "Netvibes (crawler/bot; http://www.netvibes.com",
        "Mozilla/5.0 (compatible;acapbot/0.1;treat like Googlebot)",
        "Mozilla/5.0 (compatible;acapbot/0.1.;treat like Googlebot)",
        "Baidu-YunGuanCe-Bot(ce.baidu.com)",
        "Baidu-YunGuanCe-SLABot(ce.baidu.com)",
        "Baidu-YunGuanCe-ScanBot(ce.baidu.com)",
        "Baidu-YunGuanCe-PerfBot(ce.baidu.com)",
        "Baidu-YunGuanCe-VSBot(ce.baidu.com)",
        "bitlybot/3.0 (+http://bit.ly/)",
        "bitlybot/2.0",
        "bitlybot",
        "blogmuraBot (+http://www.blogmura.com)",
        "Bot.AraTurka.com/0.0.1",
        "bot-pge.chlooe.com/1.0.0 (+http://www.chlooe.com/)",
        "Mozilla/5.0 (compatible; BoxcarBot/1.1; +awesome@boxcar.io)",
        "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0;.NET CLR 1.0.3705; ContextAd Bot 1.0)",
        "ContextAd Bot 1.0",
        "Mozilla/5.0 (compatible; Digincore bot; https://www.digincore.com/crawler.html for rules and instructions.)",
        "Flamingo_SearchEngine (+http://www.flamingosearch.com/bot)",
        "g2reader-bot/1.0 (+http://www.g2reader.com/)",
        "Mozilla/5.0 (compatible; imrbot/1.10.8 +http://www.mignify.com)",
        "K7MLWCBot/1.0 (+http://www.k7computing.com)",
        "Kemvibot/1.0 (http://kemvi.com, marco@kemvi.com)",
        "Landau-Media-Spider/1.0(http://bots.landaumedia.de/bot.html)",
        "linkapediabot (+http://www.linkapedia.com)",
        "Mozilla/5.0 (compatible; BLEXBot/1.0; +http://webmeup-crawler.com/)",
        "Mozilla/5.0 (compatible; ZuperlistBot/1.0)",
        "Mozilla/5.0 (compatible; Feedspotbot/1.0; +http://www.feedspot.com/fs/bot)",
        "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.9.1.2) Gecko/20090729 Firefox/3.5.2 (.NET CLR 3.5.30729; Diffbot/0.1; +http://www.diffbot.com)",
        "Mozilla/5.0 (compatible; SEOkicks; +https://www.seokicks.de/robot.html)",
        "Mozilla/5.0 (compatible; tracemyfile/1.0; +bot@tracemyfile.com)",
        "Mozilla/5.0 (compatible; Nimbostratus-Bot/v1.3.2; http://cloudsystemnetworks.com)",
        "AdsTxtCrawler/1.0",
        "TangibleeBot/1.0.0.0 (http://tangiblee.com/bot)",
        "Mozilla/5.0 (compatible; Seekport Crawler; http://seekport.com/)",
        "ZoomBot (Linkbot 1.0 http://suite.seozoom.it/bot.html)",
        "VelenPublicWebCrawler (velen.io)",
        "MoodleBot/1.0",
        "jpg-newsbot/2.0; (+https://vipnytt.no/bots/)",
        "Mozilla/5.0 (compatible; ICBot/0.1; +https://ideasandcode.xyz",
        "Experibot-v2 http://goo.gl/ZAr8wX",
        "Experibot-v3 http://goo.gl/ZAr8wX"
      )

      val counter1 = new AtomicInteger(0)
      val counter2 = new AtomicInteger(0)
      val body1    = """{"message":"hello world1"}"""
      val body2    = """{"message":"hello world2"}"""
      val server1 = TargetService(None, "/api", "application/json", { _ =>
        counter1.incrementAndGet()
        body1
      }).await()
      val server2 = TargetService(None, "/api", "application/json", { _ =>
        counter2.incrementAndGet()
        body2
      }).await()
      val service1 = ServiceDescriptor(
        id = "match-test-bot",
        name = "match-test",
        env = "prod",
        subdomain = "bot",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server1.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingHeaders = Map(
          "User-Agent" -> "^.*(bot|Bot|BOT|bots|Bots|robot|Robot|index|spider|Craw|crawl|wget|Slurp|slurp|BingPreview|Mediapartners-Google|Feedfetcher-Google|APIs-Google|grabber|Grabber).*$"
        )
      )
      val service2 = ServiceDescriptor(
        id = "match-test-bot-2",
        name = "match-test-2",
        env = "prod",
        subdomain = "bot",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server2.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service2).futureValue
      createOtoroshiService(service1).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "bot.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body2

      allBotUserAgents.par.foreach { userAgent =>
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host"       -> "bot.oto.tools",
            "User-Agent" -> userAgent
          )
          .get()
          .futureValue
        if (resp.status != 200) {
          println(s"Not supported: $userAgent")
        }
        resp.status mustBe 200
        resp.body mustBe body1
      }

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
      server1.stop()
      server2.stop()
    }

    "Route only if header is present and matches regex" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "match-test",
        name = "match-test",
        env = "prod",
        subdomain = "match",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingHeaders = Map("X-Session" -> "^(.*?;)?(user=jason)(;.*)?$")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "match.oto.tools"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> "match.oto.tools",
          "X-Session" -> "user=mathieu"
        )
        .get()
        .futureValue
      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> "match.oto.tools",
          "X-Session" -> "user=jason"
        )
        .get()
        .futureValue

      resp1.status mustBe 404
      resp2.status mustBe 404
      resp3.status mustBe 200
      resp3.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if matching root is present" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "matchroot-test",
        name = "matchroot-test",
        env = "prod",
        subdomain = "matchroot",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingRoot = Some("/foo")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/foo/api")
        .withHttpHeaders(
          "Host" -> "matchroot.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Add root to target call" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "root-test",
        name = "root-test",
        env = "prod",
        subdomain = "root",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        root = "/api",
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders(
          "Host" -> "root.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Match wildcard domains" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "wildcard-test",
        name = "wildcard-test",
        env = "prod",
        subdomain = "wild*",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildcard.oto.tools"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildbar.oto.tools"
        )
        .get()
        .futureValue
      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildfoo.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body
      resp2.status mustBe 200
      resp3.body mustBe body
      resp3.status mustBe 200
      resp3.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Validate sec. communication in V1" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService
        .full(
          None,
          "/api",
          "application/json", { r =>
            val state = r.getHeader("Otoroshi-State").get()
            counter.incrementAndGet()
            (200, body, List(RawHeader("Otoroshi-State-Resp", state.value())))
          }
        )
        .await()
      val service = ServiceDescriptor(
        id = "seccom-v1-test",
        name = "seccom-v1-test",
        env = "prod",
        subdomain = "seccom",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccom.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Validate sec. communication in V2" in {
      import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService
        .full(
          None,
          "/api",
          "application/json", { r =>
            val state = r.getHeader("Otoroshi-State").get()
            val tokenBody =
              Try(Json.parse(ApacheBase64.decodeBase64(state.value().split("\\.")(1)))).getOrElse(Json.obj())
            val stateValue = (tokenBody \ "state").as[String]
            val respToken: String = JWT
              .create()
              .withJWTId(IdGenerator.uuid)
              .withAudience("Otoroshi")
              .withClaim("state-resp", stateValue)
              .withIssuedAt(DateTime.now().toDate)
              .withExpiresAt(DateTime.now().plusSeconds(10).toDate)
              .sign(Algorithm.HMAC512("secret"))
            counter.incrementAndGet()
            (200, body, List(RawHeader("Otoroshi-State-Resp", respToken)))
          }
        )
        .await()
      val service = ServiceDescriptor(
        id = "seccom-v1-test",
        name = "seccom-v1-test",
        env = "prod",
        subdomain = "seccom",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        secComVersion = SecComVersion.V2,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccom.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Deny sec. communication in V2" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService
        .full(
          None,
          "/api",
          "application/json", { r =>
            val state = r.getHeader("Otoroshi-State").get()
            counter.incrementAndGet()
            (200, body, List(RawHeader("Otoroshi-State-Resp", state.value())))
          }
        )
        .await()
      val service = ServiceDescriptor(
        id = "seccom-v1-test",
        name = "seccom-v1-test",
        env = "prod",
        subdomain = "seccom",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        secComVersion = SecComVersion.V2,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccom.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 502
      resp1.body.contains("Downstream microservice does not seems to be secured. Cancelling request !") mustBe true

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "stop servers" in {
      deleteOtoroshiService(initialDescriptor).futureValue
      basicTestServer.stop()
    }

    "shutdown" in {
      stopAll()
    }
  }
}
