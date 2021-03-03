package otoroshi.utils.streams

import akka.stream._
import akka.stream.stage._

import akka.util.ByteString

object MaxLengthLimiter {
  def apply(maxLength: Int, log: (String) => Unit = str => ()): MaxLengthLimiter = new MaxLengthLimiter(maxLength, log)
}

class MaxLengthLimiter(maxLength: Int, log: (String) => Unit = str => ())
    extends GraphStage[FlowShape[ByteString, ByteString]] {

  val in  = Inlet[ByteString]("MaxLengthLimiter.in")
  val out = Outlet[ByteString]("MaxLengthLimiter.out")

  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    var current = ByteString.empty

    setHandler(
      in,
      new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          // println(s"Getting chunk ${chunk.utf8String}")
          if ((current.length + chunk.length) > maxLength) {
            // will be too big
            log(s"Cutting response to ${maxLength} bytes as response will be too big")
            current = current.concat(chunk.take(maxLength - current.length))
            emit(out, current)
            completeStage()
          } else if (current.length <= maxLength) {
            // acc
            current = current.concat(chunk)
            pull(in)
          } else {
            // too big
            log(s"Cutting response to ${maxLength} bytes as response is too big")
            emit(out, current)
            completeStage()
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (current.length <= maxLength) {
            emit(out, current)
            completeStage()
          } else {
            completeStage()
          }
        }
      }
    )

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (current.length <= maxLength) {
          pull(in)
        } else {
          emit(out, current)
          completeStage()
        }
      }
    })
  }
}
