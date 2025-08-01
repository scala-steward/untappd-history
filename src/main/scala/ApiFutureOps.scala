/*
 * Copyright 2018 Untappd History
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package lt.dvim.untappd.history

import java.util.concurrent.Executor

import scala.concurrent.Future
import scala.concurrent.Promise

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures

object ApiFutureOps {
  implicit def apiFutureOps[T](future: ApiFuture[T]): ApiFutureOps[T] = new ApiFutureOps(future)
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
