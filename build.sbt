// name := "play-git-hub"

description := "Group of library code for Play, Git, and GitHub"

ThisBuild / scalaVersion := "2.13.12"

ThisBuild / organization := "com.madgag.play-git-hub"

val scalaGitVersion = "4.8"
val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % scalaGitVersion

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15"

lazy val core = (project in file("core")).settings(
  resolvers ++= Resolver.sonatypeOssRepos("releases"),
  libraryDependencies ++= Seq(
    "com.madgag" %% "rate-limit-status" % "0.7",
    "org.playframework" %% "play" % "3.0.0",
    "com.squareup.okhttp3" % "okhttp" % "4.12.0",
    "com.lihaoyi" %% "fastparse" % "3.0.0",
    "com.madgag" %% "scala-collection-plus" % "0.11",
    "com.madgag.scala-git" %% "scala-git" % scalaGitVersion,
    scalaGitTest % Test,
    scalaTest % Test
  )
)

lazy val testkit = (project in file("testkit")).dependsOn(core).settings(
  libraryDependencies ++= Seq(
    scalaTest,
    scalaGitTest
  ),
  libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

lazy val `play-git-hub-root` = (project in file(".")).aggregate(core, testkit).settings(
  publishArtifact := false
)

resolvers ++= Resolver.sonatypeOssRepos("releases")

Test / testOptions +=
  Tests.Argument(TestFrameworks.ScalaTest, "-u", s"test-results/scala-${scalaVersion.value}")

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
