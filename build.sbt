sbtPlugin := true

organization := "com.typesafe.sbt"

name := "sbt-coffeescript-plugin"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.3"

scalacOptions += "-feature"

resolvers ++= Seq(
  "Typesafe Releases Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.url("sbt snapshot plugins", url("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns),
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/"
)

libraryDependencies ++= Seq(
  "org.webjars" % "coffee-script" % "1.6.3",
  "org.specs2" %% "specs2" % "2.3.7" % "test",
  "junit" % "junit" % "4.11" % "test"
)

addSbtPlugin("com.typesafe.sbt" % "sbt-js-engine" % "1.0.0-SNAPSHOT")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }

fork in run := true
