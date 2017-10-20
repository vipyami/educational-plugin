package com.jetbrains.edu.android

import com.intellij.ProjectTopics
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.jetbrains.edu.kotlin.EduKotlinPluginConfigurator
import com.jetbrains.edu.learning.StudyUtils
import com.jetbrains.edu.learning.checker.StudyTaskChecker
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.tasks.PyCharmTask
import com.jetbrains.edu.learning.courseGeneration.StudyGenerator
import com.jetbrains.edu.learning.intellij.JdkProjectSettings
import com.jetbrains.edu.learning.newproject.EduCourseProjectGenerator
import icons.AndroidIcons
import org.jetbrains.android.sdk.AndroidSdkType
import java.io.File
import java.io.IOException
import javax.swing.Icon

class EduKotlinAndroidPluginConfigurator : EduKotlinPluginConfigurator() {

  override fun getPyCharmTaskChecker(pyCharmTask: PyCharmTask, project: Project): StudyTaskChecker<PyCharmTask> =
          EduAndroidChecker(pyCharmTask, project)

  override fun getBundledCoursePaths(): List<String> {
    val bundledCourseRoot = StudyUtils.getBundledCourseRoot(DEFAULT_COURSE_PATH, EduKotlinAndroidPluginConfigurator::class.java)
    return listOf(FileUtil.join(bundledCourseRoot.absolutePath, DEFAULT_COURSE_PATH))
  }

  override fun createCourseModuleContent(moduleModel: ModifiableModuleModel,
                                         project: Project,
                                         course: Course,
                                         moduleDir: String?) {
    val lessons = course.lessons
    val task = lessons[0].getTaskList()[0]

    if (moduleDir == null) {
      LOG.error("Can't find module dir ")
      return
    }

    val dir = VfsUtil.findFileByIoFile(File(moduleDir), true)
    if (dir == null) {
      LOG.error("Can't find module dir on file system " + moduleDir)
      return
    }

    ApplicationManager.getApplication().invokeLater {
      ApplicationManager.getApplication().runWriteAction {
        try {
          StudyGenerator.createTaskContent(task, dir)
          val connect = project.messageBus.connect()
          connect.subscribe(ProjectTopics.MODULES, object : ModuleListener {
            override fun moduleAdded(project: Project, module: Module) {
              ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                  val androidSdks = ProjectJdkTable.getInstance().getSdksOfType(AndroidSdkType.getInstance())
                  if (!androidSdks.isEmpty()) {
                    val androidSdk = androidSdks[0]
                    val modifiableModel = ModuleRootManager.getInstance(module).modifiableModel
                    modifiableModel.sdk = androidSdk
                    modifiableModel.commit()
                    module.project.save()
                  }
                }
              }
            }
          })

        } catch (e: IOException) {
          LOG.error(e)
        }
      }
    }
  }

  override fun getEduCourseProjectGenerator(course: Course): EduCourseProjectGenerator<JdkProjectSettings>? =
          EduKotlinAndroidCourseProjectGenerator(course)

  override fun getLogo(): Icon? = AndroidIcons.Android

  override fun isEnabled(): Boolean = true

  companion object {
    private val DEFAULT_COURSE_PATH = "AndroidCourse.zip"
    private val LOG = Logger.getInstance(EduKotlinAndroidPluginConfigurator::class.java)
  }
}
