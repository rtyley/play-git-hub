import ReleaseTransformations.*
import sbtversionpolicy.withsbtrelease.ReleaseVersion

description := "Group of library code for Play, Git, and GitHub"

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / crossScalaVersions := Seq(
  scalaVersion.value,
  "3.3.6"
)

val scalaGitVersion = "7.0.0-PREVIEW.support-scala-3.2025-08-18T0747.e34aa011"
val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % scalaGitVersion
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"

lazy val artifactProducingSettings = Seq(
  organization := "com.madgag.play-git-hub",
  licenses := Seq(License.Apache2),
  scalacOptions := Seq("-deprecation", "-release:11"),
  Test / testOptions +=
    Tests.Argument(TestFrameworks.ScalaTest, "-u", s"test-results/scala-${scalaVersion.value}", "-o")
)

lazy val core = (project in file("core")).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    "com.madgag" %% "rate-limit-status" % "1.0.1",
    "com.github.blemale" %% "scaffeine" % "5.3.0",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.70",
    "org.playframework" %% "play" % "3.0.8",
    "com.squareup.okhttp3" % "okhttp" % "4.12.0",
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
    scalaGitTest
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