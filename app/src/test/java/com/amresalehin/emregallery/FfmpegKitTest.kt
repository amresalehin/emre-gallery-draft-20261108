package com.amresalehin.emregallery

import com.amresalehin.emregallery.ffmpeg.FfmpegExecutionResult
import com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine
import com.amresalehin.emregallery.ffmpeg.FfprobeMediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class FfmpegKitTest {

    @Test
    fun testFfmpegEngineStatus() {
        val status = FfmpegKitEngine.getStatus()
        assertNotNull("Status must not be null", status)
        assertTrue("Package name must reference ffmpeg-kit", status.packageName.contains("ffmpeg-kit"))
        assertTrue("Codecs summary must include H.264", status.supportedCodecsSummary.contains("H.264"))
        assertTrue("Codecs summary must include MP3", status.supportedCodecsSummary.contains("MP3"))
        assertTrue("Codecs summary must include AAC", status.supportedCodecsSummary.contains("AAC"))
    }

    @Test
    fun testFfmpegExecutionResultModel() {
        val res = FfmpegExecutionResult(
            isSuccess = true,
            returnCode = 0,
            outputLogs = "ffmpeg version 8.1.9 Copyright (c) 2000-2025 the FFmpeg developers",
            executionTimeMs = 45L
        )
        assertTrue(res.isSuccess)
        assertEquals(0, res.returnCode)
        assertEquals(45L, res.executionTimeMs)
        assertTrue(res.outputLogs.contains("ffmpeg version"))
    }

    @Test
    fun testFfprobeMediaInfoModel() {
        val info = FfprobeMediaInfo(
            format = "mp4",
            durationSeconds = 12.5,
            sizeBytes = 2048000L,
            bitrateKbps = 1310L,
            videoCodec = "H264",
            resolution = "1920x1080",
            frameRate = 30.0,
            audioCodec = "AAC",
            audioChannels = 2,
            sampleRateHz = 48000,
            streamCount = 2,
            rawMetadata = mapOf("encoder" to "Lavf61.1.100")
        )
        assertEquals("mp4", info.format)
        assertEquals("H264", info.videoCodec)
        assertEquals("1920x1080", info.resolution)
        assertEquals("AAC", info.audioCodec)
        assertEquals(2, info.audioChannels)
        assertEquals(48000, info.sampleRateHz)
        assertEquals("Lavf61.1.100", info.rawMetadata["encoder"])
    }
}
