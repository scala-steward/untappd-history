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
