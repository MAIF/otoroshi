package otoroshi.plugins.security

import akka.stream.Materializer
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class SecurityTxt extends RequestTransformer {

  override def name: String = "Security Txt"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "SecurityTxt" -> Json.obj(
          "Contact"             -> "contact@foo.bar",
          "Encryption"          -> "https://...",
          "Acknowledgments"     -> "https://...",
          "Preferred-Languages" -> "en, fr",
          "Policy"              -> "https://...",
          "Hiring"              -> "https://...",
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin exposes a special route `/.well-known/security.txt` as proposed at [https://securitytxt.org/](https://securitytxt.org/).
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "SecurityTxt": {
      |    "Contact": "contact@foo.bar", // mandatory, a link or e-mail address for people to contact you about security issues
      |    "Encryption": "http://url-to-public-key", // optional, a link to a key which security researchers should use to securely talk to you
      |    "Acknowledgments": "http://url", // optional, a link to a web page where you say thank you to security researchers who have helped you
      |    "Preferred-Languages": "en, fr, es", // optional
      |    "Policy": "http://url", // optional, a link to a policy detailing what security researchers should do when searching for or reporting security issues
      |    "Hiring": "http://url", // optional, a link to any security-related job openings in your organisation
      |  }
      |}
      |```
    """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/security.txt") => {
        val config = ctx.configFor("SecurityTxt")
        val host = s"https://${ctx.descriptor.toHost}"
        val contact =
          (config \ "contact").asOpt[String].orElse((config \ "Contact").asOpt[String]).map(c => s"Contact: $c\n")
        val values = Seq("Encryption", "Acknowledgments", "Preferred-Languages", "Policy", "Hiring").map { key =>
          (config \ key.toLowerCase())
            .asOpt[String]
            .orElse((config \ key).asOpt[String])
            .map { fromConfig =>
              if (key == "Preferred-Languages") {
                s"$key: $fromConfig\n"
              } else {
                if (fromConfig.startsWith("http")) {
                  s"$key: $fromConfig\n"
                } else if (fromConfig.contains("@")) {
                  s"$key: $fromConfig\n"
                } else {
                  s"$key: $host/$fromConfig\n"
                }
              }
            }
            .getOrElse("")
        }
        contact match {
          case None => Left(Results.InternalServerError("Contact missing !!!")).future
          case Some(cont) => {
            Left(
              Results
                .Ok((Seq(cont) ++ values ++ Seq(s"Canonical: ${host}/.well-known/security.txt\n")).mkString(""))
                .as("text/plain")
            ).future
          }
        }
      }
      case (_, _) => Right(ctx.otoroshiRequest).future
    }
  }
}
