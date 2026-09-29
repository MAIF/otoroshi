package functional

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import otoroshi.utils.gzip.GzipFlow

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Random

// GzipFlow compresses the responses of the gzip response compressor at the level of its configuration (5 by
// default). a level out of the range Deflater accepts must not make every compressed response fail
class GzipFlowSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with BeforeAndAfterAll {

  private val system                     = ActorSystem("gzip-flow-spec")
  private implicit val mat: Materializer = Materializer(system)

  override def afterAll(): Unit = Await.result(system.terminate(), 10.seconds)

  // a json body like an api returns: the same keys again and again, varied values
  private val payload: ByteString = {
    val random = new Random(42)
    ByteString(
      (0 until 3000)
        .map(i =>
          s"""{"id":$i,"name":"user-${random.nextInt(100000)}","score":${random.nextDouble()},"tags":["${random.alphanumeric
              .take(8)
              .mkString}","t${random.nextInt(50)}"]}"""
        )
        .mkString("[", ",", "]")
    )
  }

  private def gzip(level: Int): ByteString =
    Await.result(
      Source(payload.grouped(4096).toList)
        .via(GzipFlow.gzip(8192, level))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _)),
      10.seconds
    )

  private def gunzip(bytes: ByteString): ByteString =
    ByteString(new GZIPInputStream(new ByteArrayInputStream(bytes.toArray)).readAllBytes())

  "GzipFlow.gzip" should {

    // level 0 stores the data as it is, so its output is larger than the data: it proves the level is applied
    "compress at the level it is given" in {
      val sizes = Seq(0, 1, 5, 9).map(level => level -> gzip(level).size).toMap
      sizes(0) must be > payload.size
      sizes(1) must be > sizes(9)
      sizes(5) must be >= sizes(9)
      sizes(9) must be < payload.size / 2
    }

    "give the data back at every level" in {
      Seq(-1, 0, 1, 5, 9).foreach(level => gunzip(gzip(level)) mustBe payload)
    }

    "compress with a level out of the range Deflater accepts" in {
      Seq(-5, 10, 42).foreach(level => gunzip(gzip(level)) mustBe payload)
    }
  }
}
