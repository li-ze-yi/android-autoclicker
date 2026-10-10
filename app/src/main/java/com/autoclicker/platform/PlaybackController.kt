package com.autoclicker.platform

import com.autoclicker.core.bus.PlaybackState
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.flow.StateFlow

/** 脚本播放控制（引擎实现）。 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** 开始播放脚本；若已在播放则先停止。 */
    suspend fun play(script: Script)

    fun pause()

    fun resume()

    fun stop()
}