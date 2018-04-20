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

    courseFromServer.items.withIndex().forEach({ (index, lesson) -> lesson.index = index + 1 })

    //update top level lessons
    val newLessons = courseFromServer.lessons.filter { course.getLesson(it.id) == null }
    if (!newLessons.isEmpty()) {
      createNewLessons(newLessons, project.baseDir)
    }
    val lessonsUpdated = updateLessons(
      courseFromServer.lessons.filter { course.getLesson(it.id) != null },
      course)
    course.items = courseFromServer.items
    setCourseInfo(courseFromServer)
    runInEdt {
      synchronize()
      ProjectView.getInstance(project).refresh()
      showNotification(newLessons.size, lessonsUpdated)
      course.configurator?.courseBuilder?.refreshProject(project)
    }
  }

  private fun setCourseInfo(courseFromServer: Course) {
    course.name = courseFromServer.name
    course.description = courseFromServer.description
  }

  private fun showNotification(newLessons: Int,
                               updateLessonsNumber: Int) {
    val message = buildString {
      if (newLessons > 0) {
        append(if (newLessons > 1) "Loaded ${newLessons} new lessons" else "Loaded one new lesson")
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
  private fun updateLessons(lessonsFromServer: List<Lesson>, parent: ItemContainer): Int {
    var lessonsUpdated = 0
    for (lessonFromServer in lessonsFromServer) {
      lessonFromServer.taskList.withIndex().forEach { (index, task) -> task.index = index + 1 }

      val currentLesson = parent.getLesson(lessonFromServer.id)

      val taskIdsToUpdate = taskIdsToUpdate(lessonFromServer, currentLesson!!)

      val updatedTasks = ArrayList(upToDateTasks(currentLesson, taskIdsToUpdate))

      if (!taskIdsToUpdate.isEmpty()) {
        lessonsUpdated++
        val lessonDir = getDir(lessonFromServer, parent.getLesson(lessonFromServer.id)!!)
        updateTasks(taskIdsToUpdate, lessonFromServer, currentLesson, updatedTasks, lessonDir)
      }

      updatedTasks.sortBy { task -> task.index }
      lessonFromServer.taskList = updatedTasks
      lessonFromServer.init(course, lessonFromServer.section, false)
    }
    return lessonsUpdated
  }

  @Throws(URISyntaxException::class, IOException::class)
  private fun updateSections(sectionsFromServer: List<Section>) {
    val sectionsById = course.sections.associateBy({ it.id }, { it })
    for (sectionFromServer in sectionsFromServer) {
      sectionFromServer.lessons.withIndex().forEach { (index, lesson) -> lesson.index = index + 1 }

      val currentSection = sectionsById[sectionFromServer.id]

      val currentLessons = currentSection!!.lessons.map { it.id }

      val newLessons = sectionFromServer.lessons.filter { it.id !in currentLessons}
      if (!newLessons.isEmpty()) {
        val currentSectionDir = getDir(sectionFromServer, currentSection)
        createNewLessons(newLessons, currentSectionDir)
      }

      val lessonsToUpdate = sectionFromServer.lessons.filter { it.id in currentLessons }
      updateLessons(lessonsToUpdate, sectionFromServer)
    }
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


  private fun createNewLessons(newLessons: List<Lesson>, parentDir: VirtualFile) {
    for (lesson in newLessons) {
      if (directoryAlreadyExists(lesson.getLessonDir(project))) {
        saveExistingDirectory(lesson)
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

  private fun rename(dirToRename: VirtualFile, s: String) {
    invokeAndWaitIfNeed {
      runWriteAction {
        try {
          dirToRename.rename(this, s)
        }
        catch (e: IOException) {
          LOG.warn(e)
        }
      }
    }
  }

  private fun renamed(currentItem: StudyItem, newItem: StudyItem) = currentItem.name != newItem.name

  private fun directoryAlreadyExists(directory: VirtualFile?): Boolean {
    return directory != null
  }

  private fun getDir(newItem: StudyItem, currentItem: StudyItem): VirtualFile {
    if (renamed(currentItem, newItem)) {
      return itemDir(newItem)!!
    }

    if (directoryAlreadyExists(itemDir(newItem))) {
      saveSectionDirectory(itemDir(newItem)!!)
    }

    val currentSectionDir = getCurrentItemDir(currentItem)
    rename(currentSectionDir, newItem.name)

    return currentSectionDir
  }

  private fun getCurrentItemDir(item: StudyItem): VirtualFile {
    val dirsMap = if (item is Section) oldSectionDirectories else oldLessonDirectories
    val id = (item as? Section)?.id ?: (item as Lesson).id

    return if (dirsMap.containsKey(id)) {
      val savedDir = dirsMap[id]
      dirsMap.remove(id)
      savedDir!!
    }
    else {
      val oldSectionDir = project.baseDir.findChild(item.name)
      oldSectionDir!!
    }
  }

  private fun itemDir(item: StudyItem): VirtualFile? {
    return if (item is Section) {
      project.baseDir.findChild(item.name)
    }
    else {
      (item as Lesson).getLessonDir(project)
    }
  }


  private fun saveExistingDirectory(lesson: Lesson) {
    val lessonDir = lesson.getLessonDir(project)

    invokeAndWaitIfNeed {
      runWriteAction {
        try {
          lessonDir!!.rename(lesson, "old_${lessonDir.name}")
          oldLessonDirectories[lesson.id] = lessonDir
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
