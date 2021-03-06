package com.jetbrains.edu.learning.handlers

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.jetbrains.edu.coursecreator.CCUtils
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSynchronizer
import com.jetbrains.edu.learning.EduNames
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.Section
import com.jetbrains.edu.learning.courseFormat.ext.configurator
import com.jetbrains.edu.learning.courseFormat.ext.findSourceDir
import com.jetbrains.edu.learning.courseFormat.ext.findTestDir
import com.jetbrains.edu.learning.courseFormat.tasks.Task

abstract class EduVirtualFileListener(protected val project: Project) : VirtualFileListener {

  override fun fileCreated(event: VirtualFileEvent) {
    if (event.file.isDirectory) return
    val fileInfo = event.fileInfo(project) as? FileInfo.FileInTask ?: return
    fileInTaskCreated(event, fileInfo)
  }

  /**
   * Actual text of files is not loaded intentionally
   * because it is required only in some places where it is really needed:
   * course archive creation, loading to Stepik, etc.
   * Such actions load necessary text of files themselves.
   *
   * Also info about new file won't be added if the file is already in the task.
   * Generally, such checks are required because of tests.
   * In real life, project files are created before project opening and virtual file listener initialization,
   * so such situation shouldn't happen.
   * But in tests, course files usually are created by [EduTestCase.courseWithFiles] which triggers virtual file listener because
   * sometimes listener is initialized in `[TestCase.setUp] method and [EduTestCase.courseWithFiles] creates course files after it.
   * In such cases, these checks prevent replacing correct task file
   * with empty (without placeholders, hints, etc.) one.
   */
  protected open fun fileInTaskCreated(event: VirtualFileEvent, fileInfo: FileInfo.FileInTask) {
    val (task, pathInTask, kind) = fileInfo
    when (kind) {
      FileKind.TASK_FILE -> {
        if (task.getTaskFile(pathInTask) == null) {
          val taskFile = task.addTaskFile(pathInTask)
          if (EduUtils.isStudentProject(project)) {
            taskFile.isUserCreated = true
          }
        }
      }
      FileKind.TEST_FILE -> {
        if (pathInTask !in task.testsText) {
          task.addTestsTexts(pathInTask, "")
        }
      }
      FileKind.ADDITIONAL_FILE -> {
        if (pathInTask !in task.additionalFiles) {
          task.addAdditionalFile(pathInTask, "")
        }
      }
    }
  }

  protected fun VirtualFileEvent.fileInfo(project: Project): FileInfo? {
    if (project.isDisposed) return null
    val course = StudyTaskManager.getInstance(project).course ?: return null
    if (shouldIgnore(file, project)) return null

    if (file.isDirectory) {
      EduUtils.getSection(file, course)?.let { return FileInfo.SectionDirectory(it) }
      EduUtils.getLesson(file, course)?.let { return FileInfo.LessonDirectory(it) }
      EduUtils.getTask(file, course)?.let { return FileInfo.TaskDirectory(it) }
    }

    val task = EduUtils.getTaskForFile(project, file) ?: return null
    val taskDir = task.getTaskDir(project) ?: return null
    val testDir = task.findTestDir(taskDir) ?: taskDir

    val taskRelativePath = EduUtils.pathRelativeToTask(project, file)

    if (EduUtils.isTaskDescriptionFile(file.name)
        || taskRelativePath.contains(EduNames.WINDOW_POSTFIX)
        || taskRelativePath.contains(EduNames.WINDOWS_POSTFIX)
        || taskRelativePath.contains(EduNames.ANSWERS_POSTFIX)) {
      return null
    }

    // We consider that directory has `FileKind.TEST_FILE` kind if it's child of `testDir` (if it exists).
    // So single `EduUtils.isTestsFile` check is not enough because it doesn't work with directories at all
    if (EduUtils.isTestsFile(project, file) || taskDir != testDir && VfsUtilCore.isAncestor(testDir, file, true)) {
      return FileInfo.FileInTask(task, taskRelativePath, FileKind.TEST_FILE)
    }
    val sourceDir = task.findSourceDir(taskDir)
    if (sourceDir != null) {
      if (VfsUtilCore.isAncestor(sourceDir, file, true)) return FileInfo.FileInTask(task, taskRelativePath, FileKind.TASK_FILE)
    }
    return FileInfo.FileInTask(task, taskRelativePath, FileKind.ADDITIONAL_FILE)
  }

  private fun shouldIgnore(file: VirtualFile, project: Project): Boolean {
    if (file.path.contains(CCUtils.GENERATED_FILES_FOLDER) ||
        file.path.contains(CCUtils.DS_STORE)) return true
    if (YamlFormatSynchronizer.isConfigFile(file)) return true
    val courseDir = EduUtils.getCourseDir(project)
    if (!FileUtil.isAncestor(courseDir.path, file.path, true)) return true
    val course = StudyTaskManager.getInstance(project).course ?: return true
    if (course.configurator?.excludeFromArchive(project, file.path) == true) return true
    return false
  }

  protected sealed class FileInfo {
    data class SectionDirectory(val section: Section) : FileInfo()
    data class LessonDirectory(val lesson: Lesson) : FileInfo()
    data class TaskDirectory(val task: Task) : FileInfo()
    data class FileInTask(val task: Task, val pathInTask: String, val kind: FileKind) : FileInfo()
  }

  protected enum class FileKind {
    TASK_FILE,
    TEST_FILE,
    ADDITIONAL_FILE
  }
}
