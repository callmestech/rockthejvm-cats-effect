val scala3Version = "3.2.2"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "cats-effect",
    version      := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.3.14"
    ),
    scalacOptions ++= Seq("-new-syntax", "-rewrite")
  )
