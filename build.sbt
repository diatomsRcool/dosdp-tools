enablePlugins(JavaAppPackaging)

organization  := "org.monarchinitiative"

name          := "dosdp-tools"

version       := "0.15.1"

scalaVersion  := "2.13.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

scalacOptions in Test ++= Seq("-Yrangepos")

mainClass in Compile := Some("org.monarchinitiative.dosdp.cli.Main")

javaOptions += "-Xmx8G"

scriptClasspath := Seq("*")

libraryDependencies ++= {
    Seq(
      "net.sourceforge.owlapi"     %  "owlapi-distribution"    % "4.5.17",
      "org.phenoscape"             %% "scowl"                  % "1.3.4",
      "org.phenoscape"             %% "owlet"                  % "1.8.1" exclude("org.slf4j", "slf4j-log4j12"),
      "org.semanticweb.elk"        %  "elk-owlapi"             % "0.4.3" exclude("org.slf4j", "slf4j-log4j12"),
      "net.sourceforge.owlapi"     %  "org.semanticweb.hermit" % "1.4.3.456",
      "net.sourceforge.owlapi"     %  "jfact"                  % "4.0.4",
      "io.circe"                   %% "circe-yaml"             % "0.13.1",
      "io.circe"                   %% "circe-core"             % "0.13.0",
      "io.circe"                   %% "circe-generic"          % "0.13.0",
      "io.circe"                   %% "circe-parser"           % "0.13.0",
      "org.obolibrary.robot"       %  "robot-core"             % "1.7.0" exclude("org.slf4j", "slf4j-log4j12"),
      "com.github.pathikrit"       %% "better-files"           % "3.9.1",
      "org.apache.jena"            %  "apache-jena-libs"       % "3.16.0" exclude("org.slf4j", "slf4j-log4j12"),
      "org.backuity.clist"         %% "clist-core"             % "3.5.1",
      "org.backuity.clist"         %% "clist-macros"           % "3.5.1" % "provided",
      "com.github.tototoshi"       %% "scala-csv"              % "1.3.6",
      "commons-codec"              %  "commons-codec"          % "1.15",
      "com.outr"                   %% "scribe-slf4j"           % "2.7.12",
      "org.scalatest"              %% "scalatest"              % "3.2.2" % Test
    )
}
