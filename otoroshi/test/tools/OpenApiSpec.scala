package tools

import io.github.classgraph.ClassGraph
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.openapi.{CrdsGenerator, OpenApiGenerator}
import java.io.File
import io.github.classgraph.ScanResult
import play.api.libs.json.JsValue

class OpenApiSpec extends AnyWordSpec with Matchers with OptionValues {

  val scanResult: ScanResult = new ClassGraph()
      .addClassLoader(this.getClass.getClassLoader)
      .enableAllInfo()
      .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws"): _*)
      .scan

  // Debug current working directory
  println(s"Current working directory: ${new File(".").getCanonicalPath}")

  // Helper to find the correct path with better error reporting
  private def resolvePathForFile(path: String): String = {
    val possiblePaths = Seq(
      path,
      s"../$path",
      s"otoroshi/$path",
      s"../otoroshi/$path"
    )

    // For output files, we just need the parent directory to exist
    val file = new File(path)
    val parentDir = file.getParentFile

    if (parentDir != null) {
      val validPath = possiblePaths.find { p =>
        val f = new File(p)
        val parent = f.getParentFile
        parent != null && parent.exists()
      }

      validPath match {
        case Some(p) =>
          println(s"Resolved '$path' to '$p'")
          p
        case None =>
          println(s"Warning: Could not find parent directory for '$path'. Available directories:")
          println(s"  Current dir files: ${new File(".").list().mkString(", ")}")
          println(s"  Parent dir files: ${new File("..").list().mkString(", ")}")
          path
      }
    } else {
      path
    }
  }

  private def resolvePathForInput(path: String): String = {
    val possiblePaths = Seq(
      path,
      s"../$path",
      s"otoroshi/$path",
      s"../otoroshi/$path"
    )

    val validPath = possiblePaths.find(p => new File(p).exists())

    validPath match {
      case Some(p) =>
        println(s"Found input file '$path' at '$p'")
        p
      case None =>
        println(s"Warning: Could not find input file '$path'")
        path
    }
  }

  val generator = new OpenApiGenerator(
    resolvePathForInput("./conf/routes"),
    resolvePathForInput("./app/openapi/openapi-cfg.json"),
    Seq(
      "./public/openapi.json",
      "./app/openapi/openapi.json",
      "../manual/src/main/paradox/code/openapi.json"
    ).map(resolvePathForFile),
    scanResult = scanResult,
    write = true
  )

  val spec: JsValue = generator.run()

  // Find the kubernetes crds folder
  private def findKubernetesCrdsPath(): Option[String] = {
    val possiblePaths = Seq(
      "../kubernetes/helm/otoroshi/crds",
      "../../kubernetes/helm/otoroshi/crds",
      "../../../kubernetes/helm/otoroshi/crds",
      "kubernetes/helm/otoroshi/crds"
    )

    val validPath = possiblePaths.find(p => new File(p).exists())

    validPath match {
      case Some(p) =>
        val canonicalPath = new File(p).getCanonicalPath
        println(s"Found kubernetes CRDs directory at: $canonicalPath")
        Some(canonicalPath)
      case None =>
        println("Warning: Kubernetes CRDs directory not found. Skipping CRDs generation.")
        println(s"Looked in: ${possiblePaths.mkString(", ")}")
        None
    }
  }

  // Only run CrdsGenerator if we can find the kubernetes directory
  findKubernetesCrdsPath().foreach { crdsPath =>
    new CrdsGenerator(spec).run(crdsPath)
  }
}
