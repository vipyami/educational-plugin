/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.edu.learning.newproject;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.idea.ActionsBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.PlatformProjectOpenProcessor;
import com.intellij.projectImport.ProjectOpenedCallback;
import com.intellij.util.PathUtil;
import com.intellij.util.xmlb.XmlSerializer;
import com.jetbrains.edu.coursecreator.CCUtils;
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSynchronizer;
import com.jetbrains.edu.coursecreator.stepik.StepikChangeRetriever;
import com.jetbrains.edu.learning.EduCourseBuilder;
import com.jetbrains.edu.learning.EduNames;
import com.jetbrains.edu.learning.EduSettings;
import com.jetbrains.edu.learning.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.RemoteCourse;
import com.jetbrains.edu.learning.courseFormat.StepikChangeStatus;
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils;
import com.jetbrains.edu.learning.statistics.EduUsagesCollector;
import com.jetbrains.edu.learning.stepik.*;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;

public abstract class CourseProjectGenerator<S> {

  public static final Key<Boolean> EDU_PROJECT_CREATED = Key.create("edu.projectCreated");
  public static final Key<String> COURSE_MODE_TO_CREATE = Key.create("edu.courseModeToCreate");

  private static final Logger LOG = Logger.getInstance(CourseProjectGenerator.class);

  @NotNull protected final EduCourseBuilder<S> myCourseBuilder;
  @NotNull protected Course myCourse;
  private boolean isEnrolled;

  public CourseProjectGenerator(@NotNull EduCourseBuilder<S> builder, @NotNull final Course course) {
    myCourseBuilder = builder;
    myCourse = course;
  }

  protected boolean beforeProjectGenerated() {
    if (!(myCourse instanceof RemoteCourse)) return true;
    final RemoteCourse remoteCourse = (RemoteCourse) this.myCourse;
    if (remoteCourse.getId() > 0) {
      return ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        final StepicUser user = EduSettings.getInstance().getUser();
        isEnrolled = StepikConnector.isEnrolledToCourse(remoteCourse.getId(), user);
        ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
        StepikConnector.enrollToCourse(remoteCourse.getId(), user);
        StepikConnector.loadCourseStructure(null, remoteCourse);
        if (StepikConnector.loadCourseStructure(null, remoteCourse)) {
          myCourse = remoteCourse;
          return true;
        }
        return false;
      }, "Loading Course", true, null);
    }
    return true;
  }

  protected void afterProjectGenerated(@NotNull Project project, @NotNull S projectSettings) {
    EduUtils.openFirstTask(myCourse, project);
    if (CCUtils.isCourseCreator(project)) {
      YamlFormatSynchronizer.saveAll(project);

      // reset wrong statuses set during project generation in CCVirtualFileListener
      // as it listens new files creation
      StepikUtils.setStatusRecursively(myCourse, StepikChangeStatus.UP_TO_DATE);
    }
  }

  /**
   * Generate new project and create course structure for created project
   *
   * @param location location of new course project
   * @param projectSettings new project settings
   */
  // 'projectSettings' must have S type but due to some reasons:
  //  * We don't know generic parameter of EduPluginConfigurator after it was gotten through extension point mechanism
  //  * Kotlin and Java do type erasure a little bit differently
  // we use Object instead of S and cast to S when it needed
  @SuppressWarnings("unchecked")
  @Nullable
  public final Project doCreateCourseProject(@NotNull String location, @NotNull Object projectSettings) {
    if (!beforeProjectGenerated()) {
      return null;
    }
    Project createdProject = createProject(location, projectSettings);
    if (createdProject == null) return null;
    afterProjectGenerated(createdProject, (S) projectSettings);
    return createdProject;
  }

  /**
   * Create new project in given location.
   * To create course structure: modules, folders, files, etc. use {@link CourseProjectGenerator#createCourseStructure(Project, VirtualFile, Object)}
   *
   * @param locationString location of new project
   * @param projectSettings new project settings
   * @return project of new course or null if new project can't be created
   */
  @Nullable
  protected Project createProject(@NotNull String locationString, @NotNull Object projectSettings) {
    final File location = new File(FileUtil.toSystemDependentName(locationString));
    if (!location.exists() && !location.mkdirs()) {
      String message = ActionsBundle.message("action.NewDirectoryProject.cannot.create.dir", location.getAbsolutePath());
      Messages.showErrorDialog(message, ActionsBundle.message("action.NewDirectoryProject.title"));
      return null;
    }

    final VirtualFile baseDir = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByIoFile(location));
    if (baseDir == null) {
      LOG.error("Couldn't find '" + location + "' in VFS");
      return null;
    }
    VfsUtil.markDirtyAndRefresh(false, true, true, baseDir);

    RecentProjectsManager.getInstance().setLastProjectCreationLocation(PathUtil.toSystemIndependentName(location.getParent()));

    @SuppressWarnings("unchecked") ProjectOpenedCallback callback = (p, module) -> createCourseStructure(p, baseDir, (S)projectSettings);
    EnumSet<PlatformProjectOpenProcessor.Option> options = EnumSet.of(PlatformProjectOpenProcessor.Option.FORCE_NEW_FRAME);
    baseDir.putUserData(COURSE_MODE_TO_CREATE, myCourse.getCourseMode());
    Project project = PlatformProjectOpenProcessor.doOpenProject(baseDir, null, -1, callback, options);
    if (project != null) {
      project.putUserData(EDU_PROJECT_CREATED, true);
    }
    return project;
  }

  /**
   * Create course structure for already created project.
   *
   * @param project course project
   * @param baseDir base directory of project
   * @param settings project settings
   */
  @VisibleForTesting
  public void createCourseStructure(@NotNull Project project, @NotNull VirtualFile baseDir, @NotNull S settings) {
    GeneratorUtils.initializeCourse(project, myCourse);

    if (CCUtils.isCourseCreator(project) && myCourse.getItems().isEmpty()) {
      final Lesson lesson = myCourseBuilder.createInitialLesson(project, myCourse);
      if (lesson != null) {
        myCourse.addLesson(lesson);
      }
    }

    try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        GeneratorUtils.createCourse(myCourse, baseDir, indicator);
        if (CCUtils.isCourseCreator(project)) {
          CCUtils.initializeCCPlaceholders(project,myCourse);
        }
        if (myCourse instanceof RemoteCourse && myCourse.isFromZip()) {
          setStepikChangeStatuses(project);
        }
        createAdditionalFiles(project, baseDir);
        EduUsagesCollector.projectTypeCreated(courseTypeId(myCourse));

        return null; // just to use correct overloading of `runProcessWithProgressSynchronously` method
      }, "Generating Course Structure", false, project);
      loadSolutions(project, myCourse);
    } catch (IOException e) {
      LOG.error("Failed to generate course", e);
    }
  }

  private void setStepikChangeStatuses(@NotNull Project project) throws IOException {
    StepicUser user = EduSettings.getInstance().getUser();
    RemoteCourse courseFromStepik = StepikConnector.getCourseInfo(user, myCourse.getId(), ((RemoteCourse)myCourse).isCompatible());
    if (courseFromStepik != null) {
      StepikConnector.fillItems(courseFromStepik);
      courseFromStepik.init(null, null, false);
      new StepikChangeRetriever(project, courseFromStepik).setStepikChangeStatuses();
    }
    else {
      showConvertCourseToLocalNotification(project);
      LOG.warn("Failed to get stepik course for imported from zip course with id: " + myCourse.getId());
    }
  }

  private void showConvertCourseToLocalNotification(@NotNull Project project) {
    String message = "Course not found on Stepik<br> <a href=\"reset\">Convert course to local</a>";
    Notification notification = new Notification("project.generation", "Course not found", message, NotificationType.ERROR,
                                                 new NotificationListener.Adapter() {
                                                   @Override
                                                   protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
                                                     myCourse = copyAsLocalCourse(myCourse);
                                                     notification.expire();
                                                   }

                                                   @NotNull
                                                   private Course copyAsLocalCourse(Course remoteCourse) {
                                                     Element element = XmlSerializer.serialize(remoteCourse);
                                                     Course copy = XmlSerializer.deserialize(element, Course.class);
                                                     copy.init(null, null, true);
                                                     return copy;
                                                   }
                                                 });
    notification.notify(project);
  }

  protected void loadSolutions(@NotNull Project project, @NotNull Course course) {
    if (course.isStudy() && course instanceof RemoteCourse && EduSettings.isLoggedIn()) {
      PropertiesComponent.getInstance(project).setValue(StepikNames.ARE_SOLUTIONS_UPDATED_PROPERTY, true, false);
      if (isEnrolled) {
        StepikSolutionsLoader stepikSolutionsLoader = StepikSolutionsLoader.getInstance(project);
        stepikSolutionsLoader.loadSolutionsInBackground();
        EduUsagesCollector.progressOnGenerateCourse();
      }
    }
  }

  /**
   * Creates additional files that are not in course object
   *
   * @param project course project
   * @param baseDir base directory of project
   *
   * @throws IOException
   */
  protected void createAdditionalFiles(@NotNull Project project, @NotNull VirtualFile baseDir) throws IOException {}

  @NotNull
  private static String courseTypeId(@NotNull Course course) {
    if (course.isStudy()) {
      return course.isAdaptive() ? EduNames.ADAPTIVE : EduNames.STUDY;
    } else {
      return CCUtils.COURSE_MODE;
    }
  }
}
