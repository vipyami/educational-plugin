package com.jetbrains.edu.coursecreator.actions.stepik;

import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.Experiments;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task.Modal;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.jetbrains.edu.coursecreator.CCUtils;
import com.jetbrains.edu.coursecreator.stepik.StepikCourseUploader;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.ext.CourseExt;
import com.jetbrains.edu.learning.statistics.EduUsagesCollector;
import com.jetbrains.edu.learning.stepik.StepikUpdateDateExt;
import com.jetbrains.edu.learning.stepik.StepikUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;

import static com.jetbrains.edu.coursecreator.stepik.CCStepikConnector.*;

public class CCPushCourse extends DumbAwareAction {
  private static Logger LOG = Logger.getInstance(CCPushCourse.class);

  public CCPushCourse() {
    super("&Upload Course to Stepik", "Upload Course to Stepik", null);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getProject();
    presentation.setEnabledAndVisible(project != null && CCUtils.isCourseCreator(project));
    if (project != null) {
      final Course course = StudyTaskManager.getInstance(project).getCourse();
      if (course instanceof RemoteCourse) {
        presentation.setText("Update Course on Stepik");
      }
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    final IdeView view = e.getData(LangDataKeys.IDE_VIEW);
    final Project project = e.getData(CommonDataKeys.PROJECT);
    if (view == null || project == null) {
      return;
    }
    final Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return;
    }
    if (doPush(project, course)) return;
    EduUsagesCollector.courseUploaded();
  }

  public static boolean doPush(Project project, Course course) {
    if (course instanceof RemoteCourse) {
      askToWrapTopLevelLessons(project, course);

      ProgressManager.getInstance().run(new Modal(project, "Updating Course", true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          indicator.setIndeterminate(false);

          if (Experiments.isFeatureEnabled(StepikCourseUploader.FEATURE_ID)) {
            new StepikCourseUploader(project, (RemoteCourse)course).updateCourse();
          }
          else {
            pushInOldWay(indicator, project, course);
          }
        }
      });
    }
    else {
      if (CourseExt.getHasSections(course) && CourseExt.getHasTopLevelLessons(course)) {
        int result = Messages
          .showYesNoDialog(project, "Since you have sections, we have to wrap top-level lessons into section before upload",
                           "Wrap Lessons Into Sections", "Wrap and Post", "Cancel", null);
        if (result == Messages.YES) {
          wrapUnpushedLessonsIntoSections(project, course);
        }
        else {
          return true;
        }
      }
      postCourseWithProgress(project, course);
    }
    return false;
  }

  private static void pushInOldWay(@NotNull ProgressIndicator indicator, Project project, Course course) {
    if (updateCourseInfo(project, (RemoteCourse)course)) {
      updateCourseContent(indicator, course, project);
      StepikUtils.setStatusRecursively(course, StepikChangeStatus.UP_TO_DATE);
      try {
        updateAdditionalMaterials(project, course.getId());
      }
      catch (IOException e1) {
        LOG.warn(e1);
      }

      StepikUpdateDateExt.setUpdated((RemoteCourse)course);
      showNotification(project, "Course is updated", openOnStepikAction("/course/" + course.getId()));
    }
  }

  private static void askToWrapTopLevelLessons(Project project, Course course) {
    if (CourseExt.getHasSections(course) && CourseExt.getHasTopLevelLessons(course)) {
      boolean hasUnpushedLessons = course.getLessons().stream().anyMatch(lesson -> lesson.getId() == 0);
      if (hasUnpushedLessons) {
        int result = Messages
          .showYesNoDialog(project, "Top-level lessons will be wrapped with sections as it's not allowed to have both top-level lessons and sections",
                           "Wrap Lessons Into Sections", "Wrap and Post", "Cancel", null);
        if (result == Messages.YES) {
          wrapUnpushedLessonsIntoSections(project, course);
        }
      }
    }
  }

  private static void updateCourseContent(@NotNull ProgressIndicator indicator, Course course, Project project) {
    if (!((RemoteCourse)course).getSectionIds().isEmpty() && course.getLessons().isEmpty()) {
      deleteSection(((RemoteCourse)course).getSectionIds().get(0));
      ((RemoteCourse)course).setSectionIds(Collections.emptyList());
    }

    int position = 1 + (CourseExt.getHasTopLevelLessons(course) ? 1 : 0);
    for (Section section : course.getSections()) {
      section.setPosition(position++);
      if (section.getId() > 0) {
        updateSection(project, section);
      }
      else {
        postSection(project, section, indicator);
        updateAdditionalSection(project);
      }
    }

    for (Lesson lesson : course.getLessons()) {
      Integer sectionId = ((RemoteCourse)course).getSectionIds().get(0);
      if (lesson.getId() > 0) {
        updateLesson(project, lesson, false, sectionId);
      }
      else {
        int lessonId = postLesson(project, lesson, lesson.getIndex(), sectionId);
        lesson.unitId = postUnit(lessonId, lesson.getIndex(), sectionId, project);
      }
    }
  }
}