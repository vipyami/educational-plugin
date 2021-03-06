package com.jetbrains.edu.kotlin

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.util.io.FileUtil
import com.jetbrains.edu.kotlin.checker.KtTaskCheckerProvider
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.gradle.GradleConfiguratorBase

open class KtConfigurator : GradleConfiguratorBase() {

  private val myCourseBuilder = KtCourseBuilder()

  override fun getCourseBuilder() = myCourseBuilder

  override fun getTestFileName(): String = TESTS_KT

  override fun getBundledCoursePaths(): List<String> {
    val bundledCourseRoot = EduUtils.getBundledCourseRoot(DEFAULT_COURSE_NAME, KtConfigurator::class.java)
    return listOf(FileUtil.join(bundledCourseRoot.absolutePath, DEFAULT_COURSE_NAME))
  }

  override fun getTaskCheckerProvider() = KtTaskCheckerProvider()

  override fun getMockTemplate(): String {
    return FileTemplateManager.getDefaultInstance().getInternalTemplate(MOCK_KT).text
  }

  companion object {
    const val DEFAULT_COURSE_NAME = "Kotlin Koans.zip"

    const val TESTS_KT = "Tests.kt"
    const val TASK_KT = "Task.kt"
    const val MOCK_KT = "Mock.kt"
  }
}
