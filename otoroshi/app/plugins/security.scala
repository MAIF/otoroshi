package otoroshi.plugins.security

import akka.stream.Materializer
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.libs.json.JsValue
import play.api.mvc.{Result, Results}
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class SecurityTxt extends RequestTransformer {

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/security.txt") => {
        val config = (ctx.config \ "securityTxt").asOpt[JsValue]
          .orElse((ctx.globalConfig \ "securityTxt").asOpt[JsValue])
          .getOrElse(ctx.config)
        val host = s"https://${ctx.descriptor.toHost}"
        val contact = (config \ "contact").asOpt[String].map(c => s"Contact: $c\n")
        val values = Seq("Encryption", "Acknowledgments", "Preferred-Languages", "Policy", "Hiring").map { key =>
          (config \ key.toLowerCase()).asOpt[String].orElse((config \ key).asOpt[String]).map { fromConfig =>
            if (key == "Preferred-Languages") {
              s"$key: $fromConfig\n"
            } else {
              if (fromConfig.startsWith("http")) {
                s"$key: $fromConfig\n"
              } else {
                s"$key: $host/$fromConfig\n"
              }
            }
          }.getOrElse("")
        }
        contact match {
          case None => Left(Results.InternalServerError("Contact missing !!!")).future
          case Some(cont) => {
            Left(Results.Ok((Seq(cont) ++ values ++ Seq(s"Canonical: ${host}/.well-known/security.txt\n")).mkString("")).as("text/plain")).future
          }
        }
      }
      case (_, _) => Right(ctx.otoroshiRequest).future
    }
  }
}