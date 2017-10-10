package com.jetbrains.edu.coursecreator.projectView;

import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.StudyItem;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.projectView.LessonDirectoryNode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class CCLessonDirectoryNode extends LessonDirectoryNode {
  public CCLessonDirectoryNode(@NotNull Project project,
                               PsiDirectory value,
                               ViewSettings viewSettings,
                               @NotNull Lesson lesson) {
    super(project, value, viewSettings, lesson);
  }

  private Collection<AbstractTreeNode> getTestFiles(@NotNull VirtualFile taskDir) {
    VirtualFile testDir = taskDir.findChild(EduNames.TEST);
    if (testDir == null) {
      return Collections.emptyList();
    }
    Collection<AbstractTreeNode> auxiliaryChildren = new ArrayList<>();
    for (VirtualFile testFile : testDir.getChildren()) {
      PsiFile psiFile = PsiManager.getInstance(myProject).findFile(testFile);
      if (psiFile == null) {
        continue;
      }
      auxiliaryChildren.add(new CCStudentInvisibleFileNode(myProject, psiFile, myViewSettings));
    }
    return auxiliaryChildren;
  }

  @Override
  public PsiDirectoryNode createChildDirectoryNode(StudyItem item, PsiDirectory directory) {
    return new CCTaskDirectoryNode(myProject, directory, myViewSettings, ((Task)item), getTestFiles(directory.getVirtualFile().getParent()));
  }
}
