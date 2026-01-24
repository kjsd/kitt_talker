package org.kandj.kitt_talker

import com.google.gson.annotations.SerializedName

data class KittContent(
    val id: Long,
    val action: String,
    val message: String?,
    val mood: String?,
    @SerializedName("audio_path")
    val audioPath: String?
)

data class Kitt(
    val id: String,
    val name: String,
    val lang: String?,
    val timezone: String?,
    val biography: KittBiography?
)

data class KittBiography(
    val model: String?,
    val vendor: String?,
    val personality: String?
)
