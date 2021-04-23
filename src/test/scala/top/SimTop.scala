package top

import chipsalliance.rocketchip.config.{Config, Field, Parameters}
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3._
import chisel3.dontTouch
import device.{AXI4RAMWrapper, UARTIO}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import system.SoCParamsKey
import utils.GTimer
import xiangshan.{DebugOptions, DebugOptionsKey, PerfInfoIO}

class LogCtrlIO extends Bundle {
  val log_begin, log_end = Input(UInt(64.W))
  val log_level = Input(UInt(64.W)) // a cpp uint
}

class SimTop(implicit p: Parameters) extends Module {
  val debugOpts = p(DebugOptionsKey)
  val useDRAMSim = debugOpts.UseDRAMSim

  val l_soc = if(p(DutNameKey) == "boom") LazyModule(new BoomTop()) else LazyModule(new XSTopWithoutDMA())
  val soc = Module(l_soc.module)

  val l_simMMIO = LazyModule(new SimMMIO(l_soc.peripheralNode.in.head._2))
  val simMMIO = Module(l_simMMIO.module)
  l_simMMIO.connectToSoC(l_soc)

  if(!useDRAMSim){
    val l_simAXIMem = LazyModule(new AXI4RAMWrapper(
      l_soc.memAXI4SlaveNode, 2L * 1024 * 1024 * 1024, useBlackBox = true
    ))
    val simAXIMem = Module(l_simAXIMem.module)
    l_simAXIMem.connectToSoC(l_soc)
  }

  soc.io.clock := clock.asBool()
  soc.io.reset := reset.asBool()
  soc.io.extIntrs := 0.U
  dontTouch(soc.io.extIntrs)

  val io = IO(new Bundle(){
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val memAXI = if(useDRAMSim) l_soc.memory.cloneType else null
  })

  simMMIO.io.uart <> io.uart

  if(useDRAMSim){
    io.memAXI <> l_soc.memory
  }

  if (debugOpts.EnableDebug || debugOpts.EnablePerfDebug) {
    val timer = GTimer()
    val logEnable = (timer >= io.logCtrl.log_begin) && (timer < io.logCtrl.log_end)
    ExcitingUtils.addSource(logEnable, "DISPLAY_LOG_ENABLE")
    ExcitingUtils.addSource(timer, "logTimestamp")
  }

  if (debugOpts.EnablePerfDebug) {
    val clean = io.perfInfo.clean
    val dump = io.perfInfo.dump
    ExcitingUtils.addSource(clean, "XSPERF_CLEAN")
    ExcitingUtils.addSource(dump, "XSPERF_DUMP")
  }

  // Check and dispaly all source and sink connections
  ExcitingUtils.fixConnections()
  ExcitingUtils.checkAndDisplay()
}

case object DutNameKey extends Field[String]("XiangShan")

object SimTop extends App {

  override def main(args: Array[String]): Unit = {
    val (config, firrtlOpts) = ArgParser.parse(args, fpga = false)
    val boomSimConfig = new Config(
      config ++ new BoomTopConfig
    ).alterPartial({
      case DutNameKey => "boom"
    })

    // generate verilog
    XiangShanStage.execute(
      firrtlOpts,
      Seq(
        ChiselGeneratorAnnotation(() => new SimTop()(boomSimConfig))
      )
    )
  }
}