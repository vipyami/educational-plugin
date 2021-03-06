package com.jetbrains.edu.learning

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.TestActionEvent
import com.jetbrains.edu.coursecreator.CCStudyItemDeleteProvider
import com.jetbrains.edu.learning.courseFormat.StudyItem
import com.jetbrains.edu.learning.projectView.CourseViewPane

abstract class EduActionTestCase : EduTestCase() {

  fun dataContext(files: Array<VirtualFile>): DataContext {
    return MapDataContext().apply {
      put(CommonDataKeys.PROJECT, project)
      put(CommonDataKeys.VIRTUAL_FILE_ARRAY, files)
    }
  }

  fun dataContext(file: VirtualFile): DataContext {
    val psiManager = PsiManager.getInstance(project)
    val psiFile = psiManager.findDirectory(file) ?: psiManager.findFile(file)
    val studyItem = findStudyItem(file)
    return MapDataContext().apply {
      put(CommonDataKeys.PROJECT, project)
      put(CommonDataKeys.VIRTUAL_FILE, file)
      put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
      put(CommonDataKeys.PSI_ELEMENT, psiFile)
      put(PlatformDataKeys.DELETE_ELEMENT_PROVIDER, CCStudyItemDeleteProvider())
      if (studyItem != null) {
        put(CourseViewPane.STUDY_ITEM, studyItem)
      }
    }
  }

  private fun findStudyItem(file: VirtualFile): StudyItem? {
    var studyItem: StudyItem? = null
    val course = StudyTaskManager.getInstance(project).course
    if (course != null) {
      studyItem = EduUtils.getSection(file, course)
      if (studyItem == null) {
        studyItem = EduUtils.getLesson(file, course)
      }
      if (studyItem == null) {
        studyItem = EduUtils.getTask(file, course)
      }
    }
    return studyItem
  }

  fun testAction(context: DataContext, action: AnAction, runAction: Boolean = true): Presentation {
    val e = TestActionEvent(context, action)
    action.beforeActionPerformedUpdate(e)
    if (e.presentation.isEnabledAndVisible && runAction) {
      action.actionPerformed(e)
    }
    return e.presentation
  }
}
