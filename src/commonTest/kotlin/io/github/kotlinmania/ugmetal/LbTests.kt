// port-lint: tests tests/lb_tests.rs
package io.github.kotlinmania.ugmetal

import io.github.kotlinmania.ug.CommonTests
import io.github.kotlinmania.ug.cpuruntime.CpuDevice
import io.github.kotlinmania.ugmetal.runtime.Device
import kotlin.test.Test

class LbTests {
    @Test
    fun lbMetalAdd() {
        val dev = Device.new()
        CommonTests.lb.add(dev)
    }

    @Test
    fun lbMetalMm() {
        val dev = Device.new()
        CommonTests.lb.mm(dev)
    }

    @Test
    fun lbMetalCat() {
        CommonTests.lb.cat(CpuDevice)
    }
}
