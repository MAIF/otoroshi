package tests

import utils._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor._
import akka.util.ByteString

object StreamLimiterTest extends App {

  implicit val system = ActorSystem("streams")
  implicit val mat    = ActorMaterializer.create(system)

  import system.dispatcher

  val source = Source(
    scala.collection.immutable.Seq(ByteString("abcd"),
                                   ByteString("efgh"),
                                   ByteString("ijkl"),
                                   ByteString("mnop"),
                                   ByteString("qrst"),
                                   ByteString("uvwx"),
                                   ByteString("yz"))
  )

  def test(size: Int) =
    source.via(MaxLengthLimiter(size)).runWith(Sink.reduce[ByteString]((bs, n) => bs.concat(n))).map { body =>
      println(s"limit $size: body (${body.length}) : ${body.utf8String}")
    }

  for {
    _ <- test(25)
    _ <- test(30)
    _ <- test(10)
    _ <- test(26)
    _ <- test(20)
    _ <- test(2)
  } yield {
    System.exit(0)
  }
}
