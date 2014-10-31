name := "hpaste"

organization := "com.gravity"

organizationName := "Gravity"

organizationHomepage := Some(url("http://www.gravity.com"))

version := "0.1.25-CDH5.1.3"

licenses := Seq("Apache2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

homepage := Some(url("https://github.com/GravityLabs/HPaste"))

scalaVersion := "2.10.4"

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:postfixOps",
  "-language:existentials"
)

resolvers ++= Seq(
  "Cloudera releases" at "https://repository.cloudera.com/artifactory/libs-release"
)

libraryDependencies ++= Seq(
  "joda-time" % "joda-time" % "1.6.2", // "2.5"
  "org.apache.hadoop" % "hadoop-client" % "2.3.0-mr1-cdh5.1.3",
  "org.apache.hbase" % "hbase-common" % "0.98.1-cdh5.1.3" exclude("org.jruby", "jruby-complete"),
  "org.apache.hbase" % "hbase-client" % "0.98.1-cdh5.1.3" exclude("org.jruby", "jruby-complete"),
  "net.sf.trove4j" % "trove4j" % "3.0.3"
)

publishMavenStyle := true

pomIncludeRepository := { x => false }

publishTo <<= version { (v: String) =>
  val nexus = "http://nexus.rnd.unicredit.eu/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("3rdparty"  at nexus + "content/repositories/thirdparty")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")