package com.jetbrains.edu.learning.intellij

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.jetbrains.edu.learning.EduCourseBuilder
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.intellij.generation.EduGradleUtils
import com.jetbrains.edu.learning.intellij.generation.GradleCourseProjectGenerator
import com.jetbrains.edu.learning.projectView.CourseViewPane
import org.jetbrains.plugins.gradle.util.GradleConstants

abstract class GradleCourseBuilderBase : EduCourseBuilder<JdkProjectSettings>, ExternalProjectRefreshCallback {

  abstract val buildGradleTemplateName: String

  open val buildGradleVariables: Map<String, String> = mapOf("GRADLE_VERSION" to EduGradleUtils.gradleVersion())

  override fun refreshProject(project: Project) {
    ExternalSystemUtil.refreshProjects(
      ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
        .forceWhenUptodate(true)
        .use(ProgressExecutionMode.MODAL_SYNC)
        .callback(this)
    )
    if (!ApplicationManager.getApplication().isUnitTestMode) {
      ExternalSystemUtil.invokeLater(project, ModalityState.NON_MODAL) {
        ProjectView.getInstance(project).changeViewCB(CourseViewPane.ID, null)
      }
    }
  }

  override fun getLanguageSettings(): EduCourseBuilder.LanguageSettings<JdkProjectSettings> = JdkLanguageSettings()

  override fun getCourseProjectGenerator(course: Course): GradleCourseProjectGenerator =
    GradleCourseProjectGenerator(this, course)

  override fun onFailure(errorMessage: String, errorDetails: String?) {
    LOG.error(errorMessage)
    if (errorDetails != null) {
      LOG.error(errorDetails)
    }
  }

  companion object {
    private val LOG = Logger.getInstance(GradleCourseBuilderBase::class.java)
  }
}
