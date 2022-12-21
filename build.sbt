organization := "com.madgag"

name := "play-git-hub"

description := "Group of library code for Play, Git, and GitHub"

scalaVersion := "2.13.10"

libraryDependencies ++= Seq(
  "com.madgag" %% "rate-limit-status" % "0.7",
  "com.typesafe.play" %% "play" % "2.8.18",
  "com.squareup.okhttp3" % "okhttp" % "3.12.13",
  "com.lihaoyi" %% "fastparse" % "2.3.3",
  "com.madgag" %% "scala-collection-plus" % "0.11",
  "com.madgag.scala-git" %% "scala-git" % "4.5",
  "com.madgag.scala-git" %% "scala-git-test" % "4.5" % Test,
  "org.scalatest" %% "scalatest" % "3.2.14" % Test
)

resolvers ++= Resolver.sonatypeOssRepos("releases")

Test / testOptions +=
  Tests.Argument(TestFrameworks.ScalaTest, "-u", s"test-results/scala-${scalaVersion.value}")

updateOptions := updateOptions.value.withCachedResolution(true)

scmInfo := Some(ScmInfo(
  url("https://github.com/rtyley/play-git-hub"),
  "scm:git:git@github.com:rtyley/play-git-hub.git"
))

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

publishTo := sonatypePublishToBundle.value

import ReleaseTransformations._

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommand("publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
