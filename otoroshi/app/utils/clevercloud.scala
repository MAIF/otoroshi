package utils

import java.util.Base64

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import com.google.common.base.Charsets
import env.Env
import models.GlobalConfig
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue}
import play.utils.UriEncoding
import utils.CleverCloudClient.CleverSettings

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

object CleverCloudClient {

  object Keys {
    val oauth_consumer_key     = "oauth_consumer_key"
    val oauth_signature_method = "oauth_signature_method"
    val oauth_signature        = "oauth_signature"
    val oauth_timestamp        = "oauth_timestamp"
    val oauth_nonce            = "oauth_nonce"
    val oauth_token            = "oauth_token"
    val oauth_callback         = "oauth_callback"
  }

  case class CleverSettings(
      apiConsumerKey: String,
      apiConsumerSecret: String,
      apiAuthToken: UserTokens,
      apiHost: String = "https://api.clever-cloud.com/v2",
      oauthAccessTokenUrl: String = "https://api.clever-cloud.com/v2/oauth/access_token",
      requestTokenUrl: String = "https://api.clever-cloud.com/v2/oauth/request_token"
  )

  case class UserTokens(token: String, secret: String)

  case class Hmac(sharedKey: String) {

    private lazy val encoder = Base64.getUrlEncoder
    private lazy val key     = new SecretKeySpec(sharedKey.getBytes(Charsets.UTF_8), "HmacSHA512")

    private lazy val mac = {
      val a = Mac.getInstance("HmacSHA512")
      a.init(key)
      a
    }

    def signString(in: String): String = new String(encoder.encode(sign(in.getBytes(Charsets.UTF_8))), Charsets.UTF_8)

    def sign(in: Array[Byte]): Array[Byte] = mac.synchronized { mac.doFinal(in) }

    def verifyString(expected: String, in: String): Boolean = signString(in).equals(expected)
  }

  sealed trait HttpMethod
  case object GET    extends HttpMethod
  case object POST   extends HttpMethod
  case object PUT    extends HttpMethod
  case object DELETE extends HttpMethod

  def apply(env: Env, config: GlobalConfig, settings: CleverSettings, orgaId: String): CleverCloudClient =
    new CleverCloudClient(env, config, settings, orgaId)

}

class CleverCloudClient(env: Env, config: GlobalConfig, val settings: CleverSettings, val orgaId: String) {

  import utils.http.Implicits._

  import CleverCloudClient._

  lazy val logger = Logger("otoroshi-clevercloud-client")

  def getOauthParams(tokenSecret: Option[String] = None): Map[String, String] =
    Map(
      Keys.oauth_consumer_key     -> settings.apiConsumerKey,
      Keys.oauth_signature_method -> "PLAINTEXT",
      Keys.oauth_signature        -> s"${settings.apiConsumerSecret}&${tokenSecret.getOrElse("")}",
      Keys.oauth_timestamp        -> s"${Math.floor(System.currentTimeMillis() / 1000).toInt}",
      Keys.oauth_nonce            -> s"${Random.nextInt(1000000000)}"
    )

  def cleverCall(method: HttpMethod = CleverCloudClient.GET,
                 endpoint: String,
                 queryParams: Seq[(String, String)] = Seq.empty[(String, String)],
                 body: Map[String, List[String]] = Map.empty) = {
    val url = s"${settings.apiHost}$endpoint"

    val params: String = simpleAuthorization(method, url, queryParams, settings.apiAuthToken)
      .map { case (k, v) => s"""$k="$v"""" }
      .mkString(",")

    val builder = env.Ws
      .url(url)
      .withHttpHeaders("Authorization" -> params)
      .withMaybeProxyServer(config.proxies.clevercloud)
      .withQueryStringParameters(queryParams: _*)

    // logger.debug(
    //   s"""
    //      |Authorization: $params
    //    """.stripMargin)

    method match {
      case GET    => builder.get()
      case POST   => builder.post(body)
      case DELETE => builder.delete()
      case PUT    => builder.withHttpHeaders("Content-Type" -> "application/json").put("")
    }

  }

  private def simpleAuthorization(httpMethod: HttpMethod,
                                  url: String,
                                  queryParams: Seq[(String, String)],
                                  userTokens: UserTokens) =
    authorization(httpMethod, url, getOauthParams(Some(userTokens.secret)), queryParams, userTokens)

  private def hmacAuthorization(httpMethod: HttpMethod,
                                url: String,
                                queryParams: Seq[(String, String)],
                                userTokens: UserTokens) = {
    val oauthToken = getOauthParams(Some(userTokens.secret)) + (Keys.oauth_signature_method -> "HMAC-SHA512")
    authorization(httpMethod, url, oauthToken, queryParams, userTokens)
  }

  private def authorization(httpMethod: HttpMethod,
                            url: String,
                            oauthParams: Map[String, String],
                            queryParams: Seq[(String, String)],
                            userTokens: UserTokens): Seq[(String, String)] = {

    val mParams: Map[String, String] = queryParams.toMap ++ oauthParams + (Keys.oauth_token -> userTokens.token)
    val params: Seq[(String, String)] =
      mParams.map { case (k, v) => (k, v) }.toSeq.filter { case (k, v) => k != Keys.oauth_signature }

    val signature =
      if (oauthParams(Keys.oauth_signature_method) == "HMAC-SHA512") {
        signRequest(httpMethod, url, params, userTokens)
      } else {
        oauthParams(Keys.oauth_signature)
      }

    // logger.debug(s"Signature: $signature, Signature meth ${oauthParams(Keys.oauth_signature_method)}")

    Seq(
      "OAuth realm"            -> s"${settings.apiHost}/oauth",
      "oauth_consumer_key"     -> settings.apiConsumerKey,
      "oauth_token"            -> userTokens.token,
      "oauth_signature_method" -> oauthParams(Keys.oauth_signature_method),
      "oauth_signature"        -> signature,
      "oauth_timestamp"        -> oauthParams(Keys.oauth_timestamp),
      "oauth_nonce"            -> oauthParams(Keys.oauth_nonce)
    )
  }

  def signRequest(verb: HttpMethod, path: String, params: Seq[(String, String)], key: UserTokens): String = {

    val strKey = Seq(settings.apiConsumerKey, key.secret)
      .map(UriEncoding.encodePathSegment(_, Charsets.UTF_8))
      .mkString("&")

    Hmac(strKey).signString(prepareUrlToSign(verb, path, params))
  }

  def prepareUrlToSign(verb: HttpMethod, path: String, params: Seq[(String, String)]): String = {
    val toSign = Seq(verb, path, prepareParameters(params))
      .map(p => UriEncoding.encodePathSegment(p.toString, Charsets.UTF_8))
      .mkString("&")

    // logger.debug("to sign : " + toSign)
    toSign
  }

  def prepareParameters(params: Seq[(String, String)]): String = {
    val str = params
      .map { case (k, v) => (encode(k), encode(v)) }
      .sortBy(identity)
      .map { case (k, v) => s"$k=$v" }
      .mkString("&")
    // logger.debug("params : " + str)
    str
  }

  def encode(param: String): String = UriEncoding.encodePathSegment(param, Charsets.UTF_8)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def summary()(implicit ec: ExecutionContext): Future[JsObject] =
    cleverCall(endpoint = "/summary").fast.map(_.json.as[JsObject])

  def app(orga: String, id: String)(implicit ec: ExecutionContext): Future[JsObject] =
    cleverCall(endpoint = s"/organisations/$orga/applications/$id").fast.map(_.json.as[JsObject])

  def apps(orga: String)(implicit ec: ExecutionContext): Future[JsArray] =
    cleverCall(endpoint = s"/organisations/$orga/applications").fast.map(_.json.as[JsArray])

  def addon(orga: String, id: String)(implicit ec: ExecutionContext): Future[JsObject] =
    cleverCall(endpoint = s"/organisations/$orga/addons/$id").fast.map(_.json.as[JsObject])

  def appTags(orga: String, id: String)(implicit ec: ExecutionContext): Future[JsValue] =
    cleverCall(endpoint = s"/organisations/$orga/applications/$id/tags").fast.map(_.json.as[JsValue])

  def createTagsForApp(orga: String, id: String, tags: Seq[String])(implicit ec: ExecutionContext): Future[NotUsed] =
    Future
      .sequence(tags.map { tag =>
        cleverCall(method = CleverCloudClient.PUT, endpoint = s"/organisations/$orga/applications/$id/tags/$tag")
          .andThen {
            case Failure(e) => logger.error(s"Error while creating tag $tag on app $id", e)
            case Success(r) =>
              r.ignore()
              logger.error(s"Result of creating tag $tag on app $id: ${r.status}")
          }
      })
      .map(_ => NotUsed)

  def deleteTagsForApp(orga: String, id: String)(implicit ec: ExecutionContext): Future[NotUsed] =
    cleverCall(endpoint = s"/organisations/$orga/applications/$id/tags").fast.map(_.json.as[JsArray]).flatMap { seq =>
      FastFuture
        .sequence(seq.value.map(_.as[String]).map { tag =>
          cleverCall(method = CleverCloudClient.DELETE, endpoint = s"/organisations/$orga/applications/$id/tags/$tag").andThen {
            case Failure(e) => logger.error(s"Error while deleting tag $tag on app $id", e)
            case Success(r) =>
              r.ignore()
              logger.error(s"Result of deleting tag $tag on app $id: ${r.status}")
          }
        })
        .map(_ => NotUsed)
    }

  def createTagsForAddon(orga: String, id: String, tags: Seq[String])(implicit ec: ExecutionContext): Future[NotUsed] =
    FastFuture
      .sequence(tags.map { tag =>
        cleverCall(method = CleverCloudClient.PUT, endpoint = s"/organisations/$orga/addons/$id/tags/$tag").andThen {
          case Failure(e) => logger.error(s"Error while creating tag $tag on app $id", e)
          case Success(r) =>
            r.ignore()
            logger.error(s"Result of creating tag $tag on app $id: ${r.status}")
        }
      })
      .map(_ => NotUsed)

  def deleteTagsForAddon(orga: String, id: String)(implicit ec: ExecutionContext): Future[NotUsed] =
    cleverCall(endpoint = s"/organisations/$orga/addons/$id/tags").fast.map(_.json.as[JsArray]).flatMap { seq =>
      FastFuture
        .sequence(seq.value.map(_.as[String]).map { tag =>
          cleverCall(method = CleverCloudClient.DELETE, endpoint = s"/organisations/$orga/addons/$id/tags/$tag").andThen {
            case Failure(e) => logger.error(s"Error while deleting tag $tag on app $id", e)
            case Success(r) =>
              r.ignore()
              logger.error(s"Result of deleting tag $tag on app $id: ${r.status}")
          }
        })
        .map(_ => NotUsed)
    }

  def addonTags(orga: String, id: String)(implicit ec: ExecutionContext): Future[JsValue] =
    cleverCall(endpoint = s"/organisations/$orga/addons/$id/tags").fast.map(_.json.as[JsValue])

  def appEnv(orga: String, id: String)(implicit ec: ExecutionContext): Future[Map[String, String]] =
    cleverCall(endpoint = s"/organisations/$orga/applications/$id/env").fast
      .map(_.json.as[JsArray].value.map(obj => ((obj \ "name").as[String], (obj \ "value").as[String])).toMap)

}
