package com.jetbrains.edu.android

import com.jetbrains.edu.learning.EduCourseBuilder
import com.jetbrains.edu.learning.checker.TaskCheckerProvider
import com.jetbrains.edu.learning.intellij.GradleConfiguratorBase
import com.jetbrains.edu.learning.intellij.JdkProjectSettings

class AndroidConfigurator : GradleConfiguratorBase() {

  private val courseBuilder: AndroidCourseBuilder = AndroidCourseBuilder()
  private val taskCheckerProvider: AndroidTaskCheckerProvider = AndroidTaskCheckerProvider()

  override fun getCourseBuilder(): EduCourseBuilder<JdkProjectSettings> = courseBuilder

  override fun getSourceDir(): String = "src/main"
  override fun getTestDir(): String = "src/test"

  // TODO: do something with this method
  override fun getTestFileName(): String = "ExampleUnitTest.kt"

  override fun getTaskCheckerProvider(): TaskCheckerProvider = taskCheckerProvider
}
