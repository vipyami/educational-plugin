package com.jetbrains.edu.learning

import com.intellij.util.PlatformUtils
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.newproject.EduCourseProjectGenerator
import com.jetbrains.python.newProject.PyNewProjectSettings

class PyCharmPyEduPluginConfigurator : PyEduPluginConfigurator() {

  override fun getLanguageSettings(): EduPluginConfigurator.LanguageSettings<PyNewProjectSettings> =
          PyCharmPyLanguageSettings()

  override fun getEduCourseProjectGenerator(course: Course): EduCourseProjectGenerator<PyNewProjectSettings>? =
          PyCharmPyDirectoryProjectGenerator(course)

  override fun isEnabled(): Boolean = PlatformUtils.isPyCharm() || PlatformUtils.isCLion()
}
