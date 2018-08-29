organization := "lt.dvim"
name := "untappd-local-pub-history"

libraryDependencies ++= Seq(
  "is.cir" %% "ciris-core" % "0.10.2",
  "io.circe" %% "circe-optics" % "0.10.0-M1",
  "io.circe" %% "circe-parser" % "0.10.0-M1",
  "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "0.20"
)
