organization := "lt.dvim"
name := "untappd-local-pub-history"

libraryDependencies ++= Seq(
  "is.cir" %% "ciris-core" % "0.11",
  "io.circe" %% "circe-optics" % "0.10.0",
  "io.circe" %% "circe-parser" % "0.10.0",
  "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "1.0-M1",
  "com.typesafe.akka" %% "akka-stream-contrib" % "0.9+20-c110a2df"
)
