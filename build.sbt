organization := "com.madgag"

name := "play-git-hub"

description := "Group of library code for Play, Git, and GitHub"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.4.0",
  "org.kohsuke" % "github-api" % "1.68" exclude("org.jenkins-ci", "annotation-indexer"),
  "com.squareup.okhttp" % "okhttp" % "2.4.0",
  "com.squareup.okhttp" % "okhttp-urlconnection" % "2.4.0",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "3.7.1.201504261725-r",
  "com.madgag.scala-git" %% "scala-git" % "2.9",
  "com.madgag.scala-git" %% "scala-git-test" % "2.9" % "test"
)

updateOptions := updateOptions.value.withCachedResolution(true)

scmInfo := Some(ScmInfo(
  url("https://github.com/rtyley/play-git-hub"),
  "scm:git:git@github.com:rtyley/play-git-hub.git"
))

pomExtra := (
  <url>https://github.com/rtyley/play-git-hub</url>
  <developers>
    <developer>
      <id>rtyley</id>
      <name>Roberto Tyley</name>
      <url>https://github.com/rtyley</url>
    </developer>
  </developers>
)

licenses := Seq("GPLv3" -> url("http://www.gnu.org/licenses/gpl-3.0.html"))

import ReleaseTransformations._

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(action = Command.process("publishSigned", _)),
  setNextVersion,
  commitNextVersion,
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
  pushChanges
)
