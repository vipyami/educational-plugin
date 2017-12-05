package com.jetbrains.edu.android

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.jetbrains.edu.learning.EduConfigurator
import com.jetbrains.edu.learning.EduCourseBuilder
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.checker.TaskChecker
import com.jetbrains.edu.learning.courseFormat.tasks.EduTask
import com.jetbrains.edu.learning.intellij.JdkProjectSettings

class AndroidConfigurator : EduConfigurator<JdkProjectSettings> {

  private val myCourseBuilder: AndroidCourseBuilder = AndroidCourseBuilder()

  override fun getCourseBuilder(): EduCourseBuilder<JdkProjectSettings> = myCourseBuilder

  override fun getTestFileName(): String {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun excludeFromArchive(name: String): Boolean {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun getEduTaskChecker(task: EduTask, project: Project): TaskChecker<EduTask> = AndroidChecker(task, project)

  override fun getBundledCoursePaths(): List<String> {
    val bundledCourseRoot = EduUtils.getBundledCourseRoot(DEFAULT_COURSE_PATH, AndroidConfigurator::class.java)
    return listOf(FileUtil.join(bundledCourseRoot.absolutePath, DEFAULT_COURSE_PATH))
  }

  companion object {
    private const val DEFAULT_COURSE_PATH = "NewAndroidCourse.zip"
  }
}
