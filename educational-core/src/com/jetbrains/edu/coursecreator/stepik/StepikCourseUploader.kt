package com.jetbrains.edu.coursecreator.stepik

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.edu.coursecreator.stepik.CCStepikConnector.postUnit
import com.jetbrains.edu.coursecreator.stepik.CCStepikConnector.updateAdditionalMaterials
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.RemoteCourse
import com.jetbrains.edu.learning.courseFormat.ext.getDocument
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.stepik.StepikNames

class StepikCourseUploader(private val project: Project) {
  private var isCourseInfoChanged = false
  private var newLessons: List<Lesson> = ArrayList()
  private lateinit var lessonsInfoToUpdate: List<Lesson>
  private lateinit var lessonsToUpdate: List<Lesson>
  private var tasksToUpdateByLessonIndex: Map<Int, List<Task>> = HashMap()
  private var tasksToPostByLessonIndex: Map<Int, List<Task>> = HashMap()
  private val course: RemoteCourse = StudyTaskManager.getInstance(project).course as RemoteCourse

  private fun init() {
    var courseFromServer = StudyTaskManager.getInstance(project).latestCourseFromServer
    if (courseFromServer != null) {
      setTaskFileTextFromDocuments()
    }
    else {
      courseFromServer = course
    }

    isCourseInfoChanged = courseInfoChanged(courseFromServer)

    val serverLessonIds = lessonIds(courseFromServer)
    newLessons = course.lessons.filter { lesson -> !serverLessonIds.contains(lesson.id) }

    lessonsInfoToUpdate = lessonsInfoToUpdate(course, serverLessonIds, courseFromServer)

    val updateCandidates = course.lessons.filter { lesson -> serverLessonIds.contains(lesson.id) }
    tasksToPostByLessonIndex = updateCandidates.associateBy({ it.index }, { newTasks(courseFromServer, it) }).filterValues { !it.isEmpty() }
    tasksToUpdateByLessonIndex = updateCandidates.associateBy({ it.index },
                                                              { tasksToUpdate(courseFromServer, it) }).filterValues { !it.isEmpty() }
    lessonsToUpdate = updateCandidates.filter {
      tasksToPostByLessonIndex.containsKey(it.index) || tasksToUpdateByLessonIndex.containsKey(it.index)
    }
  }

  fun uploadWithProgress(showNotification: Boolean) {
    ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Modal(project, "Updating Course", false) {
      override fun run(progressIndicator: ProgressIndicator) {
        init()

        progressIndicator.isIndeterminate = true
        var postedCourse: RemoteCourse? = null
        if (isCourseInfoChanged) {
          progressIndicator.text = "Updating course info"
          postedCourse = CCStepikConnector.updateCourseInfo(project, course)
        }

        if (!newLessons.isEmpty()) {
          if (!isCourseInfoChanged) {
            // it's updating course update date that is used in student project to check if there are new lessons
            postedCourse = CCStepikConnector.updateCourseInfo(project, course)
          }
          uploadLessons(progressIndicator)
        }

        if (!lessonsInfoToUpdate.isEmpty()) {
          updateLessonsInfo(progressIndicator)
        }

        if (!lessonsToUpdate.isEmpty()) {
          updateTasks(progressIndicator)
        }

        updateAdditionalMaterials(postedCourse)

        StudyTaskManager.getInstance(project).latestCourseFromServer = StudyTaskManager.getInstance(project).course!!.copy() as RemoteCourse?
        if (showNotification) {
          val message = StringBuilder()
          if (!newLessons.isEmpty()) {
            message.append(if (newLessons.size == 1) "One lesson pushed." else "Pushed: ${newLessons.size} lessons.")
            message.append("\n")
          }
          if (!lessonsInfoToUpdate.isEmpty() || !lessonsToUpdate.isEmpty()) {
            val size = lessonsInfoToUpdate.size + lessonsToUpdate.size
            message.append(if (size == 1) "One lesson updated" else "Updated: $size lessons")
          }
          val title = if (message.isEmpty()) "Course is up to date" else "Course updated"
          CCStepikConnector.showNotification(project, title, message.toString(), "See on Stepik", {
            BrowserUtil.browse(StepikNames.STEPIK_URL + "/course/" + course.id)
          })
        }
      }
    })
  }


  private fun setTaskFileTextFromDocuments() {
    runInEdtAndWait {
      runReadAction {
        course.lessons
          .flatMap { it.taskList }
          .flatMap { it.taskFiles.values }
          .forEach { it.text = it.getDocument(project)?.text }

      }
    }
  }

  private fun taskIds(lessonFormServer: Lesson) = lessonFormServer.taskList.map { task -> task.stepId }

  private fun newTasks(courseFromServer: RemoteCourse, updateCandidate: Lesson): List<Task> {
    val lessonFormServer = courseFromServer.getLesson(updateCandidate.id)
    val onServerTaskIds = taskIds(lessonFormServer)
    return updateCandidate.taskList.filter { task -> !onServerTaskIds.contains(task.stepId) }
  }


  private fun lessonIds(latestCourseFromServer: RemoteCourse) = latestCourseFromServer.lessons.map { lesson -> lesson.id }

  private fun courseInfoChanged(latestCourseFromServer: RemoteCourse): Boolean {
    return course.name != latestCourseFromServer.name ||
           course.description != latestCourseFromServer.description ||
           course.humanLanguage != latestCourseFromServer.humanLanguage ||
           course.languageID != latestCourseFromServer.languageID
  }

  private fun updateAdditionalMaterials(postedCourse: RemoteCourse?) {
    val courseFromServer = postedCourse ?: CCStepikConnector.getCourseInfo(course.id.toString())
    invokeAndWaitIfNeed { FileDocumentManager.getInstance().saveAllDocuments() }
    val sectionIds = courseFromServer!!.sectionIds
    if (!updateAdditionalMaterials(project, course, sectionIds)) {
      CCStepikConnector.postAdditionalFiles(course, project, course.id, sectionIds.size)
    }
  }

  private fun updateTasks(progressIndicator: ProgressIndicator) {
    val totalSize = tasksToPostByLessonIndex.values.sumBy { lessonsList -> lessonsList.size } + tasksToUpdateByLessonIndex.values.sumBy { lessonsList -> lessonsList.size }
    val showVerboseProgress = showVerboseProgress(totalSize)
    progressIndicator.isIndeterminate = false
    var taskNumber = 0
    lessonsToUpdate.forEach { lesson ->
      progressIndicator.text = "Updating lesson: ${lesson.name}"
      if (tasksToPostByLessonIndex.contains(lesson.index)) {
        taskNumber++
        uploadTask(lesson, taskNumber, showVerboseProgress, progressIndicator, totalSize)
      }

      if (tasksToUpdateByLessonIndex.contains(lesson.index)) {
        taskNumber++
        updateTask(lesson, taskNumber, showVerboseProgress, progressIndicator, totalSize)
      }
    }
  }

  private fun updateTask(lesson: Lesson,
                         taskNumber: Int,
                         showVerboseProgress: Boolean,
                         progressIndicator: ProgressIndicator,
                         totalSize: Int) {
    tasksToUpdateByLessonIndex[lesson.index]!!.forEach { task ->
      if (showVerboseProgress) {
        progressIndicator.fraction = (taskNumber / totalSize).toDouble()
      }
      progressIndicator.text2 = "Updating task: ${task.name}"
      CCStepikConnector.updateTask(project, task, false)
    }
  }

  private fun uploadTask(lesson: Lesson,
                         taskNumber: Int,
                         showVerboseProgress: Boolean,
                         progressIndicator: ProgressIndicator,
                         totalSize: Int): Int {
    tasksToPostByLessonIndex[lesson.index]!!.forEach { task ->
      if (showVerboseProgress) {
        progressIndicator.fraction = (taskNumber / totalSize).toDouble()
      }
      progressIndicator.text2 = "Posting task: ${task.name}"
      CCStepikConnector.postTask(project, task, lesson.id)
    }
    return taskNumber
  }

  private fun updateLessonsInfo(progressIndicator: ProgressIndicator) {
    val showVerboseProgress = showVerboseProgress(lessonsInfoToUpdate.size)
    progressIndicator.isIndeterminate = !showVerboseProgress
    lessonsInfoToUpdate.withIndex().forEach { (index, lesson) ->
      if (showVerboseProgress) {
        progressIndicator.fraction = (index / newLessons.size).toDouble()
      }
      progressIndicator.text = "Updating lesson info: ${lesson.name}"
      CCStepikConnector.updateLessonInfo(project, lesson, false)
    }
  }

  private fun uploadLessons(progressIndicator: ProgressIndicator) {
    val showVerboseProgress = showVerboseProgress(newLessons.size)
    progressIndicator.isIndeterminate = !showVerboseProgress

    newLessons.withIndex().forEach { (index, lesson) ->
      if (showVerboseProgress) {
        progressIndicator.fraction = (index / newLessons.size).toDouble()
      }
      progressIndicator.text = "Posting lesson: ${lesson.name}"
      CCStepikConnector.postLesson(project, lesson)
      val sections = course.sectionIds
      val sectionId = sections[sections.size - 1]
      val unitId = postUnit(lesson.id, lesson.index, sectionId, project)
      if (unitId != -1) {
        lesson.unitId = unitId
      }
    }
  }

  private fun showVerboseProgress(totalSize: Int) = totalSize > 1

  private fun tasksToUpdate(courseFromServer: RemoteCourse, updateCandidate: Lesson): List<Task> {
    val lessonFormServer = courseFromServer.getLesson(updateCandidate.id)
    val onServerTaskIds = taskIds(lessonFormServer)
    val tasksUpdateCandidate = updateCandidate.taskList.filter { task -> onServerTaskIds.contains(task.stepId) }
    return tasksUpdateCandidate.filter { it != lessonFormServer.getTask(it.stepId) }
  }

  private fun lessonsInfoToUpdate(course: Course,
                                  serverLessonIds: List<Int>,
                                  latestCourseFromServer: RemoteCourse): ArrayList<Lesson> {
    val updateCandidates = course.lessons.filter { lesson -> serverLessonIds.contains(lesson.id) }
    val toUpdateInfo = ArrayList<Lesson>()
    for (updateCandidate in updateCandidates) {
      val lessonFormServer = latestCourseFromServer.getLesson(updateCandidate.id)

      if (lessonFormServer.index != updateCandidate.index) {
        toUpdateInfo.add(updateCandidate)
        continue
      }

      if (lessonFormServer.name != updateCandidate.name) {
        toUpdateInfo.add(updateCandidate)
        continue
      }

      if (lessonFormServer.isPublic != updateCandidate.isPublic) {
        toUpdateInfo.add(updateCandidate)
        continue
      }
    }

    return toUpdateInfo
  }
}

