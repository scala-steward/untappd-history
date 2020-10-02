organization := "lt.dvim.untappd"
name := "untappd-history"
description := "History and statistics of Untappd checkins"

scalaVersion := "2.13.1"

val Akka = "2.6.9"
val Circe = "0.13.0"

libraryDependencies ++= Seq(
  "is.cir"              %% "ciris-core"             % "0.13.0-RC1",
  "lt.dvim.ciris-hocon" %% "ciris-hocon"            % "0.2",
  "io.circe"            %% "circe-optics"           % "0.13.0",
  "io.circe"            %% "circe-parser"           % Circe,
  "io.circe"            %% "circe-generic"          % Circe,
  "com.typesafe.akka"   %% "akka-stream"            % Akka,
  "com.typesafe.akka"   %% "akka-slf4j"             % Akka,
  "com.typesafe.akka"   %% "akka-http"              % "10.2.1",
  "de.heikoseeberger"   %% "akka-http-circe"        % "1.35.0",
  "ch.megard"           %% "akka-http-cors"         % "1.1.0",
  "ch.qos.logback"       % "logback-classic"        % "1.2.3",
  "com.google.cloud"     % "google-cloud-firestore" % "2.1.0"
)

enablePlugins(JavaAppPackaging)

dockerUpdateLatest := true
Docker / packageName := "gcr.io/untappd-263504/untappd-history"
ThisBuild / dynverSeparator := "-"

scalafmtOnCompile := true

ThisBuild / scalafixDependencies ++= Seq(
  "com.nequissimus" %% "sort-imports" % "0.5.4"
)

enablePlugins(AutomateHeaderPlugin)
startYear := Some(2018)
organizationName := "Untappd History"
licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
