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
import com.jetbrains.edu.learning.courseFormat.*
import com.jetbrains.edu.learning.courseFormat.ext.configurator
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
  private val oldSectionDirectories = HashMap<Int, VirtualFile>()

  fun updateCourse() {
    oldLessonDirectories.clear()
    oldSectionDirectories.clear()
    val courseFromServer = courseFromServer(project, course)
    if (courseFromServer == null) {
      LOG.warn("Course ${course.id} not found on Stepik")
      return
    }

    val newSections= courseFromServer.sections.filter { section -> section.id !in course.sectionIds }
    if (!newSections.isEmpty()) {
      createNewSections(project, newSections)
    }
    val sectionsToUpdate = courseFromServer.sections.filter { section -> section.id in course.sectionIds }
    updateSections(sectionsToUpdate)

    courseFromServer.lessons.withIndex().forEach({ (index, lesson) -> lesson.index = index + 1 })

    //update top level lessons
    val newLessons = courseFromServer.lessons.filter { course.getLesson(it.id) == null }
    if (!newLessons.isEmpty()) {
      createNewLessons(project, newLessons, project.baseDir)
    }
    val updateLessonsNumber = updateLessons(
      courseFromServer.lessons.filter { course.getLesson(it.id) != null },
      course)
    course.items = courseFromServer.items
    setCourseInfo(courseFromServer)
    runInEdt {
      synchronize()
      ProjectView.getInstance(project).refresh()
      showNotification(newLessons, updateLessonsNumber)
      // TODO: use method from Katya's section changes
      course.configurator?.courseBuilder?.refreshProject(project)
    }
  }

  private fun setCourseInfo(courseFromServer: Course) {
    course.name = courseFromServer.name
    course.description = courseFromServer.description
  }

  private fun showNotification(newLessons: List<Lesson>,
                               updateLessonsNumber: Int) {
    val message = buildString {
      if (!newLessons.isEmpty()) {
        append(if (newLessons.size > 1) "Loaded ${newLessons.size} new lessons" else "Loaded one new lesson")
        append("\n")
      }

      if (updateLessonsNumber > 0) {
        append(if (updateLessonsNumber == 1) "Updated one lesson" else "Updated $updateLessonsNumber lessons")
        append("\n")
      }
    }

    val updateNotification = Notification("Update.course", "Course updated", message, NotificationType.INFORMATION)
    updateNotification.notify(project)
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun updateLessons(lessonsFromServer: List<Lesson>,
                            parent: ItemContainer): Int {
    var updatedLessonsNumber = 0
    for (lessonFromServer in lessonsFromServer) {
      updatedLessonsNumber++
      val currentLesson = parent.getLesson(lessonFromServer.id)
      val taskIdsToUpdate = taskIdsToUpdate(lessonFromServer, currentLesson!!)
      lessonFromServer.taskList.withIndex().forEach { (index, task) -> task.index = index + 1 }
      val lessonDir = getLessonDir(lessonFromServer, parent)
      val updatedTasks = ArrayList(upToDateTasks(currentLesson, taskIdsToUpdate))
      if (currentLesson.name != lessonFromServer.name) {
        val currentLessonDir = getLessonDir(currentLesson, parent)
        invokeAndWaitIfNeed { runWriteAction { currentLessonDir?.rename(this, lessonFromServer.name) } }
      }
      if (!taskIdsToUpdate.isEmpty()) {
        updatedTasksNumber += taskIdsToUpdate.size
        updateTasks(taskIdsToUpdate, lessonFromServer, currentLesson, updatedTasks, lessonDir)
      }

      updatedTasks.sortBy { task -> task.index }
      lessonFromServer.taskList = updatedTasks
      lessonFromServer.init(course, lessonFromServer.section, false)
    }
    return updatedLessonsNumber
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun updateSections(sectionsFromServer: List<Section>): Int {
    var updated = 0
    val sectionsById = course.sections.associateBy({ it.id }, { it })
    for (sectionFromServer in sectionsFromServer) {
      updated++

      val currentSection = sectionsById[sectionFromServer.id]
      val currentSectionDir = getSectionDir(currentSection!!)
      //set section info
      currentSection.index = sectionFromServer.index
      if (currentSection.name != sectionFromServer.name) {
        invokeAndWaitIfNeed { runWriteAction { currentSectionDir?.rename(this, sectionFromServer.name) } }
      }
      val currentLessons = currentSection.lessons.map { it.id }
      val newLessons = sectionFromServer.lessons.filter { it.id !in currentLessons}
      if (!newLessons.isEmpty()) {
        createNewLessons(project, newLessons, project.baseDir.findChild(sectionFromServer.name)!!)
      }

      val lessonsToUpdate = sectionFromServer.lessons.filter { it.id in currentLessons }
      updateLessons(lessonsToUpdate, sectionFromServer)
    }
    return updated
  }


  private fun updateTasks(taskIdsToUpdate: List<Int>,
                          lessonFromServer: Lesson,
                          currentLesson: Lesson,
                          updatedTasks: ArrayList<Task>,
                          lessonDir: VirtualFile?) {
    val serverTasksById = lessonFromServer.taskList.associateBy({ it.stepId }, { it })
    val tasksById = currentLesson.taskList.associateBy({ it.stepId }, { it })
    for (taskId in taskIdsToUpdate) {
      val taskFromServer = serverTasksById[taskId]
      val taskIndex = taskFromServer!!.index
      if (tasksById.containsKey(taskId)) {
        val currentTask = tasksById[taskId]
        if (isSolved(currentTask!!)) {
          updatedTasks.add(currentTask)
          currentTask.index = taskIndex
          currentTask.taskTexts = taskFromServer.taskTexts
          continue
        }
        if (updateFilesNeeded(currentTask)) {
          removeExistingDir(currentTask, lessonDir)
        }
      }

      taskFromServer.init(course, currentLesson, false)

      createTaskDirectories(lessonDir!!, taskFromServer)
      updatedTasks.add(taskFromServer)
    }
  }

  private fun updateFilesNeeded(currentTask: Task?) =
    currentTask !is TheoryTask && currentTask !is ChoiceTask

  private fun upToDateTasks(currentLesson: Lesson,
                            taskIdsToUpdate: List<Int>) =
    currentLesson.taskList.filter { task -> !taskIdsToUpdate.contains(task.stepId) }

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

  private fun isSolved(studentTask: Task): Boolean {
    return CheckStatus.Solved == studentTask.status
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun taskIdsToUpdate(lessonFromServer: Lesson,
                              currentLesson: Lesson): List<Int> {
    val taskIds = lessonFromServer.getTaskList().map { task -> task.stepId.toString() }.toTypedArray()
    val tasksById = currentLesson.taskList.associateBy({ it.stepId }, { it })

    return lessonFromServer.taskList
      .zip(taskIds)
      .filter { (newTask, taskId) ->
        val task = tasksById[Integer.parseInt(taskId)]
        task == null || task.updateDate.before(newTask.updateDate)
      }
      .map { (_, taskId) -> Integer.parseInt(taskId) }
  }


  private fun createNewLessons(project: Project,
                               newLessons: List<Lesson>,
                               parentDir: VirtualFile) {
    for (lesson in newLessons) {
      val lessonDir = lesson.getLessonDir(project)
      if (lessonDir != null) {
        saveLessonDirectory(lessonDir)
      }

      lesson.init(course, lesson.section, false)
      GeneratorUtils.createLesson(lesson, parentDir)
    }
  }

  private fun createNewSections(project: Project,
                               newSections: List<Section>){
    for (section in newSections) {
      val baseDir = project.baseDir
      val sectionDir = baseDir.findChild(section.name)
      if (sectionDir != null) {
        saveSectionDirectory(sectionDir)
      }
      section.init(course, course, false)
      GeneratorUtils.createSection(section, project.baseDir)
    }
  }

  private fun getLessonDir(lesson: Lesson,
                           parent: ItemContainer): VirtualFile? {
    val lessonDir =lesson.getLessonDir(project)
    val currentLesson = parent.getLesson(lesson.id)

    if (currentLesson!!.index == lesson.index && currentLesson.name == lesson.name) {
      return lessonDir
    }
    if (lessonDir != null) {
      saveLessonDirectory(lessonDir)
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
      val oldLessonDir = currentLesson.getLessonDir(project)
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

  private fun getSectionDir(section: Section): VirtualFile? {
    val baseDir = project.baseDir
    val sectionDir = baseDir.findChild(section.name)
    val currentSection = course.getSection(section.name)

    if (currentSection!!.index == section.index && currentSection.name == section.name) {
      return sectionDir
    }
    if (sectionDir != null) {
      saveSectionDirectory(sectionDir)
    }

    if (oldSectionDirectories.containsKey(section.id)) {
      val savedDir = oldSectionDirectories[section.id]
      invokeAndWaitIfNeed {
        runWriteAction {
          try {
            savedDir!!.rename(this, section.name)
            oldSectionDirectories.remove(section.id)
          }
          catch (e: IOException) {
            LOG.warn(e.message)
          }
        }
      }
      return savedDir
    }
    else {
      val oldSectionDir = baseDir.findChild(currentSection.name)
      invokeAndWaitIfNeed {
        runWriteAction {
          try {
            oldSectionDir!!.rename(this, section.name)
          }
          catch (e: IOException) {
            LOG.warn(e.message)
          }
        }
      }
      return oldSectionDir
    }
  }


  private fun saveLessonDirectory(lessonDir: VirtualFile) {
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

  private fun saveSectionDirectory(sectionDir: VirtualFile) {
    val sectionForDirectory = course.getSection(sectionDir.nameWithoutExtension)

    invokeAndWaitIfNeed {
      runWriteAction {
        try {
          sectionDir.rename(sectionForDirectory, "old_${sectionDir.name}")
          oldSectionDirectories[sectionForDirectory!!.id] = sectionDir
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
