scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.0.1",
  "org.typelevel" %% "cats-effect" % "0.8",
  "co.fs2" %% "fs2-core" % "0.10.0-M11"
)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-unchecked",
  "-language:higherKinds"
)

//scalacOptions += "-Ypartial-unification"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4")

ensimeIgnoreMissingDirectories in ThisBuild := true
ensimeIgnoreScalaMismatch in ThisBuild := true

