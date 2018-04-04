package com.jetbrains.edu.learning.stepik

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.EduSettings
import com.jetbrains.edu.learning.EduUtils.synchronize
import com.jetbrains.edu.learning.courseFormat.CheckStatus
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.RemoteCourse
import com.jetbrains.edu.learning.courseFormat.tasks.ChoiceTask
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseFormat.tasks.TheoryTask
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils
import com.jetbrains.edu.learning.stepik.StepikConnector.getCourse
import com.jetbrains.edu.learning.stepik.StepikConnector.getCourseFromStepik
import java.io.IOException
import java.net.URISyntaxException
import java.util.*
import kotlin.collections.ArrayList

class StepikCourseUpdater(private val course: RemoteCourse, private val project: Project) {
  private val LOG = Logger.getInstance(this.javaClass)
  private var updatedTasksNumber: Int = 0

  private val oldLessonDirectories = HashMap<Int, VirtualFile>()

  fun updateCourse() {
    oldLessonDirectories.clear()
    val courseFromServer = courseFromServer(project, course)
    if (courseFromServer == null) {
      LOG.warn("Course ${course.id} not found on Stepik")
      return
    }

    courseFromServer.lessons.withIndex().forEach({ (index, lesson) -> lesson.index = index + 1 })

    val newLessons = courseFromServer.lessons.filter { lesson -> course.getLesson(lesson.id) == null }
    if (!newLessons.isEmpty()) {
      createNewLessons(project, newLessons)
    }
    val updateLessonsNumber = updateLessons(courseFromServer)

    course.lessons = courseFromServer.lessons
    runInEdt {
      synchronize()
      ProjectView.getInstance(project).refresh()
      showNotification(newLessons, updateLessonsNumber)
    }
  }

  private fun showNotification(newLessons: List<Lesson>,
                               updateLessonsNumber: Int) {
    val message = ""
    if (!newLessons.isEmpty()) {
      if (newLessons.size > 1) {
        message.plus("Loaded ${newLessons.size} new lessons")
      }
      else {
        message.plus("Loaded one new lesson")
      }
      message.plus("\n")
    }

    if (updateLessonsNumber > 0) {
      if (updateLessonsNumber == 1) {
        message.plus("Updated one lesson")
      }
      else {
        message.plus("Updated $updateLessonsNumber lessons")
      }
      message.plus("\n")
    }

    // to remove
    message.plus("Updated $updatedTasksNumber tasks")

    val updateNotification = Notification("Update.course", "Course updated", "Current course is synchronized", NotificationType.INFORMATION)
    updateNotification.notify(project)
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun updateLessons(courseFromServer: Course): Int {
    val lessonsFromServer = courseFromServer.lessons.filter { lesson -> course.getLesson(lesson.id) != null }
    var updatedLessonsNumber = 0
    for (lessonFromServer in lessonsFromServer) {
      val currentLesson = course.getLesson(lessonFromServer.id)
      val taskIdsToUpdate = taskIdsToUpdate(lessonFromServer, currentLesson)
      if (!taskIdsToUpdate.isEmpty()) {
        updatedLessonsNumber++
        updatedTasksNumber += taskIdsToUpdate.size
      }
      val updatedTasks = ArrayList(upToDateTasks(currentLesson, taskIdsToUpdate))
      lessonFromServer.taskList.withIndex().forEach({ (index, task) -> task.index = index + 1 })

      val lessonDir = getLessonDir(lessonFromServer)
      for (taskId in taskIdsToUpdate) {
        val taskFromServer = lessonFromServer.getTask(taskId)
        val taskIndex = taskFromServer.index

        if (taskExists(currentLesson, taskId)) {
          val currentTask = currentLesson.getTask(taskId)
          if (isSolved(currentTask!!)) {
            updatedTasks.add(currentTask)
            currentTask.index = taskIndex
            continue
          }
          if (updateFilesNeeded(currentTask)) {
            removeExistingDir(currentTask, lessonDir)
          }
        }

        taskFromServer.initTask(currentLesson, false)

        if (updateFilesNeeded(taskFromServer)) {
          createTaskDirectories(lessonDir!!, taskFromServer)
        }
        updatedTasks.add(taskFromServer)
      }

      updatedTasks.sortBy { task -> task.index }
      lessonFromServer.taskList = updatedTasks
    }
    return updatedLessonsNumber
  }

  private fun updateFilesNeeded(currentTask: Task?) =
    currentTask !is TheoryTask && currentTask !is ChoiceTask

  private fun upToDateTasks(currentLesson: Lesson?,
                            taskIdsToUpdate: List<Int>) =
    currentLesson!!.taskList.filter { task -> !taskIdsToUpdate.contains(task.stepId) }

  @Throws(IOException::class)
  private fun removeExistingDir(studentTask: Task,
                                lessonDir: VirtualFile?) {
    val taskDir = getTaskDir(studentTask.name, lessonDir)
    invokeAndWaitIfNeed { runWriteAction { taskDir?.delete(studentTask) } }
  }

  @Throws(IOException::class)
  private fun createTaskDirectories(lessonDir: VirtualFile,
                                    task: Task) {
    GeneratorUtils.createTask(task, lessonDir)
  }

  private fun getTaskDir(taskName: String, lessonDir: VirtualFile?): VirtualFile? {
    return lessonDir?.findChild(taskName)
  }

  private fun taskExists(lesson: Lesson, taskId: Int): Boolean {
    return lesson.getTask(taskId) != null
  }

  private fun isSolved(studentTask: Task): Boolean {
    return CheckStatus.Solved == studentTask.status
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun taskIdsToUpdate(lessonFromServer: Lesson,
                              currentLesson: Lesson): List<Int> {
    val taskIds = lessonFromServer.getTaskList().map { task -> task.stepId.toString() }.toTypedArray()

    return lessonFromServer.taskList
      .zip(taskIds)
      .filter { (newTask, taskId) ->
        val task = currentLesson.getTask(Integer.parseInt(taskId))
        task == null || task.updateDate.before(newTask.updateDate)
      }
      .map { (_, taskId) -> Integer.parseInt(taskId) }
  }

  @Throws(IOException::class)
  private fun createNewLessons(project: Project,
                               newLessons: List<Lesson>): List<Lesson> {
    for (lesson in newLessons) {
      val baseDir = project.baseDir
      val lessonDir = baseDir.findChild(lesson.name)
      if (lessonDir != null) {
        saveDirectory(lessonDir)
      }

      lesson.initLesson(course, false)
      GeneratorUtils.createLesson(lesson, project.baseDir)
    }
    return newLessons
  }

  private fun getLessonDir(lesson: Lesson): VirtualFile? {
    val baseDir = project.baseDir
    val lessonDir = baseDir.findChild(lesson.name)

    val currentLesson = course.getLesson(lesson.id)

    if (currentLesson.index == lesson.index) {
      return lessonDir
    }
    if (lessonDir != null) {
      saveDirectory(lessonDir)
    }

    if (oldLessonDirectories.containsKey(lesson.id)) {
      val savedDir = oldLessonDirectories[lesson.id]
      invokeAndWaitIfNeed {
        runWriteAction {
          try {
            savedDir!!.rename(this, lesson.name)
            oldLessonDirectories.remove(lesson.id)
          }
          catch (e: IOException) {
            LOG.warn(e.message)
          }
        }
      }
      return savedDir
    }
    else {
      val oldLessonDir = baseDir.findChild(currentLesson.name)
      invokeAndWaitIfNeed {
        runWriteAction {
          try {
            oldLessonDir!!.rename(this, lesson.name)
          }
          catch (e: IOException) {
            LOG.warn(e.message)
          }
        }
      }
      return oldLessonDir
    }
  }


  private fun saveDirectory(lessonDir: VirtualFile) {
    val lessonForDirectory = course.getLesson(lessonDir.nameWithoutExtension)

    invokeAndWaitIfNeed {
      runWriteAction {
        try {
          lessonDir.rename(lessonForDirectory, "old_${lessonDir.name}")
          oldLessonDirectories[lessonForDirectory!!.id] = lessonDir
        }
        catch (e: IOException) {
          LOG.warn(e.message)
        }
      }
    }
  }

  private fun courseFromServer(project: Project, currentCourse: RemoteCourse): Course? {
    var course: Course? = null
    try {
      val remoteCourse = getCourseFromStepik(EduSettings.getInstance().user, currentCourse.id, true)
      if (remoteCourse != null) {
        course = getCourse(project, remoteCourse)
      }
    }
    catch (e: IOException) {
      LOG.warn(e.message)
    }

    return course
  }

}
