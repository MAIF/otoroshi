package otoroshi.utils.body

import play.api.mvc.RequestHeader

object BodyUtils {

  def hasBody(request: RequestHeader): Boolean = hasBodyWithoutLength(request)._1

  def hasBodyWithoutLength(request: RequestHeader): (Boolean, Boolean) = {
    hasBodyWithoutLengthGen(
      request.method.toUpperCase(),
      request.headers.get("Content-Length"),
      request.headers.get("Content-Type"),
      request.headers.get("Transfer-Encoding"),
    )
  }

  def hasBodyGen(method: String, clength: Option[String], ctype: Option[String], transferEncoding: Option[String]): Boolean =
    hasBodyWithoutLengthGen(method, clength, ctype, transferEncoding)._1

  def hasBodyWithoutLengthGen(method: String, clength: Option[String], ctype: Option[String], transferEncoding: Option[String]): (Boolean, Boolean) = {
    (method.toUpperCase(), clength) match {
      case ("GET", Some(_))                    => (true, false)
      // this one when trying to perform a GET with an empty body and a content-type. Play strips the content-length that should be 0. So it's not the best way but we can't do something else
      case ("GET", None) if ctype.isDefined    => (true, true)
      case ("GET", None) if transferEncoding.contains("chunked") => (true, true)
      case ("GET", None)                       => (false, false)
      case ("HEAD", Some(_))                   => (true, false)
      // this one when trying to perform a HEAD with an empty body and a content-type. Play strips the content-length that should be 0. So it's not the best way but we can't do something else
      case ("HEAD", None) if ctype.isDefined   => (true, true)
      case ("HEAD", None) if transferEncoding.contains("chunked") => (true, true)
      case ("HEAD", None)                      => (false, false)
      case ("PATCH", _)                        => (true, false)
      case ("POST", _)                         => (true, false)
      case ("PUT", _)                          => (true, false)
      case ("QUERY", _)                        => (true, false)
      case ("DELETE", Some(_))                 => (true, false)
      // this one when trying to perform a DELETE with an empty body and a content-type. Play strips the content-length that should be 0. So it's not the best way but we can't do something else
      case ("DELETE", None) if ctype.isDefined => (true, true)
      case ("DELETE", None) if transferEncoding.contains("chunked") => (true, true)
      case ("DELETE", None)                    => (false, false)
      case _                                   => (true, false)
    }
  }
}
