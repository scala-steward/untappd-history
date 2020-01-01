package lt.dvim.untappd.history

import java.util.concurrent.Executor

import scala.concurrent.Future
import scala.concurrent.Promise

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures

object ApiFutureOps {
  implicit def apiFutureOps[T](future: ApiFuture[T]) = new ApiFutureOps(future)
}

class ApiFutureOps[T](future: ApiFuture[T]) {
  def toScala(implicit ex: Executor): Future[T] = {
    val promise = Promise[T]()
    ApiFutures.addCallback(
      future,
      new ApiFutureCallback[T] {
        def onFailure(t: Throwable) = promise.failure(t)
        def onSuccess(result: T) = promise.success(result)
      },
      ex
    )
    promise.future
  }
}
