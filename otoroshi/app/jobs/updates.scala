package otoroshi.jobs.updates

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import env.Env
import otoroshi.plugins.jobs.kubernetes.KubernetesConfig
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import play.api.Logger
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Try}

object SoftwareUpdatesJobs {
  val latestVersionHolder = new AtomicReference[JsValue](JsNull)
}

class SoftwareUpdatesJobs extends Job {

  private val logger = Logger("otoroshi-jobs-software-updates")

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.SoftwareUpdatesJobs")

  override def name: String = "Kubernetes to Otoroshi certs. synchronizer"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] = s"""This job will check if a new version of otoroshi is available""".stripMargin.some

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext): Option[FiniteDuration] = 24.hours.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val otoroshiVersion = env.otoroshiVersion
    if (env.checkForUpdates) {
        logger.info("checking otoroshi updates ...")
        env.datastores.globalConfigDataStore
          .singleton()
          .flatMap { globalConfig =>
            val version = Version(otoroshiVersion)
            env.Ws
              .url("https://updates.otoroshi.io/api/versions/latest")
              .withRequestTimeout(10.seconds)
              .withHttpHeaders(
                "Otoroshi-Version" -> otoroshiVersion,
                "Otoroshi-Id"      -> globalConfig.otoroshiId
              )
              .get()
              .map { response =>
                val body = response.json.as[JsObject]
                val latestVersionRaw   = (body \ "version_raw").as[String]
                val latestVersion      = Version(latestVersionRaw)
                val latestVersionClean = (body \ "version_number").as[Double]
                val isAfter = latestVersion.isAfter(version)
                logger.debug(s"current version is ${version.raw}, latest version is ${latestVersion.raw}. Should update: ${isAfter}")
                SoftwareUpdatesJobs.latestVersionHolder.set(
                  body ++ Json.obj(
                    "current_version_raw"    -> otoroshiVersion,
                    // "current_version_number" -> version.value,
                    "outdated"               -> isAfter // (latestVersionClean > cleanVersion)
                  )
                )
                if (isAfter) {
                  logger.info(
                    s"A new version of Otoroshi (${latestVersion.raw}, your version is $otoroshiVersion) is available. You can download it on https://maif.github.io/otoroshi/ or at https://github.com/MAIF/otoroshi/releases/tag/${latestVersion.raw}"
                  )
                }
              }
          }
          .andThen {
            case Failure(e) => e.printStackTrace()
          }
    } else {
      ().future
    }
  }
}

sealed trait VersionSuffix {
  def isBefore(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = !isAfter(vSuffix, sSuffixVersion, vSuffixVersion)
  def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean
  def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean
  def stringify(): String
}

object VersionSuffix {
  case object Dev extends VersionSuffix {
    def stringify(): String = "dev"
    def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Dev => sSuffixVersion > vSuffixVersion
      case Snapshot => sSuffixVersion > vSuffixVersion
      case Alpha => false
      case Beta => false
      case ReleaseCandidate => false
    }
    def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Dev => true
      case Snapshot => true
      case _ => false
    }
  }
  case object Snapshot extends VersionSuffix {
    def stringify(): String = "snapshot"
    def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = Dev.isAfter(vSuffix, sSuffixVersion, vSuffixVersion)
    def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = Dev.isEquals(vSuffix, sSuffixVersion, vSuffixVersion)
  }
  case object Alpha extends VersionSuffix {
    def stringify(): String = "alpha"
    def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Dev => true
      case Snapshot => true
      case Alpha => sSuffixVersion > vSuffixVersion
      case Beta => false
      case ReleaseCandidate => false
    }
    def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Alpha => sSuffixVersion == vSuffixVersion
      case _ => false
    }
  }
  case object Beta extends VersionSuffix {
    def stringify(): String = "beta"
    def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Dev => true
      case Snapshot => true
      case Alpha => true
      case Beta => sSuffixVersion > vSuffixVersion
      case ReleaseCandidate => false
    }
    def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Beta => sSuffixVersion == vSuffixVersion
      case _ => false
    }
  }
  case object ReleaseCandidate extends VersionSuffix {
    def stringify(): String = "rc"
    def isAfter(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case Dev => true
      case Snapshot => true
      case Alpha => true
      case Beta => true
      case ReleaseCandidate => sSuffixVersion > vSuffixVersion
    }
    def isEquals(vSuffix: VersionSuffix, sSuffixVersion: Int, vSuffixVersion: Int): Boolean = vSuffix match {
      case ReleaseCandidate => sSuffixVersion == vSuffixVersion
      case _ => false
    }
  }
}

case class Version(major: Int, minor: Int, patch: Int, build: Option[Int], suffix: Option[VersionSuffix], suffixVersion: Option[Int], raw: String) extends Comparable[Version] {
  lazy val value = {
    raw.toLowerCase() match {
      case v if v.contains("-snapshot") =>
        v.replace(".", "")
          .replace("-snapshot", "")
          .replace("v", "")
          .toDouble - 0.5
      case v if v.contains("-dev") =>
        v.replace(".", "")
          .replace("-dev", "")
          .replace("v", "")
          .toDouble - 0.5
      case v if v.contains("-alpha") =>
        v.replace(".", "")
          .replace("-alpha0", "")
          .replace("-alpha", "")
          .replace("v", "")
          .toDouble - 0.4
      case v if v.contains("-beta") =>
        v.replace(".", "")
          .replace("-beta0", "")
          .replace("-beta", "")
          .replace("v", "")
          .toDouble - 0.3
      case v if v.contains("-rc") =>
        v.replace(".", "")
          .replace("-rc0", "")
          .replace("-rc", "")
          .replace("v", "")
          .toDouble - 0.2
      case v => v
        .replace(".", "")
        .replace("-dev", "")
        .replace("-snapshot", "")
        .replace("-rc0", "")
        .replace("-rc", "")
        .replace("-alpha0", "")
        .replace("-alpha", "")
        .replace("-beta0", "")
        .replace("-beta", "")
        .replace("v", "")
        .toDouble
    }
  }
  def compareTo(version: Version): Int = {
    if (isEquals(version)) {
      0
    } else {
      if (isAfter(version)) {
        1
      } else {
        -1
      }
    }
  }
  def isEquals(version: Version): Boolean = {
    if (major == version.major) {
      if (minor == version.minor) {
        if (patch == version.patch) {
          suffixEquals(version)
        } else {
          false
        }
      } else {
        false
      }
    } else {
      false
    }
  }
  def isBefore(version: Version): Boolean = !isAfter(version)
  def isAfter(version: Version): Boolean = {
    if (major == version.major) {
      if (minor == version.minor) {
        if (patch == version.patch) {
          suffixAfter(version)
        } else {
          patch > version.patch
        }
      } else {
        minor > version.minor
      }
    } else {
      major > version.major
    }
  }
  private def suffixAfter(version: Version): Boolean = {
    (suffix, version.suffix) match {
      case (None, None)                   => false
      case (None, Some(_))                => true
      case (Some(_), None)                => false
      case (Some(sSuffix), Some(vSuffix)) => sSuffix.isAfter(vSuffix, suffixVersion.getOrElse(0), version.suffixVersion.getOrElse(0))
    }
  }
  private def suffixEquals(version: Version): Boolean = {
    (suffix, version.suffix) match {
      case (None, None)                   => true
      case (None, Some(_))                => false
      case (Some(_), None)                => false
      case (Some(sSuffix), Some(vSuffix)) => sSuffix.isEquals(vSuffix, suffixVersion.getOrElse(0), version.suffixVersion.getOrElse(0))
    }
  }
  def stringify(): String = {
    val buildStr = build.map(v => s".$v")
    val suffixStr = suffix.map(v => s"-${v.stringify()}")
    val suffixVersionStr = suffixVersion.map(v => s".$v")
    s"$major.$minor.$patch$buildStr$suffixStr$suffixVersionStr"
  }
}

object Version {
  private val splits = Seq("alpha0", "alpha", "beta0", "beta", "rc0", "rc", "dev", "snapshot", "a", "b")
  def apply(rawVersion: String): Version = {
    val lower = rawVersion.toLowerCase().applyOnWithPredicate(_.startsWith("v"))(_.substring(1))
    val (versionText, suffix, suffixValue) = if (lower.contains("-")) {
      lower.split("-").toList match {
        case head :: suffix :: Nil if suffix.startsWith("alpha0") => (head, VersionSuffix.Alpha.some, Try(suffix.replace("alpha0", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("alpha") => (head, VersionSuffix.Alpha.some, Try(suffix.replace("alpha", "").replace(".", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("a") => (head, VersionSuffix.Alpha.some, Try(suffix.replace("a", "").replace(".", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("beta0") => (head, VersionSuffix.Beta.some, Try(suffix.replace("beta0", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("beta") => (head, VersionSuffix.Beta.some, Try(suffix.replace("beta", "").replace(".", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("b") => (head, VersionSuffix.Beta.some, Try(suffix.replace("b", "").replace(".", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("rc0") => (head, VersionSuffix.ReleaseCandidate.some, Try(suffix.replace("rc0", "").toInt).toOption)
        case head :: suffix :: Nil if suffix.startsWith("rc") => (head, VersionSuffix.ReleaseCandidate.some, Try(suffix.replace("rc", "").replace(".", "").toInt).toOption)
        /////// this section because ... ///////
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("alpha0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("alpha0", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("alpha") => (head, VersionSuffix.Dev.some, Try(suffix.replace("alpha", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("a") => (head, VersionSuffix.Dev.some, Try(suffix.replace("a", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("beta0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("beta0", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("beta") => (head, VersionSuffix.Dev.some, Try(suffix.replace("beta", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("b") => (head, VersionSuffix.Dev.some, Try(suffix.replace("b", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("rc0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("rc0", "").toInt).toOption)
        case head :: suffix :: "dev" :: Nil if suffix.startsWith("rc") => (head, VersionSuffix.Dev.some, Try(suffix.replace("rc", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("alpha0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("alpha0", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("alpha") => (head, VersionSuffix.Dev.some, Try(suffix.replace("alpha", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("a") => (head, VersionSuffix.Dev.some, Try(suffix.replace("a", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("beta0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("beta0", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("beta") => (head, VersionSuffix.Dev.some, Try(suffix.replace("beta", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("b") => (head, VersionSuffix.Dev.some, Try(suffix.replace("b", "").replace(".", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("rc0") => (head, VersionSuffix.Dev.some, Try(suffix.replace("rc0", "").toInt).toOption)
        case head :: suffix :: "snapshot" :: Nil if suffix.startsWith("rc") => (head, VersionSuffix.Dev.some, Try(suffix.replace("rc", "").replace(".", "").toInt).toOption)
        /////// this section because ... ///////
        case head :: "dev" :: Nil => (head, VersionSuffix.Dev.some, None)
        case head :: "snapshot" :: Nil => (head, VersionSuffix.Snapshot.some, None)
        case head :: "alpha" :: suffixValue :: Nil => (head, VersionSuffix.Alpha.some, Try(suffixValue.replace(".", "").toInt).toOption)
        case head :: "a" :: suffixValue :: Nil => (head, VersionSuffix.Alpha.some, Try(suffixValue.replace(".", "").toInt).toOption)
        case head :: "beta" :: suffixValue :: Nil => (head, VersionSuffix.Beta.some, Try(suffixValue.replace(".", "").toInt).toOption)
        case head :: "b" :: suffixValue :: Nil => (head, VersionSuffix.Beta.some, Try(suffixValue.replace(".", "").toInt).toOption)
        case head :: "rc" :: suffixValue :: Nil => (head, VersionSuffix.ReleaseCandidate.some, Try(suffixValue.replace(".", "").toInt).toOption)
        case head :: _ => (head, None, None)
      }
    } else {
      splits.find(lower.contains(_)) match {
        case None => (lower, None, None)
        case Some(split) => lower.split(split).toList match {
          case head :: Nil if split == "dev"      => (head, VersionSuffix.Dev.some, None)
          case head :: Nil if split == "snapshot" => (head, VersionSuffix.Snapshot.some, None)
          case head :: suffixValue :: Nil if split == "alpha0" => (head, VersionSuffix.Alpha.some, Try(suffixValue.toInt).toOption)
          case head :: suffixValue :: Nil if split == "alpha"  => (head, VersionSuffix.Alpha.some, Try(suffixValue.replace(".", "").toInt).toOption)
          case head :: suffixValue :: Nil if split == "a"  => (head, VersionSuffix.Alpha.some, Try(suffixValue.replace(".", "").toInt).toOption)
          case head :: suffixValue :: Nil if split == "beta0" => (head, VersionSuffix.Beta.some, Try(suffixValue.toInt).toOption)
          case head :: suffixValue :: Nil if split == "beta"  => (head, VersionSuffix.Beta.some, Try(suffixValue.replace(".", "").toInt).toOption)
          case head :: suffixValue :: Nil if split == "a"  => (head, VersionSuffix.Beta.some, Try(suffixValue.replace(".", "").toInt).toOption)
          case head :: suffixValue :: Nil if split == "rc0" => (head, VersionSuffix.ReleaseCandidate.some, Try(suffixValue.toInt).toOption)
          case head :: suffixValue :: Nil if split == "rc"  => (head, VersionSuffix.ReleaseCandidate.some, Try(suffixValue.replace(".", "").toInt).toOption)
          case head :: _ => (head, None, None)
        }
      }
    }
    val (major, minor, patch, build) = versionText.split("\\.").toList match {
      case _major :: Nil                               => (Try(_major.toInt).getOrElse(0), 0, 0, None)
      case _major :: _minor :: Nil                     => (Try(_major.toInt).getOrElse(0), Try(_minor.toInt).getOrElse(0), 0, None)
      case _major :: _minor :: _patch :: Nil           => (Try(_major.toInt).getOrElse(0), Try(_minor.toInt).getOrElse(0), Try(_patch.toInt).getOrElse(0), None)
      case _major :: _minor :: _patch :: _build :: Nil => (Try(_major.toInt).getOrElse(0), Try(_minor.toInt).getOrElse(0), Try(_patch.toInt).getOrElse(0), Try(_build.toInt).toOption)
      case _                                           => (0, 0, 0, None)
    }
    new Version(major, minor, patch, build, suffix, suffixValue, rawVersion)
  }
}
