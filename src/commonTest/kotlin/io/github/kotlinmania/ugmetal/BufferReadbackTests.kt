package io.github.kotlinmania.ugmetal

import io.github.kotlinmania.half.BF16
import io.github.kotlinmania.half.F16
import io.github.kotlinmania.ug.DType
import io.github.kotlinmania.ug.UgError
import io.github.kotlinmania.ug.WithDType
import io.github.kotlinmania.ugmetal.runtime.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BufferReadbackTests {
    @Test
    fun scalarBuffersPreserveValuesAndTypes() {
        val device = Device.new()
        val floats = listOf(-2.5f, -0.0f, 0f, Float.MAX_VALUE)
        val doubles = listOf(-2.5, -0.0, 0.0, Double.MAX_VALUE)
        val ints = listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)
        val longs = listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)
        val shorts = listOf(Short.MIN_VALUE, 0.toShort(), Short.MAX_VALUE)
        val bytes = listOf(Byte.MIN_VALUE, 0.toByte(), Byte.MAX_VALUE)
        val halves = listOf(F16.fromBits(0x8000u), F16.fromF32(1.5f), F16.fromBits(0x7bffu))
        val brainHalves = listOf(BF16.fromBits(0x8000u), BF16.fromF32(1.5f), BF16.fromBits(0x7f7fu))

        assertEquals(floats.map { it.toRawBits() }, device.sliceFromValues(floats).toVec().map { it.toRawBits() })
        assertEquals(doubles.map { it.toRawBits() }, device.sliceFromValues(doubles).toVec().map { it.toRawBits() })
        assertEquals(ints, device.sliceFromValues(ints).toVec())
        assertEquals(longs, device.sliceFromValues(longs).toVec())
        assertEquals(shorts, device.sliceFromValues(shorts).toVec())
        assertEquals(bytes, device.sliceFromValues(bytes).toVec())
        assertEquals(halves.map { it.toBits() }, device.sliceFromValues(halves).toVec().map { it.toBits() })
        assertEquals(brainHalves.map { it.toBits() }, device.sliceFromValues(brainHalves).toVec().map { it.toBits() })
    }

    @Test
    fun zeroedBuffersRetainTheirElementType() {
        val device = Device.new()
        assertEquals(listOf(0f, 0f), device.zeros<Float>(2).toVec())
        assertEquals(listOf(0.0, 0.0), device.zeros<Double>(2).toVec())
        assertEquals(listOf(0, 0), device.zeros<Int>(2).toVec())
        assertEquals(listOf(0L, 0L), device.zeros<Long>(2).toVec())
        assertEquals(listOf(0.toShort()), device.zeros<Short>(1).toVec())
        assertEquals(listOf(0.toByte()), device.zeros<Byte>(1).toVec())
        assertEquals(listOf(F16()), device.zeros<F16>(1).toVec())
        assertEquals(listOf(BF16()), device.zeros<BF16>(1).toVec())
        val empty = device.zeros<Int>(0)
        assertTrue(empty.isEmpty())
        assertEquals(emptyList(), empty.toVec())
    }

    @Test
    fun readbackUsesCurrentBufferAndReturnsIndependentValues() {
        val original = mutableListOf(7, 9)
        val slice = Device.new().sliceFromValues(original)
        original[0] = 99
        val firstRead = slice.toVec()
        assertEquals(listOf(7, 9), firstRead)
        slice.buffer.data()[0] = 42
        assertEquals(listOf(42, 9), slice.toVec())
        assertEquals(listOf(7, 9), firstRead)
    }

    @Test
    fun scalarDescriptorsReadPublishedUgTypes() {
        val device = Device.new()
        val floats = device.allocateUninit(DType.F32, 2)
        floats.copyHostToDevice(floatArrayOf(-1.5f, 2.5f))
        assertEquals(listOf(-1.5f, 2.5f), floats.toVec(WithDType.F32))
        assertEquals(floats.toVec(WithDType.F32), floats.toVec<Float>())
        val ints = device.allocateUninit(DType.I32, 2)
        ints.copyHostToDevice(intArrayOf(-1, 42))
        assertEquals(listOf(-1, 42), ints.toVec(WithDType.I32))
        val longs = device.allocateUninit(DType.I64, 1)
        longs.copyHostToDevice(longArrayOf(Long.MIN_VALUE))
        assertEquals(listOf(Long.MIN_VALUE), longs.toVec(WithDType.I64))
        val halves = device.allocateUninit(DType.F16, 1)
        halves.copyHostToDevice(arrayOf(F16.fromF32(1.5f)))
        assertEquals(listOf(F16.fromF32(1.5f)), halves.toVec(WithDType.F16))
        assertEquals(halves.toVec(WithDType.F16), halves.toVec<F16>())
        val brainHalves = device.allocateUninit(DType.BF16, 1)
        brainHalves.copyHostToDevice(arrayOf(BF16.fromF32(-2.5f)))
        assertEquals(listOf(BF16.fromF32(-2.5f)), brainHalves.toVec(WithDType.BF16))
        assertEquals(brainHalves.toVec(WithDType.BF16), brainHalves.toVec<BF16>())
    }

    @Test
    fun unsupportedElementTypeRetainsItsDomainError() {
        val exception = assertFailsWith<Exception> { Device.new().zeros<String>(1) }
        val error = assertIs<UgError.Msg>(UgError.fromException(exception))
        assertTrue(error.message.startsWith("unsupported element type:"))
    }
}
