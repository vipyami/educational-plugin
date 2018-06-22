package com.jetbrains.edu.learning.stepik.alt

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.edu.learning.stepik.StepicUser
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.io.IOException

object HyperskillConnector {
  private val LOG = Logger.getInstance(HyperskillConnector::class.java.name)
  private const val STEPIK_ALT_URL = "https://hyperskill.org/api/"

  private val service: HyperskillService
    get() {
//      val okHttpClient = OkHttpClient.Builder()
//        .readTimeout(60, TimeUnit.SECONDS)
//        .connectTimeout(60, TimeUnit.SECONDS)
//        .build()

      val retrofit = Retrofit.Builder()
        .baseUrl(STEPIK_ALT_URL)
        .addConverterFactory(JacksonConverterFactory.create())
//        .client(okHttpClient)
        .build()

      return retrofit.create(HyperskillService::class.java)
    }

  fun login(user: StepicUser) {
    try {
      val loginBody = LoginBody(user)
      val response = service.login(loginBody).execute()
      val tokenInfo = response.body()
      if (tokenInfo != null) {
        user.setHyperskillTokenInfo(tokenInfo)
      }
      if (response.errorBody() != null) {
        LOG.warn("Failed to login to hyperskill.org. " + response.errorBody()!!.string())
      }
    }
    catch (e: IOException) {
      LOG.warn("Failed to login to hyperskill.org. " + e.message)
    }

  }
}
