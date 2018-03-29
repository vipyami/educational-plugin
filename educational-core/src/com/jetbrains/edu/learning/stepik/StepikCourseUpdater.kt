package com.jetbrains.edu.learning.stepik

import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.EduNames
import com.jetbrains.edu.learning.EduSettings
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.courseFormat.CheckStatus
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.RemoteCourse
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils
import com.jetbrains.edu.learning.stepik.StepikConnector.*
import java.io.IOException
import java.net.URISyntaxException
import java.util.*
import kotlin.collections.ArrayList

class StepikCourseUpdater(private val course: RemoteCourse, private val project: Project) {
  private val LOG = Logger.getInstance(this.javaClass)

  private val oldLessonDirectories = HashMap<Int, VirtualFile>()

  fun updateCourse() {
    oldLessonDirectories.clear()
    val courseFromServer = courseFromServer(project, course)
    if (courseFromServer == null) {
      LOG.warn("Course ${course.id} not found on Stepik")
      return
    }

    courseFromServer.lessons.withIndex().forEach({ (index, lesson) -> lesson.index = index + 1 })

    if (!course.isUpToDate) {
      createNewLessons(project, courseFromServer)
    }
    updateLessons(courseFromServer)

    course.lessons = courseFromServer.lessons
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun updateLessons(courseFromServer: Course) {
    val updatedLessons = courseFromServer.lessons.filter { lesson -> course.getLesson(lesson.id) != null }
    for (lessonFromServer in updatedLessons) {
      val currentLesson = course.getLesson(lessonFromServer.id)
      val taskIdsToUpdate = taskIdsToUpdates(lessonFromServer, currentLesson)
      val updatedTasks = ArrayList(notUpdatedTasks(currentLesson, taskIdsToUpdate))
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

          removeExistingDir(currentTask, lessonDir)
        }


        createTaskDirectories(currentLesson, lessonDir!!, taskFromServer)
        updatedTasks.add(taskFromServer)
      }

      updatedTasks.sortBy { task -> task.index }
      lessonFromServer.taskList = updatedTasks
    }
  }

  private fun notUpdatedTasks(currentLesson: Lesson?,
                              taskIdsToUpdate: List<Int>) =
    currentLesson!!.taskList.filter { task -> !taskIdsToUpdate.contains(task.stepId) }

  @Throws(IOException::class)
  private fun removeExistingDir(studentTask: Task,
                                lessonDir: VirtualFile?) {
    val taskDir = getTaskDir(studentTask.index, lessonDir)
    invokeAndWaitIfNeed { runWriteAction { taskDir?.delete(studentTask) } }
  }

  @Throws(IOException::class)
  private fun createTaskDirectories(lesson: Lesson, lessonDir: VirtualFile, task: Task) {
    task.initTask(lesson, false)

    GeneratorUtils.createTask(task, lessonDir)
  }

  private fun getTaskDir(taskIndex: Int, lessonDir: VirtualFile?): VirtualFile? {
    val taskDirName = EduNames.TASK + taskIndex.toString()

    return lessonDir?.findChild(taskDirName)
  }

  private fun taskExists(lesson: Lesson, taskId: Int): Boolean {
    return lesson.getTask(taskId) != null
  }

  private fun isSolved(studentTask: Task): Boolean {
    return CheckStatus.Solved == studentTask.status
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun taskIdsToUpdates(lessonFromServer: Lesson,
                               currentLesson: Lesson): List<Int> {
    val taskIds = lessonFromServer.getTaskList().map { task -> task.stepId.toString() }.toTypedArray()
    val stepContainers = multipleRequestToStepik(StepikNames.STEPS, taskIds, StepikWrappers.StepContainer::class.java)

    val steps = stepContainers.flatMap({ stepContainer -> stepContainer.steps })
    return steps
      .zip(taskIds)
      .filter { (step, taskId) ->
        val task = currentLesson.getTask(Integer.parseInt(taskId))
        task == null || step.update_date.after(task.updateDate)
      }
      .map { (_, taskId) -> Integer.parseInt(taskId) }
  }

  @Throws(IOException::class)
  private fun createNewLessons(project: Project,
                               courseFromServer: Course): List<Lesson> {
    val newLessons = courseFromServer.lessons.filter { lesson -> course.getLesson(lesson.id) == null }
    for (lesson in newLessons) {
      val baseDir = project.baseDir
      val newLessonDirName = EduNames.LESSON + lesson.index
      val lessonDir = baseDir.findChild(newLessonDirName)
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
    val newLessonDirName = EduNames.LESSON + lesson.index
    val lessonDir = baseDir.findChild(newLessonDirName)

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
            savedDir!!.rename(this, newLessonDirName)
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
      val oldLessonDirName = EduNames.LESSON + currentLesson.index
      val oldLessonDir = baseDir.findChild(oldLessonDirName)
      invokeAndWaitIfNeed {
        runWriteAction {
          try {
            oldLessonDir!!.rename(this, newLessonDirName)
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
    val index = EduUtils.getIndex(lessonDir.name, EduNames.LESSON)
    val lessonForDirectory = course.lessons[index]

    invokeAndWaitIfNeed {
      runWriteAction {
        try {
          lessonDir.rename(lessonForDirectory, "old_${lessonDir.name}")
          oldLessonDirectories[lessonForDirectory.id] = lessonDir
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
