import ReleaseTransformations.*
import sbtversionpolicy.withsbtrelease.ReleaseVersion

description := "Group of library code for Play, Git, and GitHub"

ThisBuild / scalaVersion := "3.3.6"

val scalaGitVersion = "7.0.4"
val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % scalaGitVersion
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
val catsEffectTesting = "org.typelevel" %% "cats-effect-testing-scalatest" % "1.6.0"

lazy val artifactProducingSettings = Seq(
  organization := "com.madgag.play-git-hub",
  licenses := Seq(License.Apache2),
  scalacOptions := Seq("-deprecation", "-release:11"),
  Test / testOptions +=
    Tests.Argument(TestFrameworks.ScalaTest, "-u", s"test-results/scala-${scalaVersion.value}", "-o")
)

lazy val core = (project in file("core")).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    "com.madgag" %% "scala-collection-plus" % "1.0.0",
    "co.fs2" %% "fs2-core" % "3.12.2",
    "com.softwaremill.sttp.client4" %% "cats" % "4.0.11",
    "com.gu.etag-caching" %% "core" % "11.0.0-PREVIEW.support-fetching-then-parsing-with-key.2025-09-09T0923.3b4c7060",
    "com.github.cb372" %% "cats-retry" % "4.0.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    "com.madgag" %% "rate-limit-status" % "1.0.1",
    "com.github.blemale" %% "scaffeine" % "5.3.0",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.70",
    "org.playframework" %% "play" % "3.0.9",
    "com.lihaoyi" %% "fastparse" % "3.1.1",
    "com.madgag" %% "scala-collection-plus" % "1.0.0",
    "com.madgag.scala-git" %% "scala-git" % scalaGitVersion,
    "joda-time" % "joda-time" % "2.14.0",
    scalaTest % Test,
    scalaGitTest % Test,
    catsEffectTesting % Test
  )
)

lazy val testkit = (project in file("testkit")).dependsOn(core).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    scalaTest,
    scalaGitTest,
    catsEffectTesting % Test
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