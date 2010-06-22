package reaktor.scct

import scala.tools.nsc.reporters.ConsoleReporter
import tools.nsc.{SubComponent, Global, CompilerCommand, Settings}
import java.io.{File, FileOutputStream}
import org.specs.matcher.Matcher
import org.specs.Specification

trait InstrumentationSpec extends Specification {
  def instrument = addToSusVerb("instrument")

  def compileFile(file: String) = compileFiles(Seq(file) :_*)
  def compileFiles(args: String*) = {
    val settings = createSettings
    val command = new CompilerCommand(args.toList, settings)
    val runner = new PluginRunner(settings)
    (new runner.Run).compile(command.files)
    runner.scctComponent
  }

  def createSettings = {
    val settings = new Settings
    // NOTE: fixme, hardcoded paths
    val root = "/Users/mtkopone/projects/scct"
    val scalaLibs = root + "/project/boot/scala-2.8.0.RC6/lib"
    val classDir = if (System.getProperty("java.class.path").contains("sbt-launch")) {
      root + "/scct/target/scala_2.8.0.RC6/classes"
    } else {
      root + "/out/production/scct"
    }
    settings.classpath.value = List(classDir, scalaLibs+"/scala-compiler.jar", scalaLibs+"/scala-library.jar").mkString(":")
    /*
    if (System.getProperty("java.class.path").contains("sbt-launch")) {
      val classDir = if (System.getProperty("scct-self-test", "false").toBoolean) "coverage-classes" else "classes"
      settings.classpath.value = "project/boot/scala-2.8.0.RC6/lib/scala-library.jar:scct/target/scala_2.8.0.RC6/"+classDir
    }
    */
    //println("CompilerCP:\n"+settings.classpath.value.toString.split(":").mkString("\n"))
    settings
  }

  def compile(line: String): List[CoveredBlock] = {
    Some(line).map(writeFile).map(compileFile).map(_.data).map(sort).get
  }

  def writeFile(line: String): String = {
    val f = File.createTempFile("scct-test-compiler", ".scala")
    IO.withOutputStream(new FileOutputStream(f)) { out => out.write(line.getBytes("utf-8")) }
    f.getAbsolutePath
  }

  def sort(data: List[CoveredBlock]) = data.sortWith { (a,b) =>
    if (a.name.sourceFile == b.name.sourceFile) {
      a.offset < b.offset
    } else a.name.sourceFile < b.name.sourceFile
  }

  def classOffsetsMatch(s: String) {
    offsetsMatch("class Foo@(x: Int) {\n  "+s+"\n}")
  }
  def defOffsetsMatch(s: String) {
    offsetsMatch("class Foo@(x: Int) {\n  def foo {\n    "+s+"\n  }\n}")
  }
  def offsetsMatch(s: String) {
    offsetsMatch(parse(0, s, InstrumentationSpec("", Nil)), false)
  }
  def placeHoldersMatch(s: String) {
    offsetsMatch(parse(0, s, InstrumentationSpec("", Nil)), true)
  }
  def offsetsMatch(spec: InstrumentationSpec, placeHoldersOnly: Boolean) {
    val resultOffsets = compile(spec.source).filter(x => placeHoldersOnly == x.placeHolder).map(_.offset)
    resultOffsets must matchSpec(spec)
  }

  private def parse(current: Int, s: String, acc: InstrumentationSpec): InstrumentationSpec = {
    val (curr, next) = splitAtMark(s)
    if (next.length > 0) {
      val newAcc = acc + (curr, current + curr.length)
      parse(current + curr.length, next, newAcc)
    } else {
      acc + curr
    }
  }

  private def splitAtMark(s: String): Tuple2[String,String] = {
    var isEscape = false
    val idx = s.findIndexOf { _ match {
      case '@' if !isEscape => true
      case '\\' => { isEscape = true; false }
      case _ => { isEscape = false; false }
    }}
    if (idx >= 0)
      (s.substring(0, idx).replace("\\@", "@"), s.substring(idx + 1))
    else
      (s.replace("\\@", "@"), "")
  }

  case class matchSpec(spec: InstrumentationSpec) extends Matcher[List[Int]]() {
    def toS(l: Seq[Int]) = l.mkString("[",",","]")
    def apply(v: => List[Int]) = (v == spec.expectedOffsets, "ok",
            "Offset mismatch: %s != %s\n\n%s\n - doesn't match expected - \n\n%s".format(toS(v), toS(spec.expectedOffsets), printOffsets(v, spec.source), printOffsets(spec.expectedOffsets, spec.source)))
  }

  case class InstrumentationSpec(source: String, expectedOffsets: List[Int]) {
    def +(s: String): InstrumentationSpec = InstrumentationSpec(source + s, expectedOffsets)
    def +(s: String, o: Int): InstrumentationSpec = InstrumentationSpec(source + s, expectedOffsets ::: List(o))
  }

  val displayedTabWidth = 2
  val tab = 1.to(displayedTabWidth).map(_ => " ").mkString

  def printOffsets(offsets: Iterable[Int], compilationUnit: String): String = {
    printOffsets(0, offsets, compilationUnit.split("\n").toList)
  }
  private def printOffsets(offset: Int, data: Iterable[Int], compilationUnit: List[String]): String = {
    compilationUnit match {
      case Nil => ""
      case line :: tail => {
        val maxOffset = offset + line.length
        val (currData, nextData) = data.partition(_ < maxOffset)
        val blocks = blockLine(line, offset, currData)
        line.replaceAll("\t", tab) + blocks.map("\n"+_).getOrElse("") + "\n" + printOffsets(maxOffset + 1, nextData, tail)
      }
    }
  }
  private def blockLine(line: String, offset: Int, data: Iterable[Int]): Option[String] = data match {
    case Nil => None
    case d => Some(blockIndicators(calculateTabbedOffsets(line.toList, data.map(_ - offset).toList)) + " ("+d.mkString(":")+")")
  }
  private def calculateTabbedOffsets(line: List[Char], data: List[Int]): List[Int] = data match {
    case Nil => Nil
    case offset :: tail => {
      val (pre, post) = line.splitAt(offset)
      val numTabs = pre.count(_ == '\t')
      (offset + (numTabs*(displayedTabWidth-1))) :: calculateTabbedOffsets(post, tail)
    }
  }
  private def blockIndicators(columns: List[Int]) = columns match {
    case Nil => ""
    case _ => {
      val s = 0.to(columns.last+1).map(idx => " ").mkString
      columns.foldLeft(s) { (result, idx) => result.substring(0,idx)+"^"+result.substring(idx+1) }
    }
  }
}

class PluginRunner(settings: Settings) extends Global(settings, new ConsoleReporter(settings)) {
  lazy val scctComponent = {
    val scctTransformer = new ScctTransformComponent(this)
    scctTransformer.saveData = false
    scctTransformer
  }
  override def computeInternalPhases() {
    phasesSet += syntaxAnalyzer
    phasesSet += analyzer.namerFactory
    phasesSet += analyzer.packageObjects
    phasesSet += analyzer.typerFactory
    phasesSet += superAccessors
    phasesSet += pickler
    phasesSet += refchecks
    phasesSet += scctComponent
  }
}