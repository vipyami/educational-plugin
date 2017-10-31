package com.jetbrains.edu.android

import com.android.tools.idea.gradle.util.Projects.getBaseDirPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbModePermission
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.EduPluginConfiguratorManager
import com.jetbrains.edu.learning.StudyUtils
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.intellij.JdkProjectSettings
import com.jetbrains.edu.learning.intellij.generation.EduGradleModuleGenerator
import com.jetbrains.edu.learning.intellij.generation.EduProjectGenerator
import com.jetbrains.edu.learning.newproject.EduCourseProjectGenerator
import java.io.File

internal class EduAndroidCourseProjectGenerator(private val myCourse: Course) : EduCourseProjectGenerator<JdkProjectSettings> {

  override fun generateProject(project: Project, baseDir: VirtualFile, settings: JdkProjectSettings, module: Module) {
    val generator = EduProjectGenerator()
    generator.selectedCourse = myCourse
    generator.generateProject(project, project.baseDir)
    myCourse.courseType = "Tutorial"

    val language = myCourse.languageById
    val configurator = EduPluginConfiguratorManager.forLanguage(language)
    if (configurator == null) {
      LOG.warn("Configurator for $language is null")
      return
    }
    configurator.createCourseModuleContent(ModuleManager.getInstance(project).modifiableModel,
                    project, myCourse, project.basePath)
    ApplicationManager.getApplication().invokeLater {
      DumbService.allowStartingDumbModeInside(DumbModePermission.MAY_START_BACKGROUND) {
        ApplicationManager.getApplication().runWriteAction {
          StudyUtils.registerStudyToolWindow(myCourse, project)
        }
      }
    }
  }

  override fun afterProjectGenerated(project: Project, settings: JdkProjectSettings) {
    val projectPath = getBaseDirPath(project)
    EduGradleModuleGenerator.createGradleWrapper(projectPath.absolutePath)

    val gradlew = File(projectPath, "gradlew")
    if (gradlew.exists() && !gradlew.canExecute()) {
      if (!gradlew.setExecutable(true)) {
        LOG.warn("Unable to make gradlew executable")
      }
    }
  }

  companion object {
    private val LOG = Logger.getInstance(EduAndroidCourseProjectGenerator::class.java)
  }
}
