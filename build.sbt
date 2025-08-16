import ReleaseTransformations.*
import sbtversionpolicy.withsbtrelease.ReleaseVersion

description := "Group of library code for Play, Git, and GitHub"

ThisBuild / scalaVersion := "3.3.6"

val scalaGitVersion = "7.0.4"
val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % scalaGitVersion
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
val weaverCats = "org.typelevel" %% "weaver-cats" % "0.10.1"

lazy val artifactProducingSettings = Seq(
  organization := "com.madgag.play-git-hub",
  licenses := Seq(License.Apache2),
  scalacOptions := Seq("-deprecation", "-release:21"),
  Test / testOptions +=
    Tests.Argument(TestFrameworks.ScalaTest, "-u", s"test-results/scala-${scalaVersion.value}", "-o")
)

lazy val core = (project in file("core")).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-simple" % "2.0.17",
    "com.madgag" %% "scala-collection-plus" % "1.0.0",
    "co.fs2" %% "fs2-core" % "3.12.2",
    "com.softwaremill.sttp.client4" %% "cats" % "4.0.13",
    "com.gu.etag-caching" %% "core" % "12.0.0",
    "com.github.cb372" %% "cats-retry" % "4.0.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    weaverCats % Test,
    "com.madgag" %% "rate-limit-status" % "1.0.1",
    "com.github.blemale" %% "scaffeine" % "5.3.0",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.70",
    "org.playframework" %% "play" % "3.0.9",
    "com.lihaoyi" %% "fastparse" % "3.1.1",
    "com.madgag" %% "scala-collection-plus" % "1.0.0",
    "com.madgag.scala-git" %% "scala-git" % scalaGitVersion,
    "joda-time" % "joda-time" % "2.14.0",
    scalaTest % Test,
    scalaGitTest % Test
  )
)

lazy val testkit = (project in file("testkit")).dependsOn(core).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    scalaTest,
    scalaGitTest,
    weaverCats % Test
  )
)

lazy val `play-git-hub-root` = (project in file(".")).aggregate(core, testkit).settings(
  publishArtifact := false
)

publish / skip := true
releaseVersion := ReleaseVersion.fromAggregatedAssessedCompatibilityWithLatestRelease().value
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  setNextVersion,
  commitNextVersion
)