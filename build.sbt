// name := "play-git-hub"

description := "Group of library code for Play, Git, and GitHub"

ThisBuild / scalaVersion := "2.13.10"

ThisBuild / organization := "com.madgag.play-git-hub"

val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % "4.6"

lazy val core = (project in file("core")).settings(
  libraryDependencies ++= Seq(
    "com.madgag" %% "rate-limit-status" % "0.7",
    "com.typesafe.play" %% "play" % "2.8.19",
    "com.squareup.okhttp3" % "okhttp" % "3.14.9",
    "com.lihaoyi" %% "fastparse" % "2.3.3",
    "com.madgag" %% "scala-collection-plus" % "0.11",
    "com.madgag.scala-git" %% "scala-git" % "4.6",
    scalaGitTest % Test,
    "org.scalatest" %% "scalatest" % "3.2.15" % Test
  )
)

lazy val testkit = (project in file("testkit")).dependsOn(core).settings(
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.15",
    scalaGitTest
  ),
  libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

lazy val `play-secret-rotation-root` = (project in file(".")).aggregate(core, testkit).settings(
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
