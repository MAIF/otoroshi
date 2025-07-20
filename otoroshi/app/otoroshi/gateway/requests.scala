package otoroshi.gateway

import java.util.concurrent.atomic.AtomicLong

import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future

trait RequestsDataStore {

  //def incrementProcessedRequests(): Long
  //def decrementProcessedRequests(): Long
  //def getProcessedRequests(): Long
  //def asyncGetProcessedRequests(): Future[Long]

  def incrementHandledRequests(): Long
  def decrementHandledRequests(): Long
  def getHandledRequests(): Long
  def asyncGetHandledRequests(): Future[Long]
}

class InMemoryRequestsDataStore() extends RequestsDataStore {

  //private lazy val counterProcessed = new AtomicLong(0L)

  private lazy val handledProcessed = new AtomicLong(0L)

  //override def incrementProcessedRequests(): Long = counterProcessed.incrementAndGet()

  //override def decrementProcessedRequests(): Long = counterProcessed.decrementAndGet()

  //override def getProcessedRequests(): Long = counterProcessed.get()

  //override def asyncGetProcessedRequests(): Future[Long] = FastFuture.successful(counterProcessed.get())

  override def incrementHandledRequests(): Long = handledProcessed.incrementAndGet()

  override def decrementHandledRequests(): Long = handledProcessed.decrementAndGet()

  override def getHandledRequests(): Long = handledProcessed.get()

  override def asyncGetHandledRequests(): Future[Long] = FastFuture.successful(handledProcessed.get())
}
