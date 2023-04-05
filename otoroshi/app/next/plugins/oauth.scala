package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.google.common.base.Charsets
import otoroshi.auth.Oauth1AuthModule.encodeURI
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginConfig, NgPluginHttpRequest, NgPluginVisibility, NgRequestTransformer, NgStep, NgTransformerRequestContext}
import otoroshi.script.{HttpRequest, HttpResponse, RequestTransformer, TransformerRequestContext, TransformerResponseContext}
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.Logger
import play.api.libs.json.{Format, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import play.utils.UriEncoding

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

object OAuth1Caller {

  object Keys {
    val consumerKey = "oauth_consumer_key"
    val consumerSecret = "oauth_consumer_secret"
    val signatureMethod = "oauth_signature_method"
    val signature = "oauth_signature"
    val timestamp = "oauth_timestamp"
    val nonce = "oauth_nonce"
    val token = "oauth_token"
    val tokenSecret = "oauth_token_secret"
  }

  val Algo = Map(
    "HMAC-SHA1" -> "HmacSHA1",
    "HMAC-SHA256" -> "HmacSHA256",
    "HMAC-SHA384" -> "HmacSHA384",
    "HMAC-SHA512" -> "HmacSHA512",
    "TEXTPLAIN" -> "TEXTPLAIN"
  )
}

case class OAuth1CallerConfig(
    consumerKey: Option[String] = None,
    consumerSecret: Option[String] = None,
    token: Option[String] = None,
    tokenSecret: Option[String] = None,
    algo: Option[String] = None) extends NgPluginConfig {
  override def json: JsValue = OAuth1CallerConfig.format.writes(this)
}

object OAuth1CallerConfig {
  val format: Format[OAuth1CallerConfig] = new Format[OAuth1CallerConfig] {
    override def writes(o: OAuth1CallerConfig): JsValue = Json.obj(
      "consumerKey" -> o.consumerKey,
      "consumerSecret" -> o.consumerSecret,
      "token" -> o.token,
      "tokenSecret" -> o.tokenSecret,
      "algo" -> o.algo
    )

    override def reads(json: JsValue): JsResult[OAuth1CallerConfig] = Try {
      OAuth1CallerConfig(
        consumerKey = json.select("consumerKey").asOpt[String],
        consumerSecret = json.select("consumerSecret").asOpt[String],
        token = json.select("token").asOpt[String],
        tokenSecret = json.select("tokenSecret").asOpt[String],
        algo = json.select("algo").asOpt[String],
      )
    } match {
      case Failure(e) => JsError(e.getMessage())
      case Success(v) => JsSuccess(v)
    }
  }
}

class OAuth1Caller extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-oauth1-caller-plugin")

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def isTransformRequestAsync: Boolean = false
  override def isTransformResponseAsync: Boolean = false
  override def transformsError: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = OAuth1CallerConfig().some
  override def name: String = "OAuth1 caller"
  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call api that are authenticated using OAuth1.
         | Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
         | or via the configuration of this plugin.```
      """.stripMargin
    )
  }

  private def signRequest(
                           verb: String,
                           path: String,
                           params: Seq[(String, String)],
                           consumerSecret: String,
                           oauthTokenSecret: String,
                           algo: String
                         ): String = {
    val sortedEncodedParams = encodeURI(
      params.sortBy(_._1).map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")
    )
    val encodedURL          = encodeURI(path)

    val base      = s"$verb&$encodedURL&$sortedEncodedParams"
    val key       = s"${encodeURI(consumerSecret)}&${Some(oauthTokenSecret).map(encodeURI).getOrElse("")}"
    val signature = Base64.getEncoder.encodeToString(Signatures.hmac(OAuth1Caller.Algo(algo), base, key))

    encodeURI(signature)
  }

  private def encode(param: String): String = UriEncoding.encodePathSegment(param, Charsets.UTF_8)

  def prepareParameters(params: Seq[(String, String)]): String = params
    .map { case (k, v) => (encode(k), encode(v)) }
    .sortBy(identity)
    .map { case (k, v) => s"$k=$v" }
    .mkString("&")

  def getOauthParams(
                      consumerKey: String,
                      consumerSecret: String,
                      tokenSecret: Option[String] = None,
                      signatureMethod: String
                    ): Map[String, String] =
    Map(
      OAuth1Caller.Keys.consumerKey     -> consumerKey,
      OAuth1Caller.Keys.signatureMethod -> signatureMethod,
      OAuth1Caller.Keys.signature       -> s"${encodeURI(consumerSecret + "&" + tokenSecret.getOrElse(""))}",
      OAuth1Caller.Keys.timestamp       -> s"${Math.floor(System.currentTimeMillis() / 1000).toInt}",
      OAuth1Caller.Keys.nonce           -> s"${Random.nextInt(1000000000)}"
    )

  private def authorization(
                             httpMethod: String,
                             url: String,
                             oauthParams: Map[String, String],
                             queryParams: Map[String, String],
                             consumerKey: String,
                             consumerSecret: String,
                             oauthToken: String,
                             oauthTokenSecret: String
                           ): Seq[(String, String)] = {
    val params: Seq[(String, String)] =
      (queryParams ++ oauthParams + (OAuth1Caller.Keys.token -> oauthToken))
        .map { case (k, v) => (k, v) }
        .toSeq
        .filter { case (k, _) => k != "oauth_signature" }

    val signature =
      if (oauthParams(OAuth1Caller.Keys.signatureMethod) != "TEXTPLAIN")
        signRequest(httpMethod, url, params, consumerSecret, oauthTokenSecret, oauthParams(OAuth1Caller.Keys.signatureMethod))
      else
        oauthParams(OAuth1Caller.Keys.signature)

    Seq(
      OAuth1Caller.Keys.consumerKey     -> consumerKey,
      OAuth1Caller.Keys.token           -> oauthToken,
      OAuth1Caller.Keys.signatureMethod -> oauthParams(OAuth1Caller.Keys.signatureMethod),
      OAuth1Caller.Keys.timestamp       -> oauthParams(OAuth1Caller.Keys.timestamp),
      OAuth1Caller.Keys.nonce           -> oauthParams(OAuth1Caller.Keys.nonce),
      OAuth1Caller.Keys.signature       -> signature
    )
  }

  private def getOrElse(config: Option[String], metadata: Map[String, String], key: String): Either[Result, String] = {
    metadata.get(key) match {
      case Some(value) => Right(value)
      case None        =>
        config match {
          case None        => Left(BadRequest(Json.obj("error" -> "Bad parameters")))
          case Some(value) => Right(value)
        }
    }
  }

  override def transformRequestSync(ctx: NgTransformerRequestContext)
                                   (implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val oAuth1CallerConfig =
      ctx.cachedConfig(internalName)(OAuth1CallerConfig.format).getOrElse(OAuth1CallerConfig())

    val metadata         = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.metadata).getOrElse(Map.empty)
    val consumerKey      = getOrElse(oAuth1CallerConfig.consumerKey, metadata, OAuth1Caller.Keys.consumerKey)
    val consumerSecret   = getOrElse(oAuth1CallerConfig.consumerSecret, metadata, OAuth1Caller.Keys.consumerSecret)
    val oauthToken       = getOrElse(oAuth1CallerConfig.token, metadata, OAuth1Caller.Keys.token)
    val oauthTokenSecret = getOrElse(oAuth1CallerConfig.tokenSecret, metadata, OAuth1Caller.Keys.tokenSecret)

    (consumerKey, consumerSecret, oauthToken, oauthTokenSecret) match {
      case (Right(key), Right(secret), Right(token), Right(tokenSecret)) =>
        val signature_method = oAuth1CallerConfig.algo.getOrElse("HmacSHA512")

        if (logger.isDebugEnabled) {
          logger.debug(s"Algo used : $signature_method")
        }

        val inParams = getOauthParams(key, secret, Some(tokenSecret), signature_method)

        val params: String = authorization(
          ctx.otoroshiRequest.method,
          ctx.otoroshiRequest.url,
          inParams,
          ctx.otoroshiRequest.queryString.map(_.asInstanceOf[Map[String, String]]).getOrElse(Map.empty[String, String]),
          key,
          secret,
          token,
          tokenSecret
        )
          .map { case (k, v) => s"""$k="$v"""" }
          .mkString(",")


        ctx.otoroshiRequest
          .copy(headers = ctx.otoroshiRequest.headers + ("Authorization" -> s"OAuth $params"))
          .right

      case _ =>
        if (logger.isDebugEnabled) {
          logger.debug(
            s"""
               |Consumer key : ${consumerKey.getOrElse("missing")}
               |OAuth token : ${oauthToken.getOrElse("missing")}
               |OAuth token secret : ${oauthTokenSecret.getOrElse("missing")}
               |""".stripMargin)
        }
        BadRequest(Json.obj("error" -> "Bad parameters"))
        .left
    }
  }
}

