package otoroshi.utils.http

import otoroshi.env.Env
import play.api.libs.ws.{DefaultWSCookie, WSCookie, WSResponse}
import play.shaded.ahc.io.netty.handler.codec.http.cookie.Cookie

import scala.util.{Failure, Success, Try}

object ResponseImplicits {

  implicit class EnhancedWSResponse(val response: WSResponse) extends AnyVal {

    import play.shaded.ahc.io.netty.handler.codec.http.HttpHeaderNames.{SET_COOKIE, SET_COOKIE2}
    import play.shaded.ahc.io.netty.handler.codec.http.cookie.ClientCookieDecoder

    private def asCookie(c: Cookie, env: Env): WSCookie = {
      DefaultWSCookie(
        name = Option(c.name).getOrElse(""),
        value = Option(c.value).getOrElse(""),
        domain = Option(c.domain),
        path = Option(c.path),
        maxAge = Option(c.maxAge).filterNot(_ < 0),
        secure = c.isSecure,
        httpOnly = c.isHttpOnly
      )
    }
    private def buildCookies(
        headers: Map[String, scala.collection.Seq[String]],
        env: Env
    ): scala.collection.Seq[WSCookie] = {
      val option = headers.get(SET_COOKIE2.toString).orElse(headers.get(SET_COOKIE.toString))
      option
        .map { cookiesHeaders =>
          cookiesHeaders.flatMap { header =>
            val parsed = Try(ClientCookieDecoder.LAX.decode(header)) match {
              case Success(value) if value == null =>
                env.logger.error(s"error parsing null cookie from: '${header}', ignoring it !")
                None
              case Success(value)                  => Some(value)
              case Failure(ex)                     =>
                env.logger.error(s"error parsing cookie from: '${header}', ignoring it !", ex)
                None
            }
            parsed.filterNot(_ == null).map { parsed =>
              asCookie(parsed, env)
            }
          }
        }
        .getOrElse(Seq.empty)
    }

    def safeCookies(env: Env): scala.collection.Seq[WSCookie] = try {
      response.cookies
    } catch {
      case t: Throwable
          if t.getMessage.contains(
            "Cannot invoke \"play.shaded.ahc.io.netty.handler.codec.http.cookie.Cookie.name()\" because \"c\" is null"
          ) =>
        Try(buildCookies(response.headers, env)).getOrElse(Seq.empty[WSCookie])
      case t: Throwable =>
        env.logger.error("error while parsing http client response cookies, ignoring them", t)
        Seq.empty[WSCookie]
    }
  }
}
