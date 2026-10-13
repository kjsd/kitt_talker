package org.kandj.kitt_talker

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface KittApiService {
    @GET("kitts")
    fun getKitts(): Call<List<Kitt>>

    @POST("kitts/{id}/talk")
    fun talk(@Path("id") id: String, @Query("text") text: String): Call<KittContent>

    @Multipart
    @POST("kitts/{id}/talk_audio")
    fun talkAudio(
        @Path("id") id: String,
        @Part audio: MultipartBody.Part
    ): Call<KittAudioResponse>

    @GET("kitts/{id}/talks")
    fun dequeueTalk(@Path("id") id: String): Call<KittContent>
}
