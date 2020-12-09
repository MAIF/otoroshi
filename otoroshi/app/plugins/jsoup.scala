package otoroshi.plugins.react

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import otoroshi.script.{HttpResponse, RequestTransformer, TransformerResponseBodyContext, TransformerResponseContext}
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

class ReactUpdater extends RequestTransformer {

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val newHeaders = ctx.otoroshiResponse.headers.-("Content-Length").-("content-length").+("Transfer-Encoding" -> "chunked")
    Future.successful(Right(ctx.otoroshiResponse.copy(headers = newHeaders)))
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    ctx.rawResponse.headers.get("Content-Type").orElse(ctx.rawResponse.headers.get("content-type")) match {
      case Some(ctype) if ctype.contains("text/html") => {
        Source.future(
          ctx.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
            val body = bodyRaw.utf8String
            val doc = Jsoup.parse(body)
            doc.head().insertChildren(-1, new Element("script").attr("src", "https://unpkg.com/react@17.0.1/umd/react.production.min.js"))
            ByteString(doc.toString)
          }
        )
      }
      case _ => ctx.body
    }
  }
}