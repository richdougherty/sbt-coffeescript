/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.coffeescript.sbt

import akka.actor.ActorRefFactory
import com.typesafe.coffeescript._
import com.typesafe.jse.Node
import com.typesafe.sbt.web.{CompileProblems, LineBasedProblem}
import com.typesafe.sbt.web.SbtWebPlugin
import com.typesafe.sbt.web.incremental._
import com.typesafe.sbt.jse.SbtJsEnginePlugin
import _root_.sbt._
import _root_.sbt.Keys._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import spray.json._
import xsbti.{Problem, Severity}

final case class CoffeeScriptPluginException(message: String) extends Exception(message)

object CoffeeScriptPlugin extends Plugin {

  private def cs(setting: String) = s"coffeeScript-$setting"

  object CoffeeScriptKeys {
    val coffeeScript = TaskKey[Unit]("coffeeScript", "Compile CoffeeScript sources into JavaScript.")
    val sourceFilter = SettingKey[FileFilter](cs("filter"), "A filter matching CoffeeScript and literate CoffeeScript sources.")
    val outputDirectory = SettingKey[File](cs("output-directory"), "The output directory for compiled JavaScript files and source maps.")
    val literateFilter = SettingKey[NameFilter](cs("literate-filter"), "A filter to identify literate CoffeeScript files.")
    val bare = SettingKey[Boolean](cs("bare"), "Compiles JavaScript that isn't wrapped in a function.")
    val sourceMaps = SettingKey[Boolean](cs("source-maps"), "Generate source map files.")
    val compileArgs = TaskKey[Seq[CompileArgs]](cs("compile-args"), "CompileArgs instructions for the CoffeeScript compiler.")
  }

  import SbtJsEnginePlugin.JsEngineKeys._
  import SbtWebPlugin.WebKeys._
  import CoffeeScriptKeys._

  /**
   * Use this to import CoffeeScript settings into a specific scope,
   * e.g. `Project.inConfig(WebKeys.Assets)(scopedSettings)`. These settings intentionally
   * have no dependency on sbt-web settings or directories, making it possible to use these
   * settings for non-web CoffeeScript compilation.
   */
  val unscopedSettings: Seq[Setting[_]] = Seq(
    compile <<= compile.dependsOn(coffeeScript),
    CoffeeScriptKeys.outputDirectory := resourceManaged.value,
    includeFilter in CoffeeScriptKeys.coffeeScript := GlobFilter("*.coffee") | GlobFilter("*.litcoffee"),
    excludeFilter in CoffeeScriptKeys.coffeeScript := NothingFilter,
    sourceDirectory in CoffeeScriptKeys.coffeeScript := sourceDirectory.value,
    sources in CoffeeScriptKeys.coffeeScript := {
      val dirs = (sourceDirectories in CoffeeScriptKeys.coffeeScript).value
      val include = (includeFilter in CoffeeScriptKeys.coffeeScript).value
      val exclude = (excludeFilter in CoffeeScriptKeys.coffeeScript).value
      (dirs ** (include -- exclude)).get
    },
    CoffeeScriptKeys.sourceMaps := true,
    CoffeeScriptKeys.bare := false,
    CoffeeScriptKeys.literateFilter := GlobFilter("*.litcoffee"),
    CoffeeScriptKeys.compileArgs := {
      val literateFilter = CoffeeScriptKeys.literateFilter.value
      val sourceMaps = CoffeeScriptKeys.sourceMaps.value

      // http://www.scala-sbt.org/release/docs/Detailed-Topics/Mapping-Files.html
      val inputSources = (sources in CoffeeScriptKeys.coffeeScript).value.get
      val inputDirectories = (sourceDirectories in CoffeeScriptKeys.coffeeScript).value.get
      val outputDirectory = CoffeeScriptKeys.outputDirectory.value
      for {
        (csFile, rebasedFile) <- inputSources x rebase(inputDirectories, outputDirectory)
      } yield {
        val parent = rebasedFile.getParent
        val name = rebasedFile.getName
        val baseName = {
          val dotIndex = name.lastIndexOf('.')
          if (dotIndex == -1) name else name.substring(0, dotIndex)
        }
        val jsFileName = baseName + ".js"
        val jsFile = new File(parent, jsFileName)
        val mapFileName = jsFileName + ".map"
        val mapFile = new File(parent, mapFileName)

        val sourceMapOpts = if (sourceMaps) {
          Some(SourceMapOptions(
            sourceMapOutputFile = mapFile,
            sourceMapRef = mapFileName,
            javaScriptFileName = jsFileName,
            coffeeScriptRootRef = "",
            coffeeScriptPathRefs = List(name)
          ))
        } else None
        CompileArgs(
          coffeeScriptInputFile = csFile,
          javaScriptOutputFile = jsFile,
          sourceMapOpts = sourceMapOpts,
          bare = CoffeeScriptKeys.bare.value,
          literate = literateFilter.accept(name)
        )
      }
    },
    CoffeeScriptKeys.coffeeScript := {
      val log = streams.value.log
      val compiles = CoffeeScriptKeys.compileArgs.value.to[Vector]
      val sbtState = state.value
      val cacheDirectory = streams.value.cacheDirectory

      val problems = runIncremental[CompileArgs, Seq[Problem]](cacheDirectory, compiles) { neededCompiles: Seq[CompileArgs] =>
        val sourceCount = neededCompiles.length

        if (sourceCount == 0) (Map.empty, Seq.empty) else {
          val sourceString = if (sourceCount == 1) "source" else "sources"
          log.info(s"Compiling ${sourceCount} CoffeeScript ${sourceString}...")

          val compiler = CoffeeScriptCompiler.withShellFileCopiedTo(cacheDirectory / "shell.js")

          SbtWebPlugin.withActorRefFactory(sbtState, "coffeeScriptCompile") { implicit actorRefFactory =>
            import actorRefFactory.dispatcher
            val jsExecutor = new DefaultJsExecutor(Node.props(), actorRefFactory)
            neededCompiles.foldLeft[(Map[CompileArgs,OpResult], Seq[Problem])]((Map.empty, Seq.empty)) {
              case ((resultMap, problemSeq), compilation) => runSingleCompile(compiler, jsExecutor, compilation) match {
                case (newResult, newProblems) => (resultMap.updated(compilation, newResult), problemSeq ++ newProblems)
              }
            }
          }
        }
      }

      CompileProblems.report(reporter.value, problems)
    }
  )

  def runSingleCompile(compiler: CoffeeScriptCompiler, jsExecutor: JsExecutor, compilation: CompileArgs)(implicit ec: ExecutionContext): (OpResult, Seq[Problem]) = {
    compiler.compileFile(jsExecutor, compilation) match {
      case CompileSuccess =>
        (
          OpSuccess(
            filesRead = Set(compilation.coffeeScriptInputFile),
            filesWritten = Set(compilation.javaScriptOutputFile) ++ compilation.sourceMapOpts.map(_.sourceMapOutputFile).to[Set]
          ),
          Seq.empty
        )
      case err: CodeError =>
        (
          OpFailure,
          Seq(new LineBasedProblem(
            message = err.message,
            severity = Severity.Error,
            lineNumber = err.lineNumber,
            characterOffset = err.lineOffset,
            lineContent = err.lineContent,
            source = compilation.coffeeScriptInputFile
          ))
        )
      case err: GenericError =>
        throw CoffeeScriptPluginException(err.message)
    }
  }

  object TodoWebPlugin {
    def settings: Seq[Setting[_]] = Seq(
      // Stub compile tasks in WebPlugin
      compile in Assets := inc.Analysis.Empty,
      compile in TestAssets := inc.Analysis.Empty,
      compile in TestAssets <<= (compile in TestAssets).dependsOn(compile in Assets),

      // Stub test tasks in WebPlugin
      test in TestAssets := (),
      test in TestAssets <<= (test in TestAssets).dependsOn(compile in TestAssets),

      // Link tasks in web scopes to tasks in main scopes
      compile in Compile <<= (compile in Compile).dependsOn(compile in Assets),
      compile in Test <<= (compile in Test).dependsOn(compile in TestAssets),
      test in Test <<= (test in Test).dependsOn(test in TestAssets),

      // FIXME: remove
      sourceDirectories in Assets := (unmanagedSourceDirectories in Assets).value,
      sourceDirectories in TestAssets := (unmanagedSourceDirectories in TestAssets).value
    )
  }

  def coffeeScriptSettings: Seq[Setting[_]] =
    TodoWebPlugin.settings ++
    Project.inConfig(Assets)(unscopedSettings) ++
    Project.inConfig(TestAssets)(unscopedSettings) ++ Seq(
      coffeeScript := (coffeeScript in Assets).value
    )
}