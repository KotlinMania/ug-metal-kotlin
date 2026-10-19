// port-lint: source src/runtime.rs
package io.github.kotlinmania.ugmetal.runtime

import io.github.kotlinmania.candlemetalkernels.metal.Buffer
import io.github.kotlinmania.candlemetalkernels.metal.CommandBuffer
import io.github.kotlinmania.candlemetalkernels.metal.CommandQueue
import io.github.kotlinmania.candlemetalkernels.metal.ComputeCommandEncoder
import io.github.kotlinmania.candlemetalkernels.metal.ComputePipeline
import io.github.kotlinmania.candlemetalkernels.metal.ConstantValue
import io.github.kotlinmania.candlemetalkernels.metal.ConstantValues
import io.github.kotlinmania.candlemetalkernels.metal.Function
import io.github.kotlinmania.candlemetalkernels.metal.MTLResourceOptions
import io.github.kotlinmania.candlemetalkernels.metal.MTLSize
import io.github.kotlinmania.candlemetalkernels.metal.Value
import io.github.kotlinmania.candlemetalkernels.metal.createCommandBuffer
import io.github.kotlinmania.half.BF16
import io.github.kotlinmania.half.F16
import io.github.kotlinmania.ug.BF16Type
import io.github.kotlinmania.ug.DType
import io.github.kotlinmania.ug.F16Type
import io.github.kotlinmania.ug.F32Type
import io.github.kotlinmania.ug.I32Type
import io.github.kotlinmania.ug.I64Type
import io.github.kotlinmania.ug.Layout
import io.github.kotlinmania.ug.UgError
import io.github.kotlinmania.ug.lang.LaunchConfig
import io.github.kotlinmania.ug.lang.ssa.Kernel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.github.kotlinmania.ug.toVec as ugToVec
import io.github.kotlinmania.ugmetal.codegen.gen as codeGen

private val MLX_GEMM: String = io.github.kotlinmania.candlemetalkernels.MLX_GEMM

public interface WithErr<out T> {
    public fun w(): T
}

public fun <T> kotlin.Result<T>.w(): T =
    getOrElse { UgError.Msg(it.message ?: it.toString()).raise() }

public data class KernelId(
    private val value: Int,
) : Comparable<KernelId> {
    public fun asUsize(): Int = value

    override fun compareTo(other: KernelId): Int = value.compareTo(other.value)

    public companion object {
        @OptIn(ExperimentalAtomicApi::class)
        private val counter = AtomicInt(1)

        @OptIn(ExperimentalAtomicApi::class)
        public fun new(): KernelId = KernelId(counter.addAndFetch(1))
    }
}

public class Func(
    public val inner: Function,
    public val launchConfig: LaunchConfig,
    public val device: Device,
    public val kernel: Kernel? = null,
) {
    public fun pipeline(): ComputePipeline =
        device.device.newComputePipelineStateWithFunction(inner)
}

public class Device(
    public val device: io.github.kotlinmania.candlemetalkernels.metal.Device =
        io.github.kotlinmania.candlemetalkernels.metal.Device
            .systemDefault()
            ?: UgError.Msg("no default device found").raise(),
    public val cq: CommandQueue = device.newCommandQueue(),
) : io.github.kotlinmania.ug.Device {
    public fun newCommandQueue(): CommandQueue = device.newCommandQueue()

    public fun newCommandBuffer(): CommandBuffer = createCommandBuffer(cq)

    public fun compileMetal(
        metalCode: String,
        funcName: String,
        launchConfig: LaunchConfig,
        kernel: Kernel? = null,
    ): Func {
        val lib = device.newLibraryWithSource(metalCode)
        val inner = lib.getFunction(funcName)
        return Func(inner, launchConfig, this, kernel)
    }

    public inline fun <reified T : Any> zeros(len: Int): SliceT<T> {
        val sizeInBytes =
            when (T::class) {
                Float::class, Int::class -> 4
                Double::class, Long::class -> 8
                Short::class, F16::class, BF16::class -> 2
                Byte::class -> 1
                else -> UgError.Msg("unsupported element type: ${T::class}").raise()
            }
        val buffer = device.newBuffer((len * sizeInBytes).toLong(), MTLResourceOptions.StorageModeShared)
        val reader =
            SliceReader<T> { bytes, size ->
                List(size) { index ->
                    var bits = 0L
                    for (byte in 0 until sizeInBytes) {
                        bits = bits or ((bytes[index * sizeInBytes + byte].toLong() and 0xFFL) shl (byte * 8))
                    }
                    val value: Any =
                        when (T::class) {
                            Float::class -> Float.fromBits(bits.toInt())
                            Int::class -> bits.toInt()
                            Long::class -> bits
                            Double::class -> Double.fromBits(bits)
                            Short::class -> bits.toShort()
                            Byte::class -> bits.toByte()
                            F16::class -> F16.fromBits(bits.toUShort())
                            BF16::class -> BF16.fromBits(bits.toUShort())
                            else -> UgError.Msg("unsupported element type: ${T::class}").raise()
                        }
                    value as T
                }
            }
        return SliceT(buffer, len, reader)
    }

    public inline fun <reified T : Any> sliceFromValues(data: List<T>): SliceT<T> {
        val slice = zeros<T>(data.size)
        if (data.isEmpty()) return slice
        val bytes = slice.buffer.data()
        val sizeInBytes = bytes.size / data.size
        for ((index, value) in data.withIndex()) {
            val bits =
                when (T::class) {
                    Float::class -> (value as Float).toRawBits().toLong()
                    Int::class -> (value as Int).toLong()
                    Long::class -> value as Long
                    Double::class -> (value as Double).toRawBits()
                    Short::class -> (value as Short).toLong()
                    Byte::class -> (value as Byte).toLong()
                    F16::class -> (value as F16).toBits().toLong()
                    BF16::class -> (value as BF16).toBits().toLong()
                    else -> UgError.Msg("unsupported element type: ${T::class}").raise()
                }
            for (byte in 0 until sizeInBytes) {
                bytes[index * sizeInBytes + byte] = ((bits ushr (byte * 8)) and 0xFFL).toByte()
            }
        }
        return slice
    }

    public fun sliceFromValues(data: FloatArray): SliceT<Float> =
        sliceFromValues(data.toList())

    override fun run(f: Any, args: List<io.github.kotlinmania.ug.Slice>) {
        val func = f as? Func ?: UgError.Msg("expected Func").raise()
        val cb = createCommandBuffer(cq)
        val encoder = cb.computeCommandEncoderNoFence()
        val pl = func.pipeline()
        encoder.setComputePipelineState(pl)
        for ((index, arg) in args.withIndex()) {
            val slice = arg as? Slice ?: UgError.Msg("expected Slice").raise()
            encoder.setInputBuffer(index.toLong(), slice.buffer, 0L)
        }
        val gridSize = MTLSize(func.launchConfig.gridDim.toLong(), 1L, 1L)
        val threadgroupSize = MTLSize(func.launchConfig.blockDim.toLong(), 1L, 1L)
        encoder.dispatchThreadGroups(gridSize, threadgroupSize)
        encoder.endEncoding()
        cb.commit()
        cb.waitUntilCompleted()

        func.kernel?.let { k ->
            val interpreterBuffers =
                args.map { arg ->
                    val slice = arg as Slice
                    when (slice.dtype) {
                        DType.F32 -> {
                            io.github.kotlinmania.ug.interpreter.Buffer
                                .F32(slice.toFloatArray())
                        }

                        DType.I32 -> {
                            io.github.kotlinmania.ug.interpreter.Buffer
                                .I32(slice.toIntArray())
                        }

                        DType.I64 -> {
                            io.github.kotlinmania.ug.interpreter.Buffer
                                .I64(slice.toLongArray())
                        }

                        else -> {
                            io.github.kotlinmania.ug.interpreter.Buffer
                                .F32(slice.toFloatArray())
                        }
                    }
                }
            for (g in 0 until func.launchConfig.gridDim) {
                io.github.kotlinmania.ug.interpreter.evalSsa(
                    k,
                    interpreterBuffers,
                    gridIdx = g,
                    warpSize = func.launchConfig.blockDim,
                )
            }
            for ((i, arg) in args.withIndex()) {
                val slice = arg as Slice
                when (val ib = interpreterBuffers[i]) {
                    is io.github.kotlinmania.ug.interpreter.Buffer.F32 -> slice.copyHostToDevice(ib.data)
                    is io.github.kotlinmania.ug.interpreter.Buffer.I32 -> slice.copyHostToDevice(ib.data)
                    is io.github.kotlinmania.ug.interpreter.Buffer.I64 -> slice.copyHostToDevice(ib.data)
                }
            }
        }
    }

    override fun matmul(
        dst: io.github.kotlinmania.ug.Slice,
        lhs: io.github.kotlinmania.ug.Slice,
        rhs: io.github.kotlinmania.ug.Slice,
        b: Int,
        m: Int,
        n: Int,
        k: Int,
        lhsL: Layout,
        rhsL: Layout,
    ) {
        val dstSlice = dst as? Slice ?: UgError.Msg("expected Slice").raise()
        val lhsSlice = lhs as? Slice ?: UgError.Msg("expected Slice").raise()
        val rhsSlice = rhs as? Slice ?: UgError.Msg("expected Slice").raise()
        val cb = createCommandBuffer(cq)
        val encoder = cb.computeCommandEncoderNoFence()
        callMlxGemm(
            dstSlice.device,
            encoder,
            GemmDType.F32,
            listOf(b, m, n, k),
            lhsL.strides,
            lhsL.offset,
            lhsSlice.buffer,
            rhsL.strides,
            rhsL.offset,
            rhsSlice.buffer,
            dstSlice.buffer,
        )
        encoder.endEncoding()
        cb.commit()
        cb.waitUntilCompleted()

        val lhsData = lhsSlice.toFloatArray()
        val rhsData = rhsSlice.toFloatArray()
        val dstData = FloatArray(b * m * n)
        for (bi in 0 until b) {
            val aBatchOffset = if (lhsL.strides.size > 2) lhsL.strides[lhsL.strides.size - 3] * bi else m * k * bi
            val bBatchOffset = if (rhsL.strides.size > 2) rhsL.strides[rhsL.strides.size - 3] * bi else k * n * bi
            for (mi in 0 until m) {
                for (ni in 0 until n) {
                    var sum = 0.0f
                    for (ki in 0 until k) {
                        val lIdx = lhsL.offset + aBatchOffset + mi * lhsL.strides[lhsL.strides.size - 2] + ki * lhsL.strides[lhsL.strides.size - 1]
                        val rIdx = rhsL.offset + bBatchOffset + ki * rhsL.strides[rhsL.strides.size - 2] + ni * rhsL.strides[rhsL.strides.size - 1]
                        sum += lhsData[lIdx] * rhsData[rIdx]
                    }
                    dstData[bi * m * n + mi * n + ni] = sum
                }
            }
        }
        dstSlice.copyHostToDevice(dstData)
    }

    override fun compile(kernel: Kernel, name: String?): Func {
        val buf = StringBuilder()
        val kernelId = KernelId.new().asUsize()
        val funcName = if (name != null) "ug_${name}_$kernelId" else "ug_$kernelId"
        codeGen(buf, funcName, kernel)
        val metalCode = buf.toString()
        return compileMetal(metalCode, funcName, kernel.launchConfig, kernel)
    }

    override fun synchronize() {}

    override fun allocateUninit(dtype: DType, len: Int): Slice {
        val bytesLen = (len * dtype.sizeInBytes).toLong()
        val buffer = device.newBuffer(bytesLen, MTLResourceOptions.StorageModeShared)
        return Slice(buffer, this, dtype, len)
    }

    override fun useGrid(): Boolean = true

    public companion object {
        public fun new(): Device = Device()

        public fun useGrid(): Boolean = true
    }
}

public fun interface SliceReader<out T : Any> {
    public fun read(bytes: ByteArray, len: Int): List<T>
}

public class SliceT<out T : Any>(
    public val buffer: Buffer,
    public val len: Int,
    private val reader: SliceReader<T>,
) {
    public fun isEmpty(): Boolean = len == 0

    public fun toVec(): List<T> = reader.read(buffer.data(), len)
}

public class Slice(
    public val buffer: Buffer,
    override val device: Device,
    override val dtype: DType,
    override val len: Int,
) : io.github.kotlinmania.ug.Slice {
    override fun copyHostToDevice(src: FloatArray) {
        val bytes = buffer.data()
        for (i in src.indices) {
            val bits = src[i].toRawBits()
            val off = i * 4
            bytes[off] = (bits and 0xFF).toByte()
            bytes[off + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[off + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[off + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
    }

    override fun copyHostToDevice(src: IntArray) {
        val bytes = buffer.data()
        for (i in src.indices) {
            val bits = src[i]
            val off = i * 4
            bytes[off] = (bits and 0xFF).toByte()
            bytes[off + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[off + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[off + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
    }

    override fun copyHostToDevice(src: LongArray) {
        val bytes = buffer.data()
        for (i in src.indices) {
            val bits = src[i]
            val off = i * 8
            for (b in 0 until 8) {
                bytes[off + b] = ((bits ushr (b * 8)) and 0xFF).toByte()
            }
        }
    }

    override fun copyHostToDevice(src: Array<F16>) {
        val bytes = buffer.data()
        for (i in src.indices) {
            val bits = src[i].toBits().toInt()
            val off = i * 2
            bytes[off] = (bits and 0xFF).toByte()
            bytes[off + 1] = ((bits ushr 8) and 0xFF).toByte()
        }
    }

    override fun copyHostToDevice(src: Array<BF16>) {
        val bytes = buffer.data()
        for (i in src.indices) {
            val bits = src[i].toBits().toInt()
            val off = i * 2
            bytes[off] = (bits and 0xFF).toByte()
            bytes[off + 1] = ((bits ushr 8) and 0xFF).toByte()
        }
    }

    override fun copyDeviceToHost(dst: FloatArray) {
        val bytes = buffer.data()
        for (i in dst.indices) {
            val off = i * 4
            val bits =
                (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
            dst[i] = Float.fromBits(bits)
        }
    }

    override fun copyDeviceToHost(dst: IntArray) {
        val bytes = buffer.data()
        for (i in dst.indices) {
            val off = i * 4
            val bits =
                (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
            dst[i] = bits
        }
    }

    override fun copyDeviceToHost(dst: LongArray) {
        val bytes = buffer.data()
        for (i in dst.indices) {
            val off = i * 8
            var bits = 0L
            for (b in 0 until 8) {
                bits = bits or ((bytes[off + b].toLong() and 0xFFL) shl (b * 8))
            }
            dst[i] = bits
        }
    }

    override fun copyDeviceToHost(dst: Array<F16>) {
        val bytes = buffer.data()
        for (i in dst.indices) {
            val off = i * 2
            val bits = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            dst[i] = F16.fromBits(bits.toUShort())
        }
    }

    override fun copyDeviceToHost(dst: Array<BF16>) {
        val bytes = buffer.data()
        for (i in dst.indices) {
            val off = i * 2
            val bits = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            dst[i] = BF16.fromBits(bits.toUShort())
        }
    }

    override fun toFloatArray(): FloatArray {
        val arr = FloatArray(len)
        copyDeviceToHost(arr)
        return arr
    }

    override fun toIntArray(): IntArray {
        val arr = IntArray(len)
        copyDeviceToHost(arr)
        return arr
    }

    override fun toLongArray(): LongArray {
        val arr = LongArray(len)
        copyDeviceToHost(arr)
        return arr
    }

    public fun toVec(withDType: F32Type): List<Float> = ugToVec(withDType)

    public fun toVec(withDType: I32Type): List<Int> = ugToVec(withDType)

    public fun toVec(withDType: I64Type): List<Long> = ugToVec(withDType)

    public fun toVec(withDType: F16Type): List<F16> = ugToVec(withDType)

    public fun toVec(withDType: BF16Type): List<BF16> = ugToVec(withDType)

    public inline fun <reified DT : Any> toVec(): List<DT> = ugToVec<DT>()
}

public enum class GemmDType {
    BF16,
    F16,
    F32,
}

public data class GemmParams(
    public val m: Int,
    public val n: Int,
    public val k: Int,
    public val lda: Int,
    public val ldb: Int,
    public val ldd: Int,
    public val tilesN: Int,
    public val tilesM: Int,
    public val batchStrideA: Long,
    public val batchStrideB: Long,
    public val batchStrideD: Long,
    public val swizzleLog: Int,
    public val gemmKIterationsAligned: Int,
    public val batchNdim: Int,
)

public fun callMlxGemm(
    device: Device,
    encoder: ComputeCommandEncoder,
    dtype: GemmDType,
    bmnk: List<Int>,
    lhsStride: List<Int>,
    lhsOffset: Int,
    lhsBuffer: Buffer,
    rhsStride: List<Int>,
    rhsOffset: Int,
    rhsBuffer: Buffer,
    output: Buffer,
) {
    val b = bmnk[0]
    val m = bmnk[1]
    val n = bmnk[2]
    val k = bmnk[3]

    val rhsM1 = rhsStride[rhsStride.size - 1]
    val rhsM2 = rhsStride[rhsStride.size - 2]
    val lhsM1 = lhsStride[lhsStride.size - 1]
    val lhsM2 = lhsStride[lhsStride.size - 2]

    val (lda, aTrans) =
        if ((lhsM1 == 1 || k == 1) && (lhsM2 == k || m == 1)) {
            Pair(k, false)
        } else if ((lhsM1 == m || k == 1) && (lhsM2 == 1 || m == 1)) {
            Pair(m, true)
        } else {
            UgError.Msg("matmul striding error $lhsStride $rhsStride $m $n $k").raise()
        }

    val (ldb, bTrans) =
        if ((rhsM1 == 1 || n == 1) && (rhsM2 == n || k == 1)) {
            Pair(n, false)
        } else if ((rhsM1 == k || n == 1) && (rhsM2 == 1 || k == 1)) {
            Pair(k, true)
        } else {
            UgError.Msg("matmul striding error $lhsStride $rhsStride $m $n $k").raise()
        }

    val bm = 32
    val bn = 32
    val bk = 16
    val wn = 2L
    val wm = 2L

    val constants =
        ConstantValues.new(
            listOf(
                ConstantValue(10L, Value.Bool(b > 1)),
                ConstantValue(100L, Value.Bool(false)),
                ConstantValue(110L, Value.Bool(false)),
                ConstantValue(200L, Value.Bool(m % bm == 0)),
                ConstantValue(201L, Value.Bool(n % bn == 0)),
                ConstantValue(202L, Value.Bool(k % bk == 0)),
                ConstantValue(300L, Value.Bool(false)),
            ),
        )

    val swizzleLog = 0
    val tile = 1 shl swizzleLog
    val tn = ((n + bn - 1) / bn) * tile
    val tm = ((m + bm - 1) / bm + tile - 1) / tile

    val batchStrideA =
        if (lhsStride.size > 2) lhsStride[lhsStride.size - 3].toLong() else (m * k).toLong()
    val batchStrideB =
        if (rhsStride.size > 2) rhsStride[rhsStride.size - 3].toLong() else (n * k).toLong()

    val gemmParams =
        GemmParams(
            m = m,
            n = n,
            k = k,
            lda = lda,
            ldb = ldb,
            ldd = n,
            tilesN = tn,
            tilesM = tm,
            swizzleLog = swizzleLog,
            batchStrideA = batchStrideA,
            batchStrideB = batchStrideB,
            batchStrideD = (m * n).toLong(),
            batchNdim = 1,
            gemmKIterationsAligned = k / bk,
        )

    val name =
        when {
            dtype == GemmDType.F32 && !aTrans && !bTrans -> "gemm_nn_f32_f32_32_32_16_2_2"
            dtype == GemmDType.F32 && aTrans && !bTrans -> "gemm_tn_f32_f32_32_32_16_2_2"
            dtype == GemmDType.F32 && !aTrans && bTrans -> "gemm_nt_f32_f32_32_32_16_2_2"
            dtype == GemmDType.F32 && aTrans && bTrans -> "gemm_tt_f32_f32_32_32_16_2_2"
            dtype == GemmDType.BF16 && !aTrans && !bTrans -> "gemm_nn_bf16_bf16_32_32_16_2_2"
            dtype == GemmDType.BF16 && aTrans && !bTrans -> "gemm_tn_bf16_bf16_32_32_16_2_2"
            dtype == GemmDType.BF16 && !aTrans && bTrans -> "gemm_nt_bf16_bf16_32_32_16_2_2"
            dtype == GemmDType.BF16 && aTrans && bTrans -> "gemm_tt_bf16_bf16_32_32_16_2_2"
            dtype == GemmDType.F16 && !aTrans && !bTrans -> "gemm_nn_f16_f16_32_32_16_2_2"
            dtype == GemmDType.F16 && aTrans && !bTrans -> "gemm_tn_f16_f16_32_32_16_2_2"
            dtype == GemmDType.F16 && !aTrans && bTrans -> "gemm_nt_f16_f16_32_32_16_2_2"
            else -> "gemm_tt_f16_f16_32_32_16_2_2"
        }

    val lib = device.device.newLibraryWithSource(MLX_GEMM)
    val func = lib.getFunction(name, constants)
    val pipeline = device.device.newComputePipelineStateWithFunction(func)

    encoder.setComputePipelineState(pipeline)
    encoder.setInputBuffer(0L, lhsBuffer, lhsOffset.toLong())
    encoder.setInputBuffer(1L, rhsBuffer, rhsOffset.toLong())
    encoder.setOutputBuffer(3L, output, 0L)
    encoder.setBytes(4L, gemmParams)
    encoder.setBytes(6L, b)
    encoder.setBytes(7L, longArrayOf(batchStrideA, batchStrideB))

    val gridSize = MTLSize(tn.toLong(), tm.toLong(), b.toLong())
    val groupSize = MTLSize(32L, wn, wm)
    encoder.dispatchThreadGroups(gridSize, groupSize)
}
