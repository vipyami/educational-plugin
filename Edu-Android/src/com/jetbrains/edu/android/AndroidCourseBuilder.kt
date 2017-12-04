package com.jetbrains.edu.android

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.EduCourseBuilder
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.intellij.JdkLanguageSettings
import com.jetbrains.edu.learning.intellij.JdkProjectSettings
import com.jetbrains.edu.learning.newproject.CourseProjectGenerator

class AndroidCourseBuilder :  EduCourseBuilder<JdkProjectSettings> {

  override fun getCourseProjectGenerator(course: Course): CourseProjectGenerator<JdkProjectSettings>? =
          AndroidCourseProjectGenerator(course)

  override fun createTaskContent(project: Project, task: Task, parentDirectory: VirtualFile, course: Course): VirtualFile {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun getLanguageSettings(): EduCourseBuilder.LanguageSettings<JdkProjectSettings> = JdkLanguageSettings()
}
