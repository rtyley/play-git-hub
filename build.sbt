organization := "com.madgag"

name := "play-git-hub"

description := "Group of library code for Play, Git, and GitHub"

scalaVersion := "2.13.3"

libraryDependencies ++= Seq(
  "com.madgag" %% "rate-limit-status" % "0.5",
  "com.typesafe.play" %% "play" % "2.7.5",
  "com.squareup.okhttp3" % "okhttp" % "3.6.0",
  "com.lihaoyi" %% "fastparse" % "2.1.3",
  "com.madgag.scala-git" %% "scala-git" % "4.3",
  "com.madgag.scala-git" %% "scala-git-test" % "4.3" % "test",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test"
)

resolvers += Resolver.sonatypeRepo("releases")

updateOptions := updateOptions.value.withCachedResolution(true)

scmInfo := Some(ScmInfo(
  url("https://github.com/rtyley/play-git-hub"),
  "scm:git:git@github.com:rtyley/play-git-hub.git"
))

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

publishTo in ThisBuild := sonatypePublishToBundle.value

import ReleaseTransformations._

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
