package controllers

import actions.ApiAction
import akka.util.ByteString
import env.Env
import otoroshi.ssl.pki.models.GenCsrQuery
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}
import ssl.Cert
import utils.future.Implicits._

import scala.concurrent.Future
import scala.concurrent.duration._

class PkiController(ApiAction: ApiAction,  cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  private val sourceBodyParser = BodyParser("PkiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  private val logger = Logger("otoroshi-pki")

  private def findCertificateByIdOrSerialNumber(id: String): Future[Option[Cert]] = {
    env.datastores.certificatesDataStore.findById(id).flatMap {
      case None => env.datastores.certificatesDataStore.findAll().map(certs => certs.find(_.serialNumber.get == id).orElse(certs.find(_.serialNumberLng.get == id.toLong)))
      case Some(cert) => Some(cert).future
    }.map(_.filter(_.ca))
  }

  def genKeyPair() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      env.pki.genKeyPair(body).flatMap {
        case Left(err) => BadRequest(Json.obj("error" -> err)).future
        case Right(kp) =>
          val serialNumber = env.snowflakeGenerator.nextId()
          env.pki.genSelfSignedCert(GenCsrQuery(
            subject = Some(s"CN=keypair-$serialNumber"),
            duration = (10 * 365).days,
            existingKeyPair = Some(kp.keyPair),
            existingSerialNumber = Some(serialNumber)
          )).flatMap {
            case Left(err) => BadRequest(Json.obj("error" -> err)).future
            case Right(resp) =>
              val _cert = resp.toCert
              val cert = _cert.copy(name = s"KeyPair - $serialNumber", description = s"Public / Private key pair - $serialNumber", keypair = true)
              cert.save().map(_ => Ok(kp.json.as[JsObject] ++ Json.obj("certId" -> cert.id)))
          }
      }
    }
  }

  def genSelfSignedCA() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      env.pki.genSelfSignedCA(body).flatMap {
        case Left(err) => BadRequest(Json.obj("error" -> err)).future
        case Right(kp) =>
          val cert = kp.toCert
          cert.save().map(_ => Ok(kp.json.as[JsObject] ++ Json.obj("certId" -> cert.id)))
      }
    }
  }

  def genSelfSignedCert() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      env.pki.genSelfSignedCert(body).flatMap {
        case Left(err) => BadRequest(Json.obj("error" -> err)).future
        case Right(kp) =>
          val cert = kp.toCert
          cert.save().map(_ => Ok(kp.json.as[JsObject] ++ Json.obj("certId" -> cert.id)))
      }
    }
  }

  def signCert(ca: String) = ApiAction.async(sourceBodyParser) { ctx =>
    val duration = ctx.request.getQueryString("duration").map(_.toLong.millis).getOrElse(365.days)
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      findCertificateByIdOrSerialNumber(ca).flatMap {
        case None => NotFound(Json.obj("error" -> "ca not found !")).future
        case Some(cacert) => env.pki.signCert(body, duration, cacert.certificate.get, cacert.cryptoKeyPair.getPrivate, None).map {
          case Left(err) => BadRequest(Json.obj("error" -> err))
          case Right(kp) => Ok(kp.json)
        }
      }
    }
  }

  def genCsr() = ApiAction.async(sourceBodyParser) { ctx =>
    val caOpt = ctx.request.getQueryString("ca")
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      caOpt match {
        case None => {
          env.pki.genCsr(body, None).map {
            case Left(err) => BadRequest(Json.obj("error" -> err))
            case Right(kp) => Ok(kp.json)
          }
        }
        case Some(ca) => {
          findCertificateByIdOrSerialNumber(ca).flatMap {
            case None => NotFound(Json.obj("error" -> "ca not found !")).future
            case Some(cacert) => env.pki.genCsr(body, cacert.certificate).map {
              case Left(err) => BadRequest(Json.obj("error" -> err))
              case Right(kp) => Ok(kp.json)
            }
          }
        }
      }
    }
  }

  def genCert(ca: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      findCertificateByIdOrSerialNumber(ca).flatMap {
        case None => NotFound(Json.obj("error" -> "ca not found !")).future
        case Some(cacert) => env.pki.genCert(body, cacert.certificate.get, cacert.cryptoKeyPair.getPrivate).flatMap {
          case Left(err) => BadRequest(Json.obj("error" -> err)).future
          case Right(kp) =>
            val cert = kp.toCert
            cert.save().map(_ => Ok(kp.json.as[JsObject] ++ Json.obj("certId" -> cert.id)))
        }
      }
    }
  }

  def genSubCA(ca: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
      findCertificateByIdOrSerialNumber(ca).flatMap {
        case None => NotFound(Json.obj("error" -> "ca not found !")).future
        case Some(cacert) => env.pki.genSubCA(body, cacert.certificate.get, cacert.cryptoKeyPair.getPrivate).flatMap {
          case Left(err) => BadRequest(Json.obj("error" -> err)).future
          case Right(kp) =>
            val cert = kp.toCert
            cert.save().map(_ => Ok(kp.json.as[JsObject] ++ Json.obj("certId" -> cert.id)))
        }
      }
    }
  }
}
