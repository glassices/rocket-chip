// See LICENSE for license details.

package coreplex

import Chisel._
import scala.collection.mutable.{LinkedHashSet,LinkedHashMap}
import cde.{Parameters, ParameterDump, Config, Field, CDEMatchError}

case object RegressionTestNames extends Field[LinkedHashSet[String]]

abstract class RocketTestSuite {
  val dir: String
  val makeTargetName: String
  val names: LinkedHashSet[String]
  val envName: String
  def postScript = s"""

$$(addprefix $$(output_dir)/, $$(addsuffix .hex, $$($makeTargetName))): $$(output_dir)/%.hex: $dir/%.hex
\tmkdir -p $$(output_dir)
\tln -fs $$< $$@

$$(addprefix $$(output_dir)/, $$($makeTargetName)): $$(output_dir)/%: $dir/%
\tmkdir -p $$(output_dir)
\tln -fs $$< $$@

run-$makeTargetName: $$(addprefix $$(output_dir)/, $$(addsuffix .out, $$($makeTargetName)))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$^ /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'

run-$makeTargetName-debug: $$(addprefix $$(output_dir)/, $$(addsuffix .vpd, $$($makeTargetName)))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$(patsubst %.vpd,%.out,$$^) /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
"""
}

class AssemblyTestSuite(prefix: String, val names: LinkedHashSet[String])(val envName: String) extends RocketTestSuite {
  val dir = "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/isa"
  val makeTargetName = prefix + "-" + envName + "-asm-tests"
  override def toString = s"$makeTargetName = \\\n" + names.map(n => s"\t$prefix-$envName-$n").mkString(" \\\n") + postScript
}

class BenchmarkTestSuite(makePrefix: String, val dir: String, val names: LinkedHashSet[String]) extends RocketTestSuite {
  val envName = ""
  val makeTargetName = makePrefix + "-bmark-tests"
  override def toString = s"$makeTargetName = \\\n" + names.map(n => s"\t$n.riscv").mkString(" \\\n") + postScript
}

class RegressionTestSuite(val names: LinkedHashSet[String]) extends RocketTestSuite {
  val envName = ""
  val dir = "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/isa"
  val makeTargetName = "regression-tests"
  override def toString = s"$makeTargetName = \\\n" + names.mkString(" \\\n")
}

object TestGeneration {
  import scala.collection.mutable.HashMap
  val asmSuites = new LinkedHashMap[String,AssemblyTestSuite]()
  val bmarkSuites = new LinkedHashMap[String,BenchmarkTestSuite]()
  val regressionSuites = new LinkedHashMap[String,RegressionTestSuite]()

  def addSuite(s: RocketTestSuite) {
    s match {
      case a: AssemblyTestSuite => asmSuites += (a.makeTargetName -> a)
      case b: BenchmarkTestSuite => bmarkSuites += (b.makeTargetName -> b)
      case r: RegressionTestSuite => regressionSuites += (r.makeTargetName -> r)
    }
  }
  
  def addSuites(s: Seq[RocketTestSuite]) { s.foreach(addSuite) }

  def generateMakefrag: String = {
    def gen(kind: String, s: Seq[RocketTestSuite]) = {
      if(s.length > 0) {
        val envs = s.groupBy(_.envName)
        val targets = s.map(t => s"$$(${t.makeTargetName})").mkString(" ")
        s.map(_.toString).mkString("\n") +
        envs.filterKeys(_ != "").map( {
          case (env,envsuites) => {
          val suites = envsuites.map(t => s"$$(${t.makeTargetName})").mkString(" ")
        s"""
run-$kind-$env-tests: $$(addprefix $$(output_dir)/, $$(addsuffix .out, $suites))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$^ /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
run-$kind-$env-tests-debug: $$(addprefix $$(output_dir)/, $$(addsuffix .vpd, $suites))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$(patsubst %.vpd,%.out,$$^) /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
run-$kind-$env-tests-fast: $$(addprefix $$(output_dir)/, $$(addsuffix .run, $suites))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$^ /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
"""} } ).mkString("\n") + s"""
run-$kind-tests: $$(addprefix $$(output_dir)/, $$(addsuffix .out, $targets))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$^ /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
run-$kind-tests-debug: $$(addprefix $$(output_dir)/, $$(addsuffix .vpd, $targets))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$(patsubst %.vpd,%.out,$$^) /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
run-$kind-tests-fast: $$(addprefix $$(output_dir)/, $$(addsuffix .run, $targets))
\t@echo; perl -ne 'print "  [$$$$1] $$$$ARGV \\t$$$$2\\n" if( /\\*{3}(.{8})\\*{3}(.*)/ || /ASSERTION (FAILED)/i )' $$^ /dev/null | perl -ne 'if(/(.*)/){print "$$$$1\\n\\n"; exit(1) if eof()}'
"""
      } else { "\n" }
    }

    List(
      gen("asm", asmSuites.values.toSeq),
      gen("bmark", bmarkSuites.values.toSeq),
      gen("regression", regressionSuites.values.toSeq)
    ).mkString("\n")
  }

}

object DefaultTestSuites {
  val rv32uiNames = LinkedHashSet(
    "simple", "add", "addi", "and", "andi", "auipc", "beq", "bge", "bgeu", "blt", "bltu", "bne", "fence_i", 
    "jal", "jalr", "lb", "lbu", "lh", "lhu", "lui", "lw", "or", "ori", "sb", "sh", "sw", "sll", "slli",
    "slt", "slti", "sra", "srai", "srl", "srli", "sub", "xor", "xori")
  val rv32ui = new AssemblyTestSuite("rv32ui", rv32uiNames)(_)

  val rv32ucNames = LinkedHashSet("rvc")
  val rv32uc = new AssemblyTestSuite("rv32uc", rv32ucNames)(_)

  val rv32umNames = LinkedHashSet("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")
  val rv32um = new AssemblyTestSuite("rv32um", rv32umNames)(_)

  val rv32uaNames = LinkedHashSet("lrsc", "amoadd_w", "amoand_w", "amoor_w", "amoxor_w", "amoswap_w", "amomax_w", "amomaxu_w", "amomin_w", "amominu_w")
  val rv32ua = new AssemblyTestSuite("rv32ua", rv32uaNames)(_)

  val rv32siNames = LinkedHashSet("csr", "ma_fetch", "scall", "sbreak", "wfi", "dirty")
  val rv32si = new AssemblyTestSuite("rv32si", rv32siNames)(_)

  val rv32miNames = LinkedHashSet("csr", "mcsr", "illegal", "ma_addr", "ma_fetch", "sbreak", "scall")
  val rv32mi = new AssemblyTestSuite("rv32mi", rv32miNames)(_)

  val rv32u = List(rv32ui, rv32um)
  val rv32i = List(rv32ui, rv32si, rv32mi)
  val rv32pi = List(rv32ui, rv32mi)

  val rv64uiNames = LinkedHashSet("addw", "addiw", "ld", "lwu", "sd", "slliw", "sllw", "sltiu", "sltu", "sraiw", "sraw", "srliw", "srlw", "subw")
  val rv64ui = new AssemblyTestSuite("rv64ui", rv32uiNames ++ rv64uiNames)(_)

  val rv64umNames = LinkedHashSet("divuw", "divw", "mulw", "remuw", "remw")
  val rv64um = new AssemblyTestSuite("rv64um", rv32umNames ++ rv64umNames)(_)

  val rv64uaNames = rv32uaNames.map(_.replaceAll("_w","_d"))
  val rv64ua = new AssemblyTestSuite("rv64ua", rv32uaNames ++ rv64uaNames)(_)

  val rv64ucNames = rv32ucNames
  val rv64uc = new AssemblyTestSuite("rv64uc", rv64ucNames)(_)

  val rv64ufNames = LinkedHashSet("ldst", "move", "fsgnj", "fcmp", "fcvt", "fcvt_w", "fclass", "fadd", "fdiv", "fmin", "fmadd")
  val rv64uf = new AssemblyTestSuite("rv64uf", rv64ufNames)(_)
  val rv64ufNoDiv = new AssemblyTestSuite("rv64uf", rv64ufNames - "fdiv")(_)

  val rv64udNames = rv64ufNames + "structural"
  val rv64ud = new AssemblyTestSuite("rv64ud", rv64udNames)(_)
  val rv64udNoDiv = new AssemblyTestSuite("rv64ud", rv64udNames - "fdiv")(_)

  val rv64siNames = rv32siNames
  val rv64si = new AssemblyTestSuite("rv64si", rv64siNames)(_)

  val rv64miNames = rv32miNames + "breakpoint"
  val rv64mi = new AssemblyTestSuite("rv64mi", rv64miNames)(_)

  val groundtestNames = LinkedHashSet("simple")
  val groundtest64 = new AssemblyTestSuite("rv64ui", groundtestNames)(_)
  val groundtest32 = new AssemblyTestSuite("rv32ui", groundtestNames)(_)

  // TODO: "rv64ui-pm-lrsc", "rv64mi-pm-ipi",

  val rv64u = List(rv64ui, rv64um)
  val rv64i = List(rv64ui, rv64si, rv64mi)
  val rv64pi = List(rv64ui, rv64mi)

  val benchmarks = new BenchmarkTestSuite("rvi", "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks", LinkedHashSet(
    "median", "multiply", "qsort", "towers", "vvadd", "dhrystone", "mt-matmul"))

  val rv32udBenchmarks = new BenchmarkTestSuite("rvd", "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks", LinkedHashSet(
    "mm", "spmv", "mt-vvadd"))

  val emptyBmarks = new BenchmarkTestSuite("empty",
    "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks", LinkedHashSet.empty)

  val mtBmarks = new BenchmarkTestSuite("mt", "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/mt",
    LinkedHashSet(((0 to 4).map("vvadd"+_) ++
    List("ad","ae","af","ag","ai","ak","al","am","an","ap","aq","ar","at","av","ay","az",
         "bb","bc","bf","bh","bj","bk","bm","bo","br","bs","ce","cf","cg","ci","ck","cl",
         "cm","cs","cv","cy","dc","df","dm","do","dr","ds","du","dv").map(_+"_matmul")): _*))
}
