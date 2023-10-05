package tools

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.core.server.ServerConfig

import java.nio.file.Files

object OtoWsWorker {

  def main(args: Array[String]): Unit = {
    for (i <- 1 to 4) yield {
      val otoroshi = Otoroshi(
        ServerConfig(
          address = "0.0.0.0",
          port = Some(9079 + i),
          sslPort = Some(9442 + i),
          rootDir = Files.createTempDirectory(s"otoroshi-worker-test-${i}").toFile,
          properties = System.getProperties
            .seffectOn(_.setProperty("otoroshi.env", "dev"))
            .seffectOn(_.setProperty("otoroshi.cluster.mode", "worker"))
            .seffectOn(_.setProperty("otoroshi.cluster.leader.url", "http://otoroshi-api.oto.tools:9999"))
            .seffectOn(_.setProperty("otoroshi.cluster.worker.useWs", "true"))
        ),
        ConfigFactory
          .parseString(s"""
               |otoroshi.next.state-sync-interval=5
               |""".stripMargin)
          .resolve()
      )
      val server   = otoroshi.start()
    }
  }

}
