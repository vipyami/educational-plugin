package com.jetbrains.edu.learning.stepik.alt

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.jetbrains.edu.learning.stepik.StepicUser
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.*

@Suppress("unused")
interface HyperskillService {

  @POST("login/")
  fun login(@Body body: LoginBody): Call<TokenInfo>

  @GET("recommendations/")
  fun recommendations(): Call<Recommendation>

}

class LoginBody(user: StepicUser) {
  @JsonProperty("stepik_id")
  var stepikId: Int = 0

  @JsonProperty("access_token")
  var accessToken: String

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
  @JsonProperty("expires_in")
  var expiresIn: Date

  @JsonProperty("refresh_token")
  var refreshToken: String

  @JsonProperty("client_id")
  var clientId = "jcboczaSZYHmmCewusCNrE172yHkOONV7JY1ECh4"

  init {
    stepikId = user.id
    accessToken = user.accessToken
    refreshToken = user.refreshToken
    expiresIn = Date(user.expiresIn)//"2018-10-11T10:00"
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class TokenInfo {
  @JsonProperty("access_token")
  var accessToken: String? = null
  @JsonProperty("refresh_token")
  var refreshToken: String? = null
}


@JsonIgnoreProperties(ignoreUnknown = true)
class Recommendation {
  var lesson: Lesson? = null
}

class Lesson {
  @JsonProperty("stepik_id")
  var stepikId: Int = 0
}
