package com.jetbrains.edu.android

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils.createChildFile
import com.jetbrains.edu.learning.intellij.JdkProjectSettings
import com.jetbrains.edu.learning.intellij.generation.EduGradleModuleGenerator
import com.jetbrains.edu.learning.newproject.CourseProjectGenerator

class AndroidCourseProjectGenerator(course: Course) : CourseProjectGenerator<JdkProjectSettings>(course) {

  override fun generateProject(project: Project, baseDir: VirtualFile, settings: JdkProjectSettings?, module: Module) {
    runWriteAction {
      GeneratorUtils.initializeCourse(project, myCourse)
      GeneratorUtils.createCourse(myCourse, baseDir)
      EduGradleModuleGenerator.createGradleWrapper(baseDir.path)
      val template = FileTemplateManager.getInstance(project).getInternalTemplate("android-settings.gradle")
      createChildFile(baseDir, "settings.gradle", template.text.replace("\$PROJECT_NAME\$", myCourse.name))
    }
  }
}
