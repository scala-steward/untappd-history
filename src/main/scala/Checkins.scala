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

import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.QueryDocumentSnapshot
import io.circe.parser._

import lt.dvim.untappd.history.FirestoreOps._
import lt.dvim.untappd.history.model.Untappd._
import lt.dvim.untappd.history.model.VilniusPub._

object Checkins {
  def storedCheckinsStream()(implicit sys: ActorSystem, db: Firestore) = {
    import sys.dispatcher
    val PageSize = 500
    val checkins = db.collection("checkins")
    Source
      .unfoldAsync(Option.empty[QueryDocumentSnapshot]) { startAfter =>
        val query = checkins.limit(PageSize)
        val data = startAfter.fold(query)(query.startAfter).getAsync()
        data.map {
          case elements if elements.isEmpty => None
          case elements                     => Some((Some(elements.last), elements))
        }
      }
      .mapConcat(identity)
  }

  def gatherDailyCheckins()(implicit sys: ActorSystem, db: Firestore) =
    storedCheckinsStream()
      .map(_.getData().get("data").asInstanceOf[String])
      .map(body => parse(body).toTry.get.as[Checkin].toOption.get)
      .map(_.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE))
      .fold(Map.empty[String, Int])((acc, date) => acc.updatedWith(date)(count => Some(count.getOrElse(0) + 1)))
      .map(DailyCheckins.apply)
}
