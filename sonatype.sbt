sonatypeCredentialHost := "s01.oss.sonatype.org"

sonatypeProfileName := "com.madgag"

ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/rtyley/play-git-hub"),
  "scm:git:git@github.com:rtyley/play-git-hub.git"
))

ThisBuild / licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / publishTo := sonatypePublishToBundle.value

ThisBuild / pomExtra := (
  <url>https://github.com/rtyley/scala-git</url>
    <developers>
      <developer>
        <id>rtyley</id>
        <name>Roberto Tyley</name>
        <url>https://github.com/rtyley</url>
      </developer>
    </developers>
  )
