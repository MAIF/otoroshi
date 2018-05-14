package utils

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.model.Uri
import play.api.mvc.RequestHeader

import scala.util.Try

object RequestImplicits {

  private val uriCache = new ConcurrentHashMap[String, String]()

  implicit class EnhancedRequestHeader(val requestHeader: RequestHeader) extends AnyVal {
    def relativeUri: String = {
      val uri = requestHeader.uri
      uriCache.computeIfAbsent(uri, _ => {
        // println(s"computing uri for $uri")
        Try(Uri(uri).toRelative.toString()).getOrElse(uri)
      })
    }
  }
}
