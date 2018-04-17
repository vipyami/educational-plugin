package com.jetbrains.edu.coursecreator.stepik;

import com.google.common.collect.Lists;
import com.google.gson.*;
import com.intellij.ide.BrowserUtil;
import com.intellij.lang.Language;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.coursecreator.CCUtils;
import com.jetbrains.edu.learning.*;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.serialization.SerializationUtils;
import com.jetbrains.edu.learning.stepik.*;
import com.jetbrains.edu.learning.stepik.serialization.StepikSubmissionAnswerPlaceholderAdapter;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.jetbrains.edu.learning.EduUtils.showOAuthDialog;

public class CCStepikConnector {
  private static final Logger LOG = Logger.getInstance(CCStepikConnector.class.getName());
  private static final String FAILED_TITLE = "Failed to publish ";
  private static final String JETBRAINS_USER_ID = "17813950";

  private CCStepikConnector() {
  }

  @Nullable
  public static RemoteCourse getCourseInfo(@NotNull String courseId) {
    final String url = StepikNames.COURSES + "/" + courseId;
    final StepicUser user = EduSettings.getInstance().getUser();
    try {
      final StepikWrappers.CoursesContainer coursesContainer = StepikConnector.getCoursesFromStepik(user, url);
      return coursesContainer == null ? null : coursesContainer.courses.get(0);
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    return null;
  }

  public static void postCourseWithProgress(@NotNull final Project project, @NotNull final Course course) {
    ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Modal(project, "Uploading Course", true) {
      @Override
      public void run(@NotNull final ProgressIndicator indicator) {
        postCourse(project, course);
      }
    });
  }

  private static void postCourse(@NotNull final Project project, @NotNull Course course) {
    if (!checkIfAuthorized(project, "post course")) return;

    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    if (indicator != null) {
      indicator.setText("Uploading course to " + StepikNames.STEPIK_URL);
      indicator.setIndeterminate(false);
    }
    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.COURSES);

    final StepicUser currentUser = StepikAuthorizedClient.getCurrentUser();
    if (currentUser != null) {
      final List<StepicUser> courseAuthors = course.getAuthors();
      for (int i = 0; i < courseAuthors.size(); i++) {
        if (courseAuthors.size() > i) {
          final StepicUser courseAuthor = courseAuthors.get(i);
          currentUser.setFirstName(courseAuthor.getFirstName());
          currentUser.setLastName(courseAuthor.getLastName());
        }
      }
      course.setAuthors(Collections.singletonList(currentUser));
    }

    String requestBody = new Gson().toJson(new StepikWrappers.CourseWrapper(course));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) {
        LOG.warn("Http client is null");
        return;
      }
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        final String message = FAILED_TITLE + "course ";
        LOG.error(message + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

        showErrorNotification(project, FAILED_TITLE, detailString);
        return;
      }
      final RemoteCourse courseOnRemote = new Gson().fromJson(responseString, StepikWrappers.CoursesContainer.class).courses.get(0);
      courseOnRemote.setItems(Lists.newArrayList(course.getItems()));
      courseOnRemote.setAuthors(course.getAuthors());
      courseOnRemote.setCourseMode(CCUtils.COURSE_MODE);
      courseOnRemote.setLanguage(course.getLanguageID());

      addJetBrainsUserAsAdmin(client, getAdminsGroupId(responseString));
      int sectionCount = postSections(project, courseOnRemote);

      ApplicationManager.getApplication().invokeAndWait(() -> FileDocumentManager.getInstance().saveAllDocuments());
      final int finalSectionCount = sectionCount;
      ApplicationManager.getApplication().runReadAction(() -> postAdditionalFiles(course, project, courseOnRemote.getId(), finalSectionCount + 1));
      StudyTaskManager.getInstance(project).setCourse(courseOnRemote);
      StudyTaskManager.getInstance(project).latestCourseFromServer = (RemoteCourse)courseOnRemote.copy();
      showNotification(project, "Course published", "",seeOnStepikAction("/course/" + courseOnRemote.getId()));
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
  }

  private static void addJetBrainsUserAsAdmin(@NotNull CloseableHttpClient client, @NotNull String groupId) {
    JsonObject object = new JsonObject();
    JsonObject member = new JsonObject();
    member.addProperty("group", groupId);
    member.addProperty("user", JETBRAINS_USER_ID);
    object.add("member", member);

    HttpPost post = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.MEMBERS);
    post.setEntity(new StringEntity(object.toString(), ContentType.APPLICATION_JSON));
    try {
      final CloseableHttpResponse response = client.execute(post);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        LOG.warn("Failed to add JetBrains as admin " + responseString);
      }
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
  }

  private static String getAdminsGroupId(String responseString) {
    JsonObject coursesObject = new JsonParser().parse(responseString).getAsJsonObject();
    return coursesObject.get("courses").getAsJsonArray().get(0).getAsJsonObject().get("admins_group").getAsString();
  }

  private static int postSections(@NotNull Project project, @NotNull RemoteCourse course) {
    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    course.sortItems();
    final List<StudyItem> items = course.getItems();
    int i = 0;
    for (StudyItem item : items) {
      final Section section = new Section();
      List<Lesson> lessons;
      if (item instanceof Section) {
        ((Section)item).setPosition(i + 1);
        section.setName(item.getName());
        lessons = ((Section)item).getLessons();
      }
      else {
        section.setName(EduNames.LESSON + item.getIndex());
        lessons = Collections.singletonList((Lesson)item);
      }

      section.setPosition(i + 1);
      final int sectionId = postModule(course.getId(), section, project);
      section.setId(sectionId);

      int position = 1;
      for (Lesson lesson : lessons) {
        if (indicator != null) {
          indicator.checkCanceled();
          indicator.setText2("Publishing lesson " + lesson.getIndex());
        }
        final int lessonId = postLesson(project, lesson);
        postUnit(lessonId, position, sectionId, project);
        if (indicator != null) {
          indicator.setFraction((double)lesson.getIndex() / course.getLessons().size());
          indicator.checkCanceled();
        }
        position += 1;
      }
      i += 1;
    }
    return items.size();
  }

  private static boolean checkIfAuthorized(@NotNull Project project, @NotNull String failedActionName) {
    boolean isAuthorized = EduSettings.getInstance().getUser() != null;
    if (!isAuthorized) {
      showStepikNotification(project, NotificationType.ERROR, failedActionName);
      return false;
    }
    return true;
  }

  public static void postAdditionalFiles(Course course, @NotNull final Project project, int id, int position) {
    final Lesson lesson = CCUtils.createAdditionalLesson(course, project, StepikNames.PYCHARM_ADDITIONAL);
    if (lesson != null) {
      final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
      if (indicator != null) {
        indicator.setText2("Publishing additional files");
      }
      final Section section = new Section();
      section.setName(StepikNames.PYCHARM_ADDITIONAL);
      section.setPosition(position);
      final int sectionId = postModule(id, section, project);
      final int lessonId = postLesson(project, lesson);
      lesson.unitId = postUnit(lessonId, position, sectionId, project);
    }
  }

  public static void updateAdditionalFiles(Course course, @NotNull final Project project, int stepikId) {
    final Lesson lesson = CCUtils.createAdditionalLesson(course, project, StepikNames.PYCHARM_ADDITIONAL);
    if (lesson != null) {
      lesson.setId(stepikId);
      final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
      if (indicator != null) {
        indicator.setText2("Publishing additional files");
      }
      updateLesson(project, lesson, false);
    }
  }

  public static int postUnit(int lessonId, int position, int sectionId, Project project) {
    if (!checkIfAuthorized(project, "postUnit")) return lessonId;

    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.UNITS);
    final StepikWrappers.UnitWrapper unitWrapper = new StepikWrappers.UnitWrapper();
    final StepikWrappers.Unit unit = new StepikWrappers.Unit();
    unit.setLesson(lessonId);
    unit.setPosition(position);
    unit.setSection(sectionId);
    unitWrapper.setUnit(unit);

    String requestBody = new Gson().toJson(unitWrapper);
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return lessonId;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        LOG.error(FAILED_TITLE + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

        showErrorNotification(project, FAILED_TITLE, detailString);
      }
      else {
        StepikWrappers.UnitContainer unitContainer = new Gson().fromJson(responseString, StepikWrappers.UnitContainer.class);
        if (!unitContainer.units.isEmpty()) {
          return unitContainer.units.get(0).getId();
        }
      }
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return -1;
  }

  public static void updateUnit(int unitId, int lessonId, int position, int sectionId, Project project) {
    if (!checkIfAuthorized(project, "updateUnit")) return;

    final HttpPut request = new HttpPut(StepikNames.STEPIK_API_URL + StepikNames.UNITS + "/" + unitId);
    final StepikWrappers.UnitWrapper unitWrapper = new StepikWrappers.UnitWrapper();
    final StepikWrappers.Unit unit = new StepikWrappers.Unit();
    unit.setLesson(lessonId);
    unit.setPosition(position);
    unit.setSection(sectionId);
    unitWrapper.setUnit(unit);

    String requestBody = new Gson().toJson(unitWrapper);
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_OK) {
        LOG.error("Failed to update Unit" + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

        showErrorNotification(project, FAILED_TITLE, detailString);
      }
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
  }

  private static int postModule(int courseId, Section section, Project project) {
    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.SECTIONS);
    section.setCourse(courseId);
    final StepikWrappers.SectionWrapper sectionContainer = new StepikWrappers.SectionWrapper();
    sectionContainer.setSection(section);
    String requestBody = new Gson().toJson(sectionContainer);
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return -1;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        LOG.error(FAILED_TITLE + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

        showErrorNotification(project, FAILED_TITLE, detailString);
        return -1;
      }
      final Section postedSection = new Gson().fromJson(responseString, StepikWrappers.SectionContainer.class).getSections().get(0);
      return postedSection.getId();
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return -1;
  }

  public static boolean updateTask(@NotNull final Project project, @NotNull final Task task, boolean showNotification) {
    if (!checkIfAuthorized(project, "update task")) return false;
    final Lesson lesson = task.getLesson();
    final int lessonId = lesson.getId();

    VirtualFile taskDir = task.getTaskDir(project);
    if (taskDir == null) return false;

    final HttpPut request = new HttpPut(StepikNames.STEPIK_API_URL + StepikNames.STEP_SOURCES
                                        + String.valueOf(task.getStepId()));
    final Gson gson = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().
      registerTypeAdapter(AnswerPlaceholder.class, new StepikSubmissionAnswerPlaceholderAdapter()).create();
    final Language language = lesson.getCourse().getLanguageById();
    final EduConfigurator configurator = EduConfiguratorManager.forLanguage(language);
    if (configurator == null) return false;
    List<VirtualFile> testFiles = Arrays.stream(taskDir.getChildren()).filter(configurator::isTestFile)
      .collect(Collectors.toList());
    for (VirtualFile file : testFiles) {
      try {
        task.addTestsTexts(file.getName(), VfsUtilCore.loadText(file));
      }
      catch (IOException e) {
        LOG.warn("Failed to load text " + file.getName());
      }
    }
    final String requestBody = gson.toJson(new StepikWrappers.StepSourceWrapper(project, task, lessonId));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return false;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      EntityUtils.consume(responseEntity);
      final StatusLine line = response.getStatusLine();
      switch (line.getStatusCode()) {
        case HttpStatus.SC_OK:
          return true;
        case HttpStatus.SC_NOT_FOUND:
          // TODO: support case when lesson was removed from Stepik too
          return postTask(project, task, task.getLesson().getId());
        default:
          final String message = "Failed to update task ";
          LOG.error(message + responseString);
          return false;
      }
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return false;
  }

  public static RemoteCourse updateCourseInfo(@NotNull final Project project, @NotNull final RemoteCourse course) {
    if (!checkIfAuthorized(project, "update course")) return course;

    // Course info parameters such as isPublic() and isCompatible can be changed from Stepik site only
    // so we get actual info here
    RemoteCourse courseInfo = getCourseInfo(String.valueOf(course.getId()));
    if (courseInfo != null) {
      course.setPublic(courseInfo.isPublic());
      course.setCompatible(courseInfo.isCompatible());
    } else {
      LOG.warn("Failed to get current course info");
    }

    final HttpPut request = new HttpPut(StepikNames.STEPIK_API_URL + StepikNames.COURSES + "/" + String.valueOf(course.getId()));
    String requestBody = new Gson().toJson(new StepikWrappers.CourseWrapper(course));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) {
        LOG.warn("Http client is null");
        return course;
      }
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_OK) {
        final String message = FAILED_TITLE + "course ";
        LOG.error(message + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

          showErrorNotification(project, FAILED_TITLE, detailString);
      }
      return new Gson().fromJson(responseString, StepikWrappers.CoursesContainer.class).courses.get(0);
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }


    return null;
  }

  public static boolean updateAdditionalMaterials(@NotNull Project project, @NotNull final RemoteCourse course,
                                                   @NotNull final List<Integer> sectionsIds) throws IOException {
    AtomicBoolean additionalMaterialsUpdated = new AtomicBoolean(false);
    for (Integer sectionId : sectionsIds) {
      final Section section = StepikConnector.getSection(sectionId);
      if (StepikNames.PYCHARM_ADDITIONAL.equals(section.getName())) {
        final List<Lesson> lessons = StepikConnector.getLessons(course, sectionId);
        lessons.stream()
                .filter(Lesson::isAdditional)
                .findFirst()
                .ifPresent(lesson -> {
                        updateAdditionalFiles(course, project, lesson.getId());
                        additionalMaterialsUpdated.set(true);
                });
      }
    }
    return additionalMaterialsUpdated.get();
  }

  public static Lesson updateLessonInfo(@NotNull final Project project, @NotNull final Lesson lesson, boolean showNotification) {
    if(!checkIfAuthorized(project, "update lesson")) return null;

    final HttpPut request = new HttpPut(StepikNames.STEPIK_API_URL + StepikNames.LESSONS + String.valueOf(lesson.getId()));

    String requestBody = new Gson().toJson(new StepikWrappers.LessonWrapper(lesson));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return null;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      // TODO: support case when lesson was removed from Stepik
      if (line.getStatusCode() != HttpStatus.SC_OK) {
        final String message = "Failed to update lesson ";
        LOG.error(message + responseString);
        if (showNotification) {
          final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
          final JsonElement detail = details.get("detail");
          final String detailString = detail != null ? detail.getAsString() : responseString;

          showErrorNotification(project, message, detailString);
        }
      }

      final Lesson postedLesson = getLessonFromString(responseString);
      if (postedLesson == null) {
        return null;
      }

      Course course = StudyTaskManager.getInstance(project).getCourse();
      assert course != null;
      final Integer sectionId = lesson.getSection().getId();
      if (!lesson.isAdditional()) {
        updateUnit(lesson.unitId, lesson.getId(), lesson.getIndex(), sectionId, project);
      }
      return new Gson().fromJson(responseString, RemoteCourse.class).
        getLessons(true).get(0);
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return null;
  }

  public static int updateLesson(@NotNull final Project project,
                                 @NotNull final Lesson lesson,
                                 boolean showNotification) {
    Lesson postedLesson = updateLessonInfo(project, lesson, showNotification);

    if (postedLesson != null) {
      updateLessonTasks(project, lesson, postedLesson);
      return postedLesson.getId();
    }

    return -1;
  }

  private static void updateLessonTasks(@NotNull Project project,
                                        @NotNull Lesson localLesson,
                                        @NotNull Lesson remoteLesson) {
    final Set<Integer> localTasksIds = localLesson.getTaskList()
      .stream()
      .map(task -> task.getStepId())
      .filter(id -> id > 0)
      .collect(Collectors.toSet());

    final List<Integer> taskIdsToDelete = remoteLesson.steps.stream()
      .filter(id -> !localTasksIds.contains(id))
      .collect(Collectors.toList());

    // Remove all tasks from Stepik which are not in our lessons now
    for (Integer step : taskIdsToDelete) {
      deleteTask(step, project);
    }

    for (Task task : localLesson.getTaskList()) {
      final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
      if (indicator != null) {
        indicator.checkCanceled();
      }
      if (task.getStepId() > 0) {
        updateTask(project, task, false);
      } else {
        postTask(project, task, localLesson.getId());
      }
    }
  }

  public static void showErrorNotification(@NotNull Project project, String title, String message) {
    final Notification notification =
      new Notification("Push.course", title, message, NotificationType.ERROR);
    notification.notify(project);
  }

  public static void showNotification(@NotNull Project project,
                                      @NotNull String title,
                                      @NotNull String message,
                                      @Nullable AnAction action) {
    final Notification notification =
      new Notification("Push.course", title, message, NotificationType.INFORMATION);
    if (action != null) {
      notification.addAction(action);
    }
    notification.notify(project);
  }

  public static AnAction seeOnStepikAction(@NotNull String url) {
    return new AnAction("See on Stepik") {
      @Override
      public void actionPerformed(AnActionEvent e) {
        BrowserUtil.browse(StepikNames.STEPIK_URL + url);
      }
    };
  }

  private static void showStepikNotification(@NotNull Project project,
                                             @NotNull NotificationType notificationType, @NotNull String failedActionName) {
    String text = "Log in to Stepik to " + failedActionName;
    Notification notification = new Notification("Stepik", "Failed to " + failedActionName, text, notificationType);
    notification.addAction(new AnAction("Log in") {

      @Override
      public void actionPerformed(AnActionEvent e) {
        StepikConnector.doAuthorize(() -> showOAuthDialog());
        notification.expire();
      }
    });

    notification.notify(project);
  }

  public static int postLesson(@NotNull final Project project, @NotNull final Lesson lesson) {
    if (!checkIfAuthorized(project, "postLesson")) return -1;

    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + "/lessons");

    String requestBody = new Gson().toJson(new StepikWrappers.LessonWrapper(lesson));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return -1;
      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      final StatusLine line = response.getStatusLine();
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        final String message = FAILED_TITLE + "lesson ";
        LOG.error(message + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

          showErrorNotification(project, message, detailString);
        return 0;
      }

      final Lesson postedLesson = getLessonFromString(responseString);
      if (postedLesson == null) {
        return -1;
      }
      lesson.setId(postedLesson.getId());
      for (Task task : lesson.getTaskList()) {
        final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) {
          indicator.checkCanceled();
        }
        postTask(project, task, postedLesson.getId());
      }
      return postedLesson.getId();
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return -1;
  }

  @Nullable
  public static Lesson getLessonFromString(@NotNull String responseString) {
    final JsonObject jsonTree = new Gson().fromJson(responseString, JsonObject.class);
    if (jsonTree.has(SerializationUtils.LESSONS)) {
      final JsonArray lessons = jsonTree.get(SerializationUtils.LESSONS).getAsJsonArray();
      if (lessons.size() == 1) {
        return new Gson().fromJson(lessons.get(0), Lesson.class);
      }
    }
    return null;
  }

  public static void deleteTask(@NotNull final Integer task, Project project) {
    final HttpDelete request = new HttpDelete(StepikNames.STEPIK_API_URL + StepikNames.STEP_SOURCES + task);
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
        if (client == null) return;
        final CloseableHttpResponse response = client.execute(request);
        final HttpEntity responseEntity = response.getEntity();
        final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
        EntityUtils.consume(responseEntity);
        final StatusLine line = response.getStatusLine();
        if (line.getStatusCode() != HttpStatus.SC_NO_CONTENT) {
          LOG.error("Failed to delete task " + responseString);
            final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
            final JsonElement detail = details.get("detail");
            final String detailString = detail != null ? detail.getAsString() : responseString;

            showErrorNotification(project, "Failed to delete task ", detailString);
        }
      }
      catch (IOException e) {
        LOG.error(e.getMessage());
      }
    });
  }

  public static boolean postTask(final Project project, @NotNull final Task task, final int lessonId) {
    if (!checkIfAuthorized(project, "postTask")) return false;

    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + "/step-sources");
    final Gson gson = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().
      registerTypeAdapter(AnswerPlaceholder.class, new StepikSubmissionAnswerPlaceholderAdapter()).create();
    final String requestBody = gson.toJson(new StepikWrappers.StepSourceWrapper(project, task, lessonId));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
      if (client == null) return false;
      final CloseableHttpResponse response = client.execute(request);
      final StatusLine line = response.getStatusLine();
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
      EntityUtils.consume(responseEntity);
      if (line.getStatusCode() != HttpStatus.SC_CREATED) {
        final String message = FAILED_TITLE + "task ";
        LOG.error(message + responseString);
        final JsonObject details = new JsonParser().parse(responseString).getAsJsonObject();
        final JsonElement detail = details.get("detail");
        final String detailString = detail != null ? detail.getAsString() : responseString;

        showErrorNotification(project, message, detailString);
        return false;
      }

      final JsonObject postedTask = new Gson().fromJson(responseString, JsonObject.class);
      final JsonObject stepSource = postedTask.getAsJsonArray("step-sources").get(0).getAsJsonObject();
      task.setStepId(stepSource.getAsJsonPrimitive("id").getAsInt());
      return true;
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }

    return false;
  }

}
