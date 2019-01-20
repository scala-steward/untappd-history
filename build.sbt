organization := "lt.dvim.untappd"
name := "untappd-history"

scalaVersion := "2.12.8"

val Akka = "2.5.19"
val Circe = "0.11.1"

libraryDependencies ++= Seq(
  "is.cir" %% "ciris-core" % "0.12.1",
  "lt.dvim.ciris-hocon" %% "ciris-hocon" % "0.1",
  "io.circe" %% "circe-optics" % Circe,
  "io.circe" %% "circe-parser" % Circe,
  "io.circe" %% "circe-java8" % Circe,
  "com.typesafe.akka" %% "akka-http" % "10.1.7",
  "de.heikoseeberger" %% "akka-http-circe" % "1.24.3",
  "com.typesafe.akka" %% "akka-stream-typed" % Akka,
  "com.typesafe.akka" %% "akka-persistence-typed" % Akka,
  "com.typesafe.akka" %% "akka-persistence-query" % Akka,
  "com.typesafe.akka" %% "akka-slf4j" % Akka,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.4.0",
  "com.h2database" % "h2" % "1.4.197"
)

resolvers += Resolver.bintrayRepo("2m", "snapshots")

scalafmtOnCompile := true

version in ThisBuild ~= (_.replace('+', '-'))
dockerUsername := Some("martynas")
dockerExposedVolumes += "/data"
enablePlugins(JavaAppPackaging)
