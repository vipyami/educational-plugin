package com.jetbrains.edu.learning.checkio.account;

import com.google.gson.annotations.JsonAdapter;
import com.intellij.util.xmlb.annotations.Tag;
import com.jetbrains.edu.learning.checkio.api.adapters.CheckiOTokensDeserializer;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@JsonAdapter(CheckiOTokensDeserializer.class)
public class CheckiOTokens {
  @NotNull
  @Tag("AccessToken")
  private String myAccessToken;

  @NotNull
  @Tag("RefreshToken")
  private String myRefreshToken;

  @Tag("ExpiringTime")
  private long myExpiringTime;

  @SuppressWarnings("unused") // used for deserialization
  private CheckiOTokens() {
    myAccessToken = "";
    myRefreshToken = "";
    myExpiringTime = -1;
  }

  public CheckiOTokens(@NotNull String accessToken, @NotNull String refreshToken, long expiringTime) {
    myAccessToken = accessToken;
    myRefreshToken = refreshToken;
    myExpiringTime = expiringTime;
  }

  @NotNull
  public String getAccessToken() {
    return myAccessToken;
  }

  @NotNull
  public String getRefreshToken() {
    return myRefreshToken;
  }

  public boolean isUpToDate() {
    return currentTimeSeconds() < myExpiringTime - 600; // subtract 10 minutes for avoiding boundary case
  }

  private static long currentTimeSeconds() {
    return System.currentTimeMillis() / 1000;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CheckiOTokens tokens = (CheckiOTokens)o;
    return Objects.equals(myAccessToken, tokens.myAccessToken) &&
           Objects.equals(myRefreshToken, tokens.myRefreshToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myAccessToken, myRefreshToken);
  }
}
