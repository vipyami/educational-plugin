package com.jetbrains.edu.coursecreator.stepik

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.*
import com.jetbrains.edu.learning.courseFormat.ext.getVirtualFile
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.isUnitTestMode

data class StepikChangesInfo(var isCourseInfoChanged: Boolean = false,
                             var newSections: List<Section> = ArrayList(),
                             var sectionInfosToUpdate: List<Section> = ArrayList(),
                             var newLessons: List<Lesson> = ArrayList(),
                             var lessonsInfoToUpdate: List<Lesson> = ArrayList(),
                             var tasksToUpdateByLessonIndex: Map<Int, List<Task>> = HashMap(),
                             var tasksToPostByLessonIndex: Map<Int, List<Task>> = HashMap())


fun getChangedItems(project: Project, courseFromServer: RemoteCourse): StepikChangesInfo {
  val course = StudyTaskManager.getInstance(project).course as RemoteCourse
  if (!isUnitTestMode) {
    setTaskFileTextFromDocuments(project, course)
  }
  val stepikChanges = StepikChangesInfo()

  stepikChanges.isCourseInfoChanged = courseInfoChanged(course, courseFromServer)

  val sectionIdsFromServer = courseFromServer.sections.map { it.id }
  stepikChanges.newSections = course.sections.filter { !sectionIdsFromServer.contains(it.id) }
  stepikChanges.sectionInfosToUpdate = sectionsInfoToUpdate(course, sectionIdsFromServer, courseFromServer)

  val serverLessonIds = lessonIds(courseFromServer)
  val allLessons = allLessons(course)

  stepikChanges.newLessons = allLessons.filter { lesson -> !serverLessonIds.contains(lesson.id) }
  stepikChanges.lessonsInfoToUpdate = lessonsInfoToUpdate(course, serverLessonIds, courseFromServer)

  val updateCandidates = allLessons.filter { lesson -> serverLessonIds.contains(lesson.id) }
  val lessonsById = allLessons(courseFromServer).associateBy({ it.id }, { it })
  stepikChanges.tasksToPostByLessonIndex = updateCandidates
    .filter { !stepikChanges.newLessons.contains(it) }
    .associateBy({ it.index },
                 { newTasks(lessonsById[it.id]!!, it) })
    .filterValues { !it.isEmpty() }
  stepikChanges.tasksToUpdateByLessonIndex = updateCandidates.associateBy({ it.index },
                                                                          {
                                                                            tasksToUpdate(lessonsById[it.id]!!, it)
                                                                          }).filterValues { !it.isEmpty() }

  return stepikChanges
}

private fun allLessons(course: RemoteCourse) =
  course.lessons.plus(course.sections.flatMap { it.lessons })

fun setStepikChangeStatuses(project: Project,
                            courseFromStepik: RemoteCourse) {
  val course = StudyTaskManager.getInstance(project).course as RemoteCourse
  val stepikChanges = getChangedItems(project, courseFromStepik)

  if (stepikChanges.isCourseInfoChanged) {
    StepikCourseChangeHandler.infoChanged(course)
  }

  stepikChanges.newSections.forEach {
    StepikCourseChangeHandler.contentChanged(course)
  }

  stepikChanges.sectionInfosToUpdate.forEach {
    StepikCourseChangeHandler.infoChanged(it)
  }

  stepikChanges.newLessons.forEach {
    StepikCourseChangeHandler.contentChanged(it.section ?: course)
  }

  stepikChanges.lessonsInfoToUpdate.forEach {
    StepikCourseChangeHandler.infoChanged(it)
  }

  stepikChanges.tasksToPostByLessonIndex.forEach { _, taskList ->
    if (!taskList.isEmpty()) {
      StepikCourseChangeHandler.contentChanged(taskList.single().lesson)
    }
  }

  stepikChanges.tasksToUpdateByLessonIndex.forEach { _, taskList ->
    taskList.forEach {
      StepikCourseChangeHandler.changed(it)
    }
  }
}


private fun setTaskFileTextFromDocuments(project: Project, course: RemoteCourse) {
  runInEdtAndWait {
    runReadAction {
      course.lessons
        .flatMap { it.taskList }
        .flatMap { it.taskFiles.values }
        .forEach { it.text = EduUtils.createStudentFile(project, it.getVirtualFile(project)!!, it.task)!!.text }
    }
  }
}

private fun taskIds(lessonFormServer: Lesson) = lessonFormServer.taskList.map { task -> task.stepId }

private fun newTasks(lessonFormServer: Lesson, updateCandidate: Lesson): List<Task> {
  val onServerTaskIds = taskIds(lessonFormServer)
  return updateCandidate.taskList.filter { task -> !onServerTaskIds.contains(task.stepId) }
}


private fun lessonIds(latestCourseFromServer: RemoteCourse) = latestCourseFromServer.lessons
  .plus(latestCourseFromServer.sections.flatMap { it.lessons })
  .map { lesson -> lesson.id }

private fun courseInfoChanged(course: RemoteCourse, latestCourseFromServer: RemoteCourse): Boolean {
  return course.name != latestCourseFromServer.name ||
         course.description != latestCourseFromServer.description ||
         course.humanLanguage != latestCourseFromServer.humanLanguage ||
         course.languageID != latestCourseFromServer.languageID
}

private fun tasksToUpdate(lessonFormServer: Lesson, updateCandidate: Lesson): List<Task> {
  val onServerTaskIds = taskIds(lessonFormServer)
  val tasksUpdateCandidate = updateCandidate.taskList.filter { task -> task.stepId in onServerTaskIds }

  val taskById = lessonFormServer.taskList.associateBy({ it.stepId }, { it })
  return tasksUpdateCandidate.filter { !it.isEqualTo(taskById[it.stepId]) }
}

private fun lessonsInfoToUpdate(course: Course,
                                serverLessonIds: List<Int>,
                                latestCourseFromServer: RemoteCourse): List<Lesson> {
  return course.lessons
    .filter { lesson -> serverLessonIds.contains(lesson.id) }
    .filter { updateCandidate ->
      val lessonFormServer = latestCourseFromServer.getLesson(updateCandidate.id)!!
      lessonFormServer.index != updateCandidate.index ||
      lessonFormServer.name != updateCandidate.name ||
      lessonFormServer.isPublic != updateCandidate.isPublic
    }
}


private fun sectionsInfoToUpdate(course: RemoteCourse,
                                 sectionIdsFromServer: List<Int>,
                                 latestCourseFromServer: RemoteCourse): List<Section> {
  val sectionsById = latestCourseFromServer.sections.associateBy({ it.id }, { it })
  return course.sections
    .filter { sectionIdsFromServer.contains(it.id) }
    .filter {
      val sectionFromServer = sectionsById[it.id]!!
      it.index != sectionFromServer.index ||
      it.name != sectionFromServer.name
    }
}


private fun AnswerPlaceholderDependency.isEqualTo(otherDependency: AnswerPlaceholderDependency?): Boolean {
  if (this === otherDependency) return true
  if (otherDependency == null) return false

  if (this.isVisible != otherDependency.isVisible) {
    return false
  }

  if (this.fileName != otherDependency.fileName) {
    return false
  }

  if (this.lessonName != otherDependency.lessonName) {
    return false
  }

  if (this.placeholderIndex != otherDependency.placeholderIndex) {
    return false
  }

  if (this.sectionName != otherDependency.sectionName) {
    return false
  }

  return true
}


private fun AnswerPlaceholder.isEqualTo(otherAnswerPlaceholder: AnswerPlaceholder?): Boolean {
  if (this === otherAnswerPlaceholder) return true
  if (otherAnswerPlaceholder == null) return false

  if (offset != otherAnswerPlaceholder.offset) {
    return false
  }

  if (length != otherAnswerPlaceholder.length) {
    return false
  }

  if (index != otherAnswerPlaceholder.index) {
    return false
  }

  if (possibleAnswer != otherAnswerPlaceholder.possibleAnswer) {
    return false
  }

  if (placeholderDependency == null && otherAnswerPlaceholder.placeholderDependency != null) {
    return false
  }

  if (placeholderDependency == null && otherAnswerPlaceholder.placeholderDependency == null) {
    return true
  }

  if (!placeholderDependency!!.isEqualTo(otherAnswerPlaceholder.placeholderDependency)) {
    return false
  }

  return true
}


private fun TaskFile.isEqualTo(otherTaskFile: TaskFile?): Boolean {
  if (this === otherTaskFile) return true

  val otherPlaceholders = otherTaskFile!!.answerPlaceholders
  if (answerPlaceholders.size != otherPlaceholders.size) {
    return false
  }

  for (i in answerPlaceholders.indices) {
    if (!answerPlaceholders[i].isEqualTo(otherPlaceholders[i])) {
      return false
    }
  }

  if (name != otherTaskFile.name) {
    return false
  }

  if (text != otherTaskFile.text) {
    return false
  }

  return true
}


private fun Task.isEqualTo(otherTask: Task?): Boolean {
  if (this === otherTask) return true
  if (otherTask == null) return false

  if (descriptionText != otherTask.descriptionText) {
    return false
  }

  if (index != otherTask.index) {
    return false
  }

  if (name != otherTask.name) {
    return false
  }

  val otherTaskFiles = otherTask.taskFiles
  if (taskFiles.size != otherTaskFiles.size) {
    return false
  }
  for (entry in taskFiles.entries) {
    val name = entry.key
    val taskFile = entry.value

    if (!otherTaskFiles.containsKey(name)) {
      return false
    }

    if (!taskFile.isEqualTo(otherTaskFiles[name])) {
      return false
    }
  }

  val otherAdditionalFiles = otherTask.additionalFiles
  for (entry in additionalFiles.entries) {
    val name = entry.key
    val text = entry.value

    if (!otherAdditionalFiles.containsKey(name)) {
      return false
    }

    if (text != otherAdditionalFiles[name]) {
      return false
    }
  }

  return true
}