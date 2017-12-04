package com.jetbrains.edu.android

import com.intellij.openapi.components.ApplicationComponent

class AndroidApplicationComponent : ApplicationComponent {
  override fun initComponent() {
    // Load 'Android' language class
    Android
  }

  override fun disposeComponent() {}

  override fun getComponentName(): String = "EduAndroid"
}
