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

import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters._

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Query

import lt.dvim.untappd.history.ApiFutureOps._

object FirestoreOps {
  implicit def documentReferenceOps(ref: DocumentReference) = new DocumentReferenceOps(ref)
  implicit def queryOps(query: Query) = new QueryOps(query)
}

class DocumentReferenceOps(ref: DocumentReference) {
  def setAsync(attributes: Map[String, String])(implicit ex: Executor) =
    ref.set(attributes.asJava).toScala
  def getAsync()(implicit ex: ExecutionContextExecutor) =
    ref.get().toScala.map(_.getData().asScala.toMap)
}

class QueryOps(query: Query) {
  def getAsync()(implicit ex: ExecutionContextExecutor) =
    query.get().toScala.map(_.getDocuments.asScala.toIndexedSeq)
}
