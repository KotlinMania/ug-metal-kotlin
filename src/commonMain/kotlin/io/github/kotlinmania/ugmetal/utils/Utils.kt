// port-lint: source src/utils.rs
package io.github.kotlinmania.ugmetal.utils

import io.github.kotlinmania.candlemetalkernels.metal.Buffer
import io.github.kotlinmania.candlemetalkernels.metal.ComputeCommandEncoder
import io.github.kotlinmania.ugmetal.runtime.Slice

public fun <P : EncoderParam> setParam(encoder: ComputeCommandEncoder, position: Long, data: P) {
    data.setParam(encoder, position)
}

public fun setParam(encoder: ComputeCommandEncoder, position: Long, data: Any?) {
    when (data) {
        is EncoderParam -> {
            data.setParam(encoder, position)
        }

        is Buffer -> {
            encoder.setInputBuffer(position, data, 0L)
        }

        is Slice -> {
            encoder.setInputBuffer(position, data.buffer, 0L)
        }

        is Pair<*, *> -> {
            val buf = data.first
            val offset = (data.second as? Number)?.toLong() ?: 0L
            when (buf) {
                is Buffer -> encoder.setInputBuffer(position, buf, offset)
                is Slice -> encoder.setInputBuffer(position, buf.buffer, offset)
                else -> encoder.setBytes(position, data)
            }
        }

        else -> {
            encoder.setBytes(position, data)
        }
    }
}

/**
 * Helper functions to create the various objects on the compute command encoder
 * on a single line.
 * Prevents getting wrong some arguments number and mixing length and size in bytes.
 */
public interface EncoderParam {
    public fun setParam(encoder: ComputeCommandEncoder, position: Long)
}

public fun setParams(encoder: ComputeCommandEncoder, vararg params: Any?) {
    for (i in params.indices) {
        setParam(encoder, i.toLong(), params[i])
    }
}
