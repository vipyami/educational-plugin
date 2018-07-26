package com.jetbrains.edu.coursecreator.actions.stepik;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import org.jetbrains.annotations.NotNull;

public class CCShowChangedFiles extends DumbAwareAction {

  public CCShowChangedFiles() {
    super("Compare with Course on Stepik", "Show changed files comparing to the course on Stepik", null);
  }

  @Override
  public void actionPerformed(AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) {
      return;
    }

    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;

    StringBuilder message = buildChangeMessage(course);
    Messages.showInfoMessage(message.toString(), "Course Changes");
  }

  // public for test
  @NotNull
  public static StringBuilder buildChangeMessage(Course course) {
    StringBuilder message = new StringBuilder();
    if (course.getStepikChangeStatus() != StepikChangeStatus.UP_TO_DATE) {
      appendChangeLine("", course, message, course.getStepikChangeStatus().toString());
    }

    for (StudyItem item : course.getItems()) {
      if (item.getStepikChangeStatus() != StepikChangeStatus.UP_TO_DATE) {
        appendChangeLine("", item, message, item.getStepikChangeStatus().toString());
      }

      // we don't mark new items, so for them we have to check it's id
      if (isNew(item)) {
        appendChangeLine("", item, message, "new");
      }

      if (item instanceof Section) {
        for (Lesson lesson : ((Section)item).getLessons()) {
          if (lesson.getStepikChangeStatus() != StepikChangeStatus.UP_TO_DATE) {
            appendChangeLine(item.getName() + "/", lesson, message, lesson.getStepikChangeStatus().toString());
          }

          // all tasks of new lesson are new
          if (isNew(lesson)) {
            appendChangeLine(item.getName() + "/", lesson, message, "new");
            continue;
          }

          for (Task task : lesson.taskList) {
            String parentsLine = item.getName() + "/" + lesson.getName() + "/";
            if (task.getStepikChangeStatus() != StepikChangeStatus.UP_TO_DATE) {
              appendChangeLine(parentsLine, task, message, task.getStepikChangeStatus().toString());
            }
            if (isNew(task)) {
              appendChangeLine(parentsLine, task, message, "new");
            }
          }
        }
      }

      if (item instanceof Lesson) {
        // all tasks of new lesson are new
        if (isNew(item)) {
          continue;
        }

        for (Task task : ((Lesson)item).taskList) {
          if (task.getStepikChangeStatus() != StepikChangeStatus.UP_TO_DATE) {
            appendChangeLine(item.getName() + "/", task, message, task.getStepikChangeStatus().toString());
          }
        }
      }
    }
    return message;
  }

  private static boolean isNew(StudyItem item) {
    return item.getId() == 0;
  }

  private static void appendChangeLine(@NotNull String parentsLine,
                                       @NotNull StudyItem item,
                                       @NotNull StringBuilder stringBuilder,
                                       @NotNull String status) {
    stringBuilder
      .append(parentsLine)
      .append(item.getName())
      .append(" ")
      .append(status)
      .append("\n");
  }

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(false);
    final Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) {
      return;
    }
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course instanceof RemoteCourse && !course.isStudy()) {
      e.getPresentation().setEnabledAndVisible(true);
    }
    else {
      e.getPresentation().setEnabledAndVisible(false);
    }
  }
}
