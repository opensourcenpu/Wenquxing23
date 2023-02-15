/**************************************************************************************
* Copyright (c) 2020 Institute of Computing Technology, CAS
* Copyright (c) 2020 University of Chinese Academy of Sciences
* 
* NutShell is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2. 
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2 
* 
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER 
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR 
* FIT FOR A PARTICULAR PURPOSE.  
*
* See the Mulan PSL v2 for more details.  
***************************************************************************************/

package device

import chisel3._
import chisel3.util._

import bus.axi4._
import utils._
import top.Settings

trait HasVGAConst {
  val ScreenW = 800
  val ScreenH = 600

  val HFrontPorch = 56
  val HActive = HFrontPorch + 120
  val HBackPorch = HActive + ScreenW
  val HTotal = HBackPorch + 64
  val VFrontPorch = 37
  val VActive = VFrontPorch + 6
  val VBackPorch = VActive + ScreenH
  val VTotal = VBackPorch + 23
}

trait HasHDMIConst {
  val ScreenW = 800
  val ScreenH = 600

  val HFrontPorch = 40
  val HActive = HFrontPorch + 128
  val HBackPorch = HActive + ScreenW
  val HTotal = HBackPorch + 88
  val VFrontPorch = 1
  val VActive = VFrontPorch + 4
  val VBackPorch = VActive + ScreenH
  val VTotal = VBackPorch + 23
}

trait HasVGAParameter extends HasHDMIConst {
  val FBWidth = ScreenW / 2
  val FBHeight = ScreenH / 2
  val FBPixels = FBWidth * FBHeight
}

class VGABundle extends Bundle {
  val rgb = Output(UInt(24.W))
  val hsync = Output(Bool())
  val vsync = Output(Bool())
  val valid = Output(Bool())
}

class VGACtrlBundle extends Bundle {
  val sync = Output(Bool())
}

class VGACtrl extends AXI4SlaveModule(new AXI4Lite, new VGACtrlBundle) with HasVGAParameter {
  val fbSizeReg = Cat(FBWidth.U(16.W), FBHeight.U(16.W))
  val sync = in.aw.fire()

  val mapping = Map(
    RegMap(0x0, fbSizeReg, RegMap.Unwritable),
    RegMap(0x4, sync, RegMap.Unwritable)
  )

  RegMap.generate(mapping, raddr(3,0), in.r.bits.data,
    waddr(3,0), in.w.fire(), in.w.bits.data, MaskExpand(in.w.bits.strb))

  io.extra.get.sync := sync
}

class FBHelper extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val valid = Input(Bool())
    val pixel = Input(UInt(32.W))
    val sync = Input(Bool())
  }).suggestName("io")

  setInline("FBHelper.v",
    s"""
      |import "DPI-C" function void put_pixel(input int pixel);
      |import "DPI-C" function void vmem_sync();
      |
      |module FBHelper (
      |  input clk,
      |  input valid,
      |  input [31:0] pixel,
      |  input sync
      |);
      |
      |  always@(posedge clk) begin
      |    if (valid) put_pixel(pixel);
      |    if (sync) vmem_sync();
      |  end
      |
      |endmodule
     """.stripMargin)
}

class AXI4VGA(sim: Boolean = false) extends Module with HasVGAParameter {
  val AXIidBits = 2
  val io = IO(new Bundle {
    val in = new Bundle {
      val fb = Flipped(new AXI4Lite)
      val ctrl = Flipped(new AXI4Lite)
    }
    val vga = new VGABundle
  })

  val ctrl = Module(new VGACtrl)
  io.in.ctrl <> ctrl.io.in
  val fb = Module(new AXI4RAM(new AXI4Lite, memByte = FBPixels * 4))
  // writable by axi4lite
  // but it only readable by the internel controller
  fb.io.in.aw <> io.in.fb.aw
  fb.io.in.w <> io.in.fb.w
  io.in.fb.b <> fb.io.in.b
  io.in.fb.ar.ready := true.B
  io.in.fb.r.bits.data := 0.U
  io.in.fb.r.bits.resp := AXI4Parameters.RESP_OKAY
  io.in.fb.r.valid := BoolStopWatch(io.in.fb.ar.fire(), io.in.fb.r.fire(), startHighPriority = true)

  def inRange(x: UInt, start: Int, end: Int) = (x >= start.U) && (x < end.U)

  val (hCounter, hFinish) = Counter(true.B, HTotal)
  val (vCounter, vFinish) = Counter(hFinish, VTotal)

  io.vga.hsync := hCounter >= HFrontPorch.U
  io.vga.vsync := vCounter >= VFrontPorch.U

  val hInRange = inRange(hCounter, HActive, HBackPorch)
  val vInRange = inRange(vCounter, VActive, VBackPorch)
  io.vga.valid := hInRange && vInRange

  val hCounterIsOdd = hCounter(0)
  val hCounterIs2 = hCounter(1,0) === 2.U
  val vCounterIsOdd = vCounter(0)
  // there is 2 cycle latency to read block memory,
  // so we should issue the read request 2 cycle eariler
  val nextPixel = inRange(hCounter, HActive - 1, HBackPorch - 1) && vInRange && hCounterIsOdd
  val fbPixelAddrV0 = Counter(nextPixel && !vCounterIsOdd, FBPixels)._1
  val fbPixelAddrV1 = Counter(nextPixel &&  vCounterIsOdd, FBPixels)._1

  // each pixel is 4 bytes
  fb.io.in.ar.bits.prot := 0.U
  fb.io.in.ar.bits.addr := Cat(Mux(vCounterIsOdd, fbPixelAddrV1, fbPixelAddrV0), 0.U(2.W))
  fb.io.in.ar.valid := RegNext(nextPixel) && hCounterIs2

  fb.io.in.r.ready := true.B
  val data = HoldUnless(fb.io.in.r.bits.data, fb.io.in.r.fire())
  val color = if (Settings.get("IsRV32")) data(31, 0)
              else Mux(hCounter(1), data(63, 32), data(31, 0))
  io.vga.rgb := Mux(io.vga.valid, color(23, 0), 0.U)

  if (sim) {
    val fbHelper = Module(new FBHelper)
    fbHelper.io.clk := clock
    fbHelper.io.valid := io.vga.valid
    fbHelper.io.pixel := color
    fbHelper.io.sync := ctrl.io.extra.get.sync
  }
}
