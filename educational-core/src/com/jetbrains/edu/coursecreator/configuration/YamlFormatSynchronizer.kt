package com.jetbrains.edu.coursecreator.configuration

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSettings.COURSE_CONFIG
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSettings.LESSON_CONFIG
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSettings.SECTION_CONFIG
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSettings.TASK_CONFIG
import com.jetbrains.edu.coursecreator.configuration.mixins.*
import com.jetbrains.edu.learning.EduDocumentListener
import com.jetbrains.edu.learning.EduNames
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.*
import com.jetbrains.edu.learning.courseFormat.ext.project
import com.jetbrains.edu.learning.courseFormat.tasks.EduTask
import com.jetbrains.edu.learning.courseFormat.tasks.OutputTask
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseFormat.tasks.TheoryTask
import com.jetbrains.edu.learning.editor.EduEditor
import java.io.IOException


object YamlFormatSynchronizer {

  private val LOAD_FROM_CONFIG = Key<Boolean>("Edu.loadFromConfig")
  private val LOG = Logger.getInstance(YamlFormatSynchronizer.javaClass)

  @VisibleForTesting
  val MAPPER: ObjectMapper by lazy {
    val yamlFactory = YAMLFactory()
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
    yamlFactory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    yamlFactory.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)

    val mapper = ObjectMapper(yamlFactory)
    mapper.registerKotlinModule()
    mapper.propertyNamingStrategy = PropertyNamingStrategy.SNAKE_CASE
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    addMixIns(mapper)

    mapper
  }

  private fun addMixIns(mapper: ObjectMapper) {
    mapper.addMixIn(Course::class.java, CourseYamlMixin::class.java)
    mapper.addMixIn(Section::class.java, SectionYamlMixin::class.java)
    mapper.addMixIn(Lesson::class.java, LessonYamlMixin::class.java)
    mapper.addMixIn(Task::class.java, TaskYamlMixin::class.java)
    mapper.addMixIn(EduTask::class.java, EduTaskYamlMixin::class.java)
    mapper.addMixIn(TaskFile::class.java, TaskFileYamlMixin::class.java)
    mapper.addMixIn(AnswerPlaceholder::class.java, AnswerPlaceholderYamlMixin::class.java)
    mapper.addMixIn(AnswerPlaceholderDependency::class.java, AnswerPlaceholderDependencyYamlMixin::class.java)
  }

  @JvmStatic
  fun saveItem(item: StudyItem) {
    if (YamlFormatSettings.isDisabled()) {
      return
    }
    val course = item.course
    if (course.isStudy) {
      return
    }
    val project = course.project
    if (project == null) {
      LOG.info("Failed to find project for course")
      return
    }
    val fileName = when (item) {
      is Course -> COURSE_CONFIG
      is Section -> SECTION_CONFIG
      is Lesson -> LESSON_CONFIG
      is Task -> TASK_CONFIG
      else -> error("Unknown StudyItem type: ${item.javaClass.name}")
    }
    val dir = item.getDir(project)
    if (dir == null) {
      LOG.error("Failed to save ${item.javaClass.name} '${item.name}' to config file: directory not found")
      return
    }

    val undoManager = UndoManager.getInstance(project)
    if (undoManager.isUndoInProgress || undoManager.isRedoInProgress) {
      ApplicationManager.getApplication().invokeLater {
        saveConfigDocument(dir, fileName, item)
      }
    }
    else {
      saveConfigDocument(dir, fileName, item)
    }
  }

  @JvmStatic
  fun saveAll(project: Project) {
    val course = StudyTaskManager.getInstance(project).course
    if (course == null) {
      LOG.error("Attempt to create config files for project without course")
      return
    }
    saveItem(course)
    course.visitSections(SectionVisitor { section -> saveItem(section) })
    course.visitLessons(LessonVisitor { lesson ->
      for (task in lesson.getTaskList()) {
        saveItem(task)
      }
      saveItem(lesson)
      true
    })
  }

  @JvmStatic
  fun isConfigFile(file: VirtualFile): Boolean {
    val name = file.name
    return COURSE_CONFIG == name || LESSON_CONFIG == name || TASK_CONFIG == name || SECTION_CONFIG == name
  }

  @VisibleForTesting
  fun deserializeTask(taskYaml: String): Task {
    val treeNode = MAPPER.readTree(taskYaml)
    val type = treeNode.get("type")?.asText()
    val typeNotSpecifiedMessage = "task type not specified"
    if (type == null) {
      throw InvalidYamlFormatException(typeNotSpecifiedMessage)
    }
    val clazz = when (type) {
      "edu" -> EduTask::class.java
      "output" -> OutputTask::class.java
      "theory" -> TheoryTask::class.java
      "null" -> throw InvalidYamlFormatException(typeNotSpecifiedMessage)
      else -> throw InvalidYamlFormatException("Unsupported task type '$type'")
    }
    return MAPPER.treeToValue(treeNode, clazz)
  }

  private fun saveConfigDocument(dir: VirtualFile, configFileName: String, item: StudyItem) {
    runUndoTransparentWriteAction {
      val file = dir.findOrCreateChildData(javaClass, configFileName)
      file.putUserData(LOAD_FROM_CONFIG, false)
      val document = file.getDocument() ?: return@runUndoTransparentWriteAction
      document.setText(MAPPER.writeValueAsString(item))
      file.putUserData(LOAD_FROM_CONFIG, true)
    }
  }

  private fun getAllConfigFiles(project: Project): List<VirtualFile> {
    val configFiles = mutableListOf<VirtualFile?>()
    val course = StudyTaskManager.getInstance(project).course ?: error("Accessing to config files in non-edu project")
    course.visitLessons { lesson ->
      val lessonDir = lesson.getLessonDir(project)
      if (lesson.section != null) {
        configFiles.add(lessonDir?.parent?.findChild(SECTION_CONFIG))
      }
      configFiles.add(lessonDir?.findChild(LESSON_CONFIG))
      lesson.visitTasks { task, _ ->
        configFiles.add(task.getTaskDir(project)?.findChild(TASK_CONFIG))
        true
      }
      true
    }

    course.sections.forEach {
      val sectionDir = course.getDir(project).findChild(it.name)
      configFiles.add(sectionDir?.findChild(SECTION_CONFIG))
    }
    return configFiles.filterNotNull()
  }

  private fun loadAllFromConfigs(project: Project) {
    getAllConfigFiles(project).forEach {
      loadFromConfig(project, it, it.getDocument())
    }
  }

  @JvmStatic
  fun startSynchronization(project: Project) {
    loadAllFromConfigs(project)
    val configFiles = getAllConfigFiles(project)
    for (file in configFiles) {
      addSynchronizationListener(project, file)
    }
  }

  private fun addSynchronizationListener(project: Project, file: VirtualFile) {
    val document = file.getDocument()
    document?.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent?) {
        val loadFromConfig = file.getUserData(LOAD_FROM_CONFIG) ?: true
        if (loadFromConfig) {
          loadFromConfig(project, file, event!!.document)
        }
      }
    }, project)
  }

  fun loadFromConfig(project: Project,
                     configFile: VirtualFile,
                     configDocument: Document?,
                     editor: com.intellij.openapi.editor.Editor? = configFile.getEditor(project)) {
    if (editor != null) {
      if (editor.headerComponent is InvalidFormatPanel) {
        editor.headerComponent = null
      }
    }
    configDocument ?: return
    val name = configFile.name
    try {
      when (name) {
        COURSE_CONFIG -> {
          // to implement
        }
        LESSON_CONFIG -> {
          //to implement
        }
        TASK_CONFIG -> loadTask(project, configFile.parent, configDocument.text)
        else -> throw IllegalStateException("unknown config file: ${configFile.name}")
      }
      FileDocumentManager.getInstance().saveDocumentAsIs(configDocument)
    }
    catch (e: MissingKotlinParameterException) {
      val parameterName = e.parameter.name
      if (parameterName == null) {
        showError(project, configFile, editor)
      }
      else {
        showError(project, configFile, editor,
                  "${StringUtil.join(NameUtil.nameToWordsLowerCase(parameterName), "_")} is empty")
      }
    }
    catch (e: MismatchedInputException) {
      showError(project, configFile, editor)
    }
    catch (e: InvalidYamlFormatException) {
      showError(project, configFile, editor, e.message.capitalize())
    }
    catch (e: IOException) {
      val causeException = e.cause
      if (causeException?.message == null || causeException !is InvalidYamlFormatException) {
        showError(project, configFile, editor)
      }
      else {
        showError(project, configFile, editor, causeException.message.capitalize())
      }
    }
  }

  private fun loadTask(project: Project, taskDir: VirtualFile, taskInfo: String) {
    val task = findTask(taskDir, project)
    if (task == null) {
      LOG.info("Failed to synchronize: ${taskDir.name} not found")
      return
    }
    val course = task.course
    val newTask = deserializeTask(taskInfo)
    task.apply {
      task.feedbackLink = newTask.feedbackLink
      task.taskFiles = newTask.taskFiles
    }
    newTask.init(course, task.lesson, false)
    newTask.lesson = task.lesson
    newTask.apply {
      stepId = task.stepId
      index = task.index
      name = task.name
      descriptionText = task.descriptionText
      descriptionFormat = task.descriptionFormat
    }
    task.lesson.getTaskList()[task.index - 1] = newTask
    for (file in FileEditorManager.getInstance(project).openFiles) {
      if (VfsUtil.isAncestor(taskDir, file, true)) {
        val taskFile = EduUtils.getTaskFile(project, file) ?: continue
        val selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor(file)
        if (selectedEditor is EduEditor) {
          selectedEditor.taskFile = taskFile
          EduEditor.removeListener(selectedEditor.editor.document)
          EduEditor.addDocumentListener(selectedEditor.editor.document, EduDocumentListener(project, taskFile))
          EduUtils.drawAllAnswerPlaceholders(selectedEditor.editor, taskFile)
        }
      }
    }
  }

  private fun findTask(taskDir: VirtualFile, project: Project): Task? {
    val lesson = findLesson(taskDir.parent, project) ?: return null
    val index = Integer.valueOf(taskDir.name.substring(EduNames.TASK.length)) - 1
    return lesson.getTaskList().getOrNull(index)
  }

  private fun findLesson(lessonDir: VirtualFile,
                         project: Project): Lesson? {
    val index = Integer.valueOf(lessonDir.name.substring(EduNames.LESSON.length)) - 1
    return StudyTaskManager.getInstance(project).course!!.lessons.getOrNull(index)
  }

  private fun showError(project: Project,
                        configFile: VirtualFile,
                        editor: com.intellij.openapi.editor.Editor?,
                        cause: String = "invalid config") {
    if (editor != null) {
      editor.headerComponent = InvalidFormatPanel(cause)
    }
    else {
      val notification = InvalidConfigNotification(project, configFile, cause)
      notification.notify(project)
    }
  }

  private fun VirtualFile.getDocument(): Document? = FileDocumentManager.getInstance().getDocument(this)

  private fun VirtualFile.getEditor(project: Project): com.intellij.openapi.editor.Editor? {
    val selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor(this)
    return if (selectedEditor != null && selectedEditor is TextEditor) selectedEditor.editor else null
  }
}