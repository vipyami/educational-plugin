package com.jetbrains.edu.learning.projectView;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.courseFormat.StudyItem;
import com.jetbrains.edu.learning.courseFormat.StudyStatus;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.navigation.StudyNavigator;
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class TaskDirectoryNode extends StudyDirectoryNode {
  @NotNull protected final Project myProject;
  protected final ViewSettings myViewSettings;
  @NotNull protected final Task myTask;
  private final Collection<AbstractTreeNode> myAuxiliaryChildren;

  public TaskDirectoryNode(@NotNull Project project,
                           PsiDirectory value,
                           ViewSettings viewSettings,
                           @NotNull Task task,
                           @NotNull Collection<AbstractTreeNode> auxiliaryChildren) {
    super(project, value, viewSettings);
    myProject = project;
    myViewSettings = viewSettings;
    myTask = task;
    myAuxiliaryChildren = auxiliaryChildren;
  }

  public TaskDirectoryNode(@NotNull Project project,
                           PsiDirectory value,
                           ViewSettings viewSettings,
                           @NotNull Task task) {
    this(project, value, viewSettings, task, Collections.emptyList());
  }

  @Override
  public int getWeight() {
    return myTask.getIndex();
  }

  @Override
  protected void updateImpl(PresentationData data) {
    StudyStatus status = myTask.getStatus();
    String subtaskInfo = myTask instanceof TaskWithSubtasks ? getSubtaskInfo((TaskWithSubtasks)myTask) : null;
    if (status == StudyStatus.Unchecked) {
      updatePresentation(data, myTask.getName(), JBColor.BLACK, EducationalCoreIcons.Task, subtaskInfo);
      return;
    }
    boolean isSolved = status == StudyStatus.Solved;
    JBColor color = isSolved ? LIGHT_GREEN : JBColor.RED;
    Icon icon = isSolved ? EducationalCoreIcons.TaskCompl : EducationalCoreIcons.TaskProbl;
    updatePresentation(data, myTask.getName(), color, icon, subtaskInfo);
  }

  private String getSubtaskInfo(TaskWithSubtasks task) {
    int index = task.getActiveSubtaskIndex() + 1;
    int subtasksNum = task.getLastSubtaskIndex() + 1;
    return EduNames.SUBTASK + " " + index + "/" + subtasksNum;
  }

  @Override
  public boolean expandOnDoubleClick() {
    return false;
  }

  @Override
  public boolean canNavigate() {
    return true;
  }

  @Override
  public void navigate(boolean requestFocus) {
    StudyNavigator.navigateToTask(myProject, myTask);
  }

  @Nullable
  @Override
  public AbstractTreeNode modifyChildNode(AbstractTreeNode childNode) {
    Object value = childNode.getValue();
    if (value instanceof PsiDirectory && !((PsiDirectory)value).getName().equals(EduNames.SRC)) {
      return createChildDirectoryNode(null, (PsiDirectory)value);
    }
    if (value instanceof PsiElement) {
      PsiFile psiFile = ((PsiElement) value).getContainingFile();
      if (psiFile == null) return null;
      VirtualFile virtualFile = psiFile.getVirtualFile();
      if (virtualFile == null) {
        return null;
      }
      return StudyUtils.getTaskFile(myProject, virtualFile) != null ? childNode : null;
    }
    return null;
  }

  @Override
  public PsiDirectoryNode createChildDirectoryNode(StudyItem item, PsiDirectory value) {
    return new DirectoryNode(myProject, value, myViewSettings);
  }

  @Override
  public Collection<AbstractTreeNode> getChildrenImpl() {
    Collection<AbstractTreeNode> actualChildren = super.getChildrenImpl();
    if (myAuxiliaryChildren.isEmpty()) {
      return actualChildren;
    }
    Collection<AbstractTreeNode> newChildren = new ArrayList<>();
    if (actualChildren != null) {
      newChildren.addAll(actualChildren);
    }
    newChildren.addAll(myAuxiliaryChildren);
    return newChildren;
  }
}
