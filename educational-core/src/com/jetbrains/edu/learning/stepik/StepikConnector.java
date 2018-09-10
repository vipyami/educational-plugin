package com.jetbrains.edu.learning.stepik;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.ide.BrowserUtil;
import com.intellij.lang.Language;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task.Backgroundable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.edu.learning.EduNames;
import com.jetbrains.edu.learning.EduSettings;
import com.jetbrains.edu.learning.EduUtils;
import com.jetbrains.edu.learning.authUtils.CustomAuthorizationServer;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.builtInWebServer.BuiltInServerOptions;
import org.jetbrains.ide.BuiltInServerManager;

import javax.swing.event.HyperlinkEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jetbrains.edu.learning.stepik.StepikWrappers.*;

public class StepikConnector {
  private static final Logger LOG = Logger.getInstance(StepikConnector.class.getName());

  //this prefix indicates that course can be opened by educational plugin
  private static final String ADAPTIVE_NOTE =
    "\n\nInitially, the adaptive system may behave somewhat randomly, but the more problems you solve, the smarter it becomes!";
  private static final String NOT_VERIFIED_NOTE = "\n\nNote: We’re sorry, but this course feels a little incomplete. " +
      "If you are the owner of the course please <a href=\"mailto:Tatiana.Vasilyeva@jetbrains.com\">get in touch with us</a>, " +
      "we would like to verify this with you; we think with improvement this can be listed as a featured course in the future.";
  private static final String OPEN_PLACEHOLDER_TAG = "<placeholder>";
  private static final String CLOSE_PLACEHOLDER_TAG = "</placeholder>";
  private static final String PROMOTED_COURSES_LINK = "https://raw.githubusercontent.com/JetBrains/educational-plugin/master/featured_courses.txt";
  private static final String IN_PROGRESS_COURSES_LINK = "https://raw.githubusercontent.com/JetBrains/educational-plugin/master/in_progress_courses.txt";
  public static final int MAX_REQUEST_PARAMS = 100; // restriction of Stepik API for multiple requests
  private static final int THREAD_NUMBER = Runtime.getRuntime().availableProcessors();
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(THREAD_NUMBER);

  private StepikConnector() {
  }

  public static boolean enrollToCourse(final int courseId, @Nullable final StepicUser user) {
    if (user == null) return false;
    HttpPost post = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.ENROLLMENTS);
    try {
      final EnrollmentWrapper enrollment = new EnrollmentWrapper(String.valueOf(courseId));
      post.setEntity(new StringEntity(new GsonBuilder().create().toJson(enrollment)));
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient(user);
      CloseableHttpResponse response = client.execute(post);
      StatusLine line = response.getStatusLine();
      return line.getStatusCode() == HttpStatus.SC_CREATED;
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    return false;
  }

  public static boolean isEnrolledToCourse(final int courseId, @Nullable final StepicUser user) {
    if (user == null) return false;
    HttpGet request = new HttpGet(StepikNames.STEPIK_API_URL + StepikNames.ENROLLMENTS + "/" + courseId);
    try {
      final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient(user);
      CloseableHttpResponse response = client.execute(request);
      StatusLine line = response.getStatusLine();
      return line.getStatusCode() == HttpStatus.SC_OK;
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    return false;
  }

  @NotNull
  public static List<Course> getCourseInfos(@Nullable StepicUser user) {
    LOG.info("Loading courses started...");
    long startTime = System.currentTimeMillis();
    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    final List<Integer> featuredCourses = getFeaturedCoursesIds();

    List<Callable<List<Course>>> tasks = ContainerUtil.newArrayList();
    for (int i = 0; i < THREAD_NUMBER; i++) {
      final int currentThread = i;
      tasks.add(() -> {
        List<Course> courses = ContainerUtil.newArrayList();
        try {
          int pageNumber = currentThread + 1;
          while (addCourseInfos(user, courses, pageNumber, featuredCourses)) {
            if (indicator != null && indicator.isCanceled()) {
              return null;
            }
            pageNumber += THREAD_NUMBER;
          }
          return courses;
        }
        catch (IOException e) {
          return courses;
        }
      });
    }
    List<Course> result = ContainerUtil.newArrayList();
    try {
      for (Future<List<Course>> future : ConcurrencyUtil.invokeAll(tasks, EXECUTOR_SERVICE)) {
        if (!future.isCancelled()) {
          List<Course> courses = future.get();
          if (courses != null) {
            result.addAll(courses);
          }
        }
      }
    }
    catch (Throwable e) {
      LOG.warn("Cannot load course list " + e.getMessage());
    }
    addInProgressCourses(user, result);
    LOG.info("Loading courses finished...Took " + (System.currentTimeMillis() - startTime) + " ms");
    return result;
  }

  private static void addInProgressCourses(@Nullable StepicUser user, List<Course> result) {
    final List<Integer> inProgressCourses = getInProgressCoursesIds();
    for (Integer courseId : inProgressCourses) {
      try {
        final RemoteCourse info = getCourseInfo(user, courseId, false);
        if (info == null) continue;
        CourseCompatibility compatibility = info.getCompatibility();
        if (compatibility == CourseCompatibility.UNSUPPORTED) continue;
        CourseVisibility visibility = new CourseVisibility.InProgressVisibility(inProgressCourses.indexOf(info.getId()));
        info.setVisibility(visibility);
        setCourseAuthors(info);

        result.add(info);
      }
      catch (IOException e) {
        LOG.warn("Cannot load course " + courseId + "  " + e.getMessage());
      }
    }
  }

  public static void updateCourseIfNeeded(@NotNull Project project, @NotNull RemoteCourse course) {
    int id = course.getId();

    if (id == 0) {
      return;
    }

    if (!course.isStudy()) {
      return;
    }

    ProgressManager.getInstance().runProcessWithProgressAsynchronously(new Backgroundable(project, "Updating Course") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        if (!StepikUpdateDateExt.isUpToDate(course)) {
          showUpdateAvailableNotification(project, course);
        }
      }
    }, new EmptyProgressIndicator());
  }

  private static void showUpdateAvailableNotification(@NotNull Project project, @NotNull Course course) {
    final Notification notification =
      new Notification("Update.course", "Course Updates", "Course is ready to <a href=\"update\">update</a>", NotificationType.INFORMATION,
                       new NotificationListener() {
                         @Override
                         public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                           FileEditorManagerEx.getInstanceEx(project).closeAllFiles();

                           ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                             ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                             new StepikCourseUpdater((RemoteCourse)course, project).updateCourse();
                             StepikUpdateDateExt.setUpdated((RemoteCourse)course);
                           }, "Updating Course", true, project);
                         }
                       });
    notification.notify(project);
  }

  public static int getTaskPosition(final int taskId) {
    final String url = StepikNames.STEPS + String.valueOf(taskId);
    try {
      StepContainer container = StepikAuthorizedClient.getFromStepik(url, StepContainer.class);
      if (container == null) {
        container = StepikClient.getFromStepik(url, StepContainer.class);
      }
      List<StepSource> steps = container.steps;
      if (!steps.isEmpty()) {
        return steps.get(0).position;
      }
    }
    catch (IOException e) {
      LOG.warn("Could not retrieve task with id=" + taskId);
    }

    return -1;
  }

  private static CoursesContainer getCourseContainers(@Nullable StepicUser user, @NotNull URI url) throws IOException {
    return getCourseContainers(user, url.toString());
  }

  public static CoursesContainer getCourseContainers(@Nullable StepicUser user, @NotNull String url) throws IOException {
    final CoursesContainer coursesContainer;
    if (user != null) {
      coursesContainer = StepikAuthorizedClient.getFromStepik(url, CoursesContainer.class, user);
    }
    else {
      coursesContainer = StepikClient.getFromStepik(url, CoursesContainer.class);
    }
    if (coursesContainer != null) {
      for (RemoteCourse info : coursesContainer.courses) {
        StepikUtils.setCourseLanguage(info);
      }
    }
    return coursesContainer;
  }

  private static boolean addCourseInfos(@Nullable StepicUser user, List<Course> result, int pageNumber,
                                        @NotNull List<Integer> featuredCourses) throws IOException {
    final URI url;
    try {
      url = new URIBuilder(StepikNames.COURSES).addParameter("is_idea_compatible", "true").
        addParameter("page", String.valueOf(pageNumber)).build();
    }
    catch (URISyntaxException e) {
      LOG.error(e.getMessage());
      return false;
    }
    final CoursesContainer coursesContainer = getCourseContainers(user, url);
    addAvailableCourses(result, coursesContainer, featuredCourses);
    return coursesContainer.meta.containsKey("has_next") && coursesContainer.meta.get("has_next") == Boolean.TRUE;
  }

  @Nullable
  public static RemoteCourse getCourseInfo(@Nullable StepicUser user, int courseId, boolean isIdeaCompatible) {
    final URI url;
    final CoursesContainer coursesContainer;
    try {
      url = new URIBuilder(StepikNames.COURSES + "/" + courseId)
        .addParameter("is_idea_compatible", String.valueOf(isIdeaCompatible))
        .build();
      coursesContainer = getCourseContainers(user, url);
    }
    catch (URISyntaxException | IOException e) {
      LOG.warn(e.getMessage());
      return null;
    }

    if (coursesContainer != null && !coursesContainer.courses.isEmpty()) {
      return coursesContainer.courses.get(0);
    } else {
      return null;
    }
  }

  private static void addAvailableCourses(List<Course> result, CoursesContainer coursesContainer,
                                  @NotNull List<Integer> featuredCourses) throws IOException {
    final List<RemoteCourse> courses = coursesContainer.courses;
    for (RemoteCourse info : courses) {
      if (!info.isAdaptive() && StringUtil.isEmptyOrSpaces(info.getType())) continue;

      CourseCompatibility compatibility = info.getCompatibility();
      if (compatibility == CourseCompatibility.UNSUPPORTED) continue;

      setCourseAuthors(info);

      if (info.isAdaptive()) {
        info.setDescription("This is a Stepik Adaptive course.\n\n" + info.getDescription() + ADAPTIVE_NOTE);
      }
      if (info.isPublic() && !featuredCourses.contains(info.getId())) {
        info.setDescription(info.getDescription() + NOT_VERIFIED_NOTE);
      }
      info.setVisibility(getVisibility(info, featuredCourses));
      result.add(info);
    }
  }

  private static void setCourseAuthors(@NotNull final RemoteCourse info) throws IOException {
    final ArrayList<StepicUser> authors = new ArrayList<>();
    for (Integer instructor : info.getInstructors()) {
      final StepicUser author = StepikClient.getFromStepik(StepikNames.USERS + String.valueOf(instructor),
                                                           AuthorWrapper.class).users.get(0);
      authors.add(author);
    }
    info.setAuthors(authors);
  }

  private static CourseVisibility getVisibility(@NotNull RemoteCourse course, @NotNull List<Integer> featuredCourses) {
    if (!course.isPublic()) {
      return CourseVisibility.PrivateVisibility.INSTANCE;
    }
    if (featuredCourses.contains(course.getId())) {
      return new CourseVisibility.FeaturedVisibility(featuredCourses.indexOf(course.getId()));
    }
    if (featuredCourses.isEmpty()) {
      return CourseVisibility.LocalVisibility.INSTANCE;
    }
    return CourseVisibility.PublicVisibility.INSTANCE;
  }

  public static RemoteCourse getCourseInfoByLink(@NotNull StepicUser user, @NotNull String link) {
    int courseId;
    try {
      courseId = Integer.parseInt(link);
    }
    catch (NumberFormatException e) {
      courseId = getCourseIdFromLink(link);
    }
    if (courseId != -1) {
      return getCourseInfo(user, courseId, false);
    }
    return null;
  }

  public static int getCourseIdFromLink(@NotNull String link) {
    try {
      URL url = new URL(link);
      String[] pathParts = url.getPath().split("/");
      for (int i = 0; i < pathParts.length; i++) {
        String part = pathParts[i];
        if (part.equals("course") && i + 1 < pathParts.length) {
          return Integer.parseInt(pathParts[i + 1]);
        }
      }
    }
    catch (MalformedURLException | NumberFormatException e) {
      LOG.warn(e.getMessage());
    }
    return -1;
  }

  public static boolean loadCourseStructure(@Nullable final Project project, @NotNull final RemoteCourse remoteCourse) {
    final List<StudyItem> items = remoteCourse.getItems();
    if (!items.isEmpty()) return true;
    if (!remoteCourse.isAdaptive()) {
      try {
        fillItems(remoteCourse);
        return true;
      }
      catch (IOException e) {
        LOG.error(e);
        return false;
      }
    }
    else {
      final Lesson lesson = new Lesson();
      lesson.setName(EduNames.ADAPTIVE);
      remoteCourse.addLesson(lesson);
      //TODO: more specific name?
      final Task recommendation = StepikAdaptiveConnector.getNextRecommendation(project, remoteCourse);
      if (recommendation != null) {
        lesson.addTask(recommendation);
      }
      return true;
    }
  }

  public static void fillItems(@NotNull RemoteCourse remoteCourse) throws IOException {
    try {
      String[] sectionIds = remoteCourse.getSectionIds().stream().map(section -> String.valueOf(section)).toArray(String[]::new);
      List<Section> allSections = getSections(sectionIds);

      final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
      if (hasVisibleSections(allSections, remoteCourse.getName())) {
        remoteCourse.setSectionIds(Collections.emptyList());
        int itemIndex = 1;
        for (Section section : allSections) {
          if (progressIndicator != null) {
            progressIndicator.checkCanceled();
            progressIndicator.setText("Loading section " + itemIndex + " from " + allSections.size());
            progressIndicator.setFraction((double)itemIndex / allSections.size());
          }
          final String[] unitIds = section.units.stream().map(unit -> String.valueOf(unit)).toArray(String[]::new);
          if (unitIds.length > 0) {
            final List<Lesson> lessonsFromUnits = getLessonsFromUnits(remoteCourse, unitIds, false);
            final String sectionName = section.getName();
            if (sectionName.equals(StepikNames.PYCHARM_ADDITIONAL)) {
              final Lesson lesson = lessonsFromUnits.get(0);
              lesson.setIndex(itemIndex);
              remoteCourse.addLesson(lesson);
              remoteCourse.setAdditionalMaterialsUpdateDate(lesson.getUpdateDate());
            }
            else if (section.getName().equals(remoteCourse.getName())) {
              remoteCourse.addLessons(lessonsFromUnits);
              remoteCourse.setSectionIds(Collections.singletonList(section.getId()));
            }
            else {
              section.setIndex(itemIndex);
              for (int i = 0; i < lessonsFromUnits.size(); i++) {
                Lesson lesson = lessonsFromUnits.get(i);
                lesson.setIndex(i + 1);
              }
              section.addLessons(lessonsFromUnits);
              remoteCourse.addSection(section);
            }
            itemIndex += 1;
          }
        }
      }
      else {
        final String[] unitIds = allSections.stream().map(section -> section.units).flatMap(unitList -> unitList.stream())
          .map(unit -> String.valueOf(unit)).toArray(String[]::new);
        if (unitIds.length > 0) {
          final List<Lesson> lessons = getLessons(remoteCourse);
          remoteCourse.addLessons(lessons);
          remoteCourse.setSectionIds(allSections.stream().map(s -> s.getId()).collect(Collectors.toList()));
          lessons.stream().filter(lesson -> lesson.isAdditional()).forEach(lesson -> remoteCourse.setAdditionalMaterialsUpdateDate(lesson.getUpdateDate()));
        }
      }
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }
  }

  public static List<Section> getSections(String[] sectionIds) throws URISyntaxException, IOException {
    List<SectionContainer> containers = multipleRequestToStepik(StepikNames.SECTIONS, sectionIds, SectionContainer.class);
    return containers.stream().map(container -> container.sections).flatMap(sections -> sections.stream())
      .collect(Collectors.toList());
  }

  @NotNull
  public static List<Unit> getUnits(String[] unitIds) throws URISyntaxException, IOException {
    List<UnitContainer> unitContainers = multipleRequestToStepik(StepikNames.UNITS, unitIds, UnitContainer.class);
    return unitContainers.stream().flatMap(container -> container.units.stream()).collect(Collectors.toList());
  }

  private static List<Lesson> getLessons(RemoteCourse remoteCourse) throws IOException {
    try {
      String[] unitIds = getUnitsIds(remoteCourse);
      if (unitIds.length > 0) {
        return getLessonsFromUnits(remoteCourse, unitIds, true);
      }
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }

    return Collections.emptyList();
  }

  public static List<Lesson> getLessons(RemoteCourse remoteCourse, int sectionId) throws IOException {
    final SectionContainer sectionContainer = getFromStepik(StepikNames.SECTIONS + "/" + String.valueOf(sectionId),
            SectionContainer.class);
    if (sectionContainer.sections.isEmpty()) {
      return Collections.emptyList();
    }
    Section firstSection = sectionContainer.sections.get(0);
    String[] unitIds = firstSection.units.stream().map(id -> String.valueOf(id)).toArray(String[]::new);

    return new ArrayList<>(getLessonsFromUnits(remoteCourse, unitIds, true));
  }

  private static String[] getUnitsIds(RemoteCourse remoteCourse) throws IOException, URISyntaxException {
    String[] sectionIds = remoteCourse.getSectionIds().stream().map(section -> String.valueOf(section)).toArray(String[]::new);
    List<SectionContainer> containers = multipleRequestToStepik(StepikNames.SECTIONS, sectionIds, SectionContainer.class);
    Stream<Section> allSections = containers.stream().map(container -> container.sections).flatMap(sections -> sections.stream());
    return allSections
            .map(section -> section.units)
            .flatMap(unitList -> unitList.stream())
            .map(unit -> String.valueOf(unit))
            .toArray(String[]::new);
  }

  private static boolean hasVisibleSections(@NotNull final List<Section> sections, String courseName) {
    if (sections.isEmpty()) {
      return false;
    }
    final String firstSectionTitle = sections.get(0).getName();
    if (firstSectionTitle != null && firstSectionTitle.equals(courseName)) {
      if (sections.size() == 1) {
        return false;
      }
      final String secondSectionTitle = sections.get(1).getName();
      if (sections.size() == 2 && (secondSectionTitle.equals(EduNames.ADDITIONAL_MATERIALS) ||
                                   secondSectionTitle.equals(StepikNames.PYCHARM_ADDITIONAL))) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private static List<Lesson> getLessons(String[] unitIds) throws IOException, URISyntaxException {
    List<UnitContainer> unitContainers = multipleRequestToStepik(StepikNames.UNITS, unitIds, UnitContainer.class);
    Stream<Unit> allUnits = unitContainers.stream().flatMap(container -> container.units.stream());
    String[] lessonIds = allUnits.map(unit -> String.valueOf(unit.lesson)).toArray(String[]::new);

    List<LessonContainer> lessonContainers = multipleRequestToStepik(StepikNames.LESSONS, lessonIds, LessonContainer.class);
    List<Lesson> lessons = lessonContainers.stream().flatMap(lessonContainer -> lessonContainer.lessons.stream()).collect(Collectors.toList());
    List<Unit> units = unitContainers.stream().flatMap(container -> container.units.stream()).collect(Collectors.toList());

    for (int i = 0; i < lessons.size(); i++) {
      Lesson lesson = lessons.get(i);
      Unit unit = units.get(i);
      if (!StepikUpdateDateExt.isSignificantlyAfter(lesson.getUpdateDate(), unit.getUpdateDate())) {
        lesson.setUpdateDate(unit.getUpdateDate());
      }
    }

    return sortLessonsByUnits(units, lessons);
  }

  /**
   * Stepik sorts result of multiple requests by id, but in some cases unit-wise and lessonId-wise order differ.
   * So we need to sort lesson by units to keep correct course structure
   */
  @NotNull
  private static List<Lesson> sortLessonsByUnits(List<Unit> units, List<Lesson> lessons) {
    HashMap<Integer, Lesson> idToLesson = new HashMap<>();
    units.sort(Comparator.comparingInt(unit -> unit.section));
    for (Lesson lesson : lessons) {
      idToLesson.put(lesson.getId(), lesson);
    }
    List<Lesson> sorted = new ArrayList<>();
    for (Unit unit : units) {
      int lessonId = unit.lesson;
      sorted.add(idToLesson.get(lessonId));
    }
    return sorted;
  }

  @VisibleForTesting
  public static List<Lesson> getLessonsFromUnits(RemoteCourse remoteCourse, String[] unitIds, boolean updateIndicator) throws IOException {
    final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    final List<Lesson> lessons = new ArrayList<>();
    try {
      List<Lesson> lessonsFromUnits = getLessons(unitIds);

      final int lessonCount = lessonsFromUnits.size();
      for (int lessonIndex = 0; lessonIndex < lessonCount; lessonIndex++) {
        Lesson lesson = lessonsFromUnits.get(lessonIndex);
        lesson.unitId = Integer.parseInt(unitIds[lessonIndex]);
        if (progressIndicator != null && updateIndicator) {
          final int readableIndex = lessonIndex + 1;
          progressIndicator.checkCanceled();
          progressIndicator.setText("Loading lesson " + readableIndex + " from " + lessonCount);
          progressIndicator.setFraction((double)readableIndex / lessonCount);
        }
        String[] stepIds = lesson.steps.stream().map(stepId -> String.valueOf(stepId)).toArray(String[]::new);
        List<StepSource> allStepSources = getStepSources(stepIds);

        if (!allStepSources.isEmpty()) {
          final StepOptions options = allStepSources.get(0).block.options;
          if (options != null && options.lessonType != null) {
            // TODO: find a better way to get framework lessons from stepik
            lesson = new FrameworkLesson(lesson);
          }
        }
        List<Task> tasks = getTasks(remoteCourse, stepIds, allStepSources);
        lesson.taskList.addAll(tasks);
        lessons.add(lesson);
      }
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }

    return lessons;
  }

  public static List<StepSource> getStepSources(String[] stepIds) throws URISyntaxException, IOException {
    List<StepContainer> stepContainers = multipleRequestToStepik(StepikNames.STEPS, stepIds, StepContainer.class);
    return stepContainers.stream().flatMap(stepContainer -> stepContainer.steps.stream()).collect(Collectors.toList());
  }

  @NotNull
  public static List<Task> getTasks(RemoteCourse remoteCourse, String[] stepIds, List<StepSource> allStepSources) {
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < allStepSources.size(); i++) {
      StepSource step = allStepSources.get(i);
      Integer stepId = Integer.valueOf(stepIds[i]);
      StepicUser user = EduSettings.getInstance().getUser();
      StepikTaskBuilder builder = new StepikTaskBuilder(remoteCourse, step, stepId, user == null ? -1 : user.getId());
      if (builder.isSupported(step.block.name)) {
        final Task task = builder.createTask(step.block.name);
        if (task != null) {
          tasks.add(task);
        }
      }
    }
    return tasks;
  }

  public static List<Language> getSupportedLanguages(RemoteCourse remoteCourse) {
    List<Language> languages = new ArrayList<>();
    try {
      Map<String, String> codeTemplates = getFirstCodeTemplates(remoteCourse);
      for (String languageName : codeTemplates.keySet()) {
        String id = StepikLanguages.langOfName(languageName).getId();
        Language language = Language.findLanguageByID(id);
        if (language != null) {
          languages.add(language);
        }
      }
    }
    catch (IOException | URISyntaxException  e) {
      LOG.warn(e.getMessage());
    }

    return languages;
  }

  @NotNull
  private static Map<String, String> getFirstCodeTemplates(@NotNull RemoteCourse remoteCourse) throws IOException, URISyntaxException {
    String[] unitsIds = getUnitsIds(remoteCourse);
    List<Lesson> lessons = getLessons(unitsIds);
    for (Lesson lesson : lessons) {
      String[] stepIds = lesson.steps.stream().map(stepId -> String.valueOf(stepId)).toArray(String[]::new);
      List<StepSource> allStepSources = getStepSources(stepIds);

      for (StepSource stepSource : allStepSources) {
        Step step = stepSource.block;
        if (step != null && step.name.equals("code") && step.options != null) {
          Map<String, String> codeTemplates = step.options.codeTemplates;
          if (codeTemplates != null) {
            return codeTemplates;
          }
        }
      }
    }

    return Collections.emptyMap();
  }

  private static <T> T getFromStepik(String link, final Class<T> container) throws IOException {
    if (EduSettings.isLoggedIn()) {
      final StepicUser user = EduSettings.getInstance().getUser();
      assert user != null;
      return StepikAuthorizedClient.getFromStepik(link, container, user);
    }
    return StepikClient.getFromStepik(link, container);
  }

  /**
   * Parses solution from Stepik.
   *
   * In Stepik solution text placeholder text is wrapped in <placeholder> tags. Here we're trying to find corresponding
   * placeholder for all taskFile placeholders.
   *
   * If we can't find at least one placeholder, we mark all placeholders as invalid. Invalid placeholder isn't showing
   * and task file with such placeholders couldn't be checked.
   *
   * @param taskFile for which we're updating placeholders
   * @param solutionFile from Stepik with text of last submission
   * @return false if there're invalid placeholders
   */
  static boolean setPlaceholdersFromTags(@NotNull TaskFile taskFile, @NotNull SolutionFile solutionFile) {
    int lastIndex = 0;
    StringBuilder builder = new StringBuilder(solutionFile.text);
    List<AnswerPlaceholder> placeholders = taskFile.getAnswerPlaceholders();
    boolean isPlaceholdersValid = true;
    for (AnswerPlaceholder placeholder : placeholders) {
      int start = builder.indexOf(OPEN_PLACEHOLDER_TAG, lastIndex);
      int end = builder.indexOf(CLOSE_PLACEHOLDER_TAG, start);
      if (start == -1 || end == -1) {
        isPlaceholdersValid = false;
        break;
      }
      placeholder.setOffset(start);
      String placeholderText = builder.substring(start + OPEN_PLACEHOLDER_TAG.length(), end);
      placeholder.setLength(placeholderText.length());
      builder.delete(end, end + CLOSE_PLACEHOLDER_TAG.length());
      builder.delete(start, start + OPEN_PLACEHOLDER_TAG.length());
      lastIndex = start + placeholderText.length();
    }

    if (!isPlaceholdersValid) {
      for (AnswerPlaceholder placeholder : placeholders) {
        markInvalid(placeholder);
      }
    }

    return isPlaceholdersValid;
  }

  private static void markInvalid(AnswerPlaceholder placeholder) {
    placeholder.setLength(-1);
    placeholder.setOffset(-1);
  }

  static String removeAllTags(@NotNull String text) {
    String result = text.replaceAll(OPEN_PLACEHOLDER_TAG, "");
    result = result.replaceAll(CLOSE_PLACEHOLDER_TAG, "");
    return result;
  }

  @Nullable
  static Reply getLastSubmission(@NotNull String stepId, boolean isSolved) throws IOException {
    try {
      URI url = new URIBuilder(StepikNames.SUBMISSIONS)
        .addParameter("order", "desc")
        .addParameter("page", "1")
        .addParameter("status", isSolved ? "correct" : "wrong")
        .addParameter("step", stepId).build();
      Submission[] submissions = getFromStepik(url.toString(), SubmissionsWrapper.class).submissions;
      if (submissions.length > 0) {
        return submissions[0].reply;
      }
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }
    return null;
  }

  @NotNull
  static HashMap<String, String> getSolutionForStepikAssignment(@NotNull Task task, boolean isSolved) throws IOException {
    HashMap<String, String> taskFileToText = new HashMap<>();
    try {
      URI url = new URIBuilder(StepikNames.SUBMISSIONS)
              .addParameter("order", "desc")
              .addParameter("page", "1")
              .addParameter("status", isSolved ? "correct" : "wrong")
              .addParameter("step", String.valueOf(task.getStepId())).build();
      Submission[] submissions = getFromStepik(url.toString(), SubmissionsWrapper.class).submissions;
      Language language = task.getLesson().getCourse().getLanguageById();
      String stepikLanguage = StepikLanguages.langOfId(language.getID()).getLangName();
      for (Submission submission : submissions) {
        Reply reply = submission.reply;
        if (stepikLanguage != null && stepikLanguage.equals(reply.language)) {
          Collection<TaskFile> values = task.taskFiles.values();
          assert values.size() == 1;
          for (TaskFile value : values) {
            taskFileToText.put(value.name, reply.code);
          }
        }
      }
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }

    return taskFileToText;
  }

  public static StepSource getStep(int step) throws IOException {
    return getFromStepik(StepikNames.STEPS + String.valueOf(step),
                         StepContainer.class).steps.get(0);
  }

  @Nullable
  static Boolean[] taskStatuses(String[] progresses) {
    try {
      List<ProgressContainer> progressContainers = multipleRequestToStepik(StepikNames.PROGRESS, progresses, ProgressContainer.class);
      Map<String, Boolean> progressMap = progressContainers.stream()
        .flatMap(progressContainer -> progressContainer.progresses.stream())
        .collect(Collectors.toMap(p -> p.id, p -> p.isPassed));
      return Arrays.stream(progresses)
        .map(progressMap::get)
        .toArray(Boolean[]::new);
    }
    catch (URISyntaxException | IOException e) {
      LOG.warn(e.getMessage());
    }

    return null;
  }

  private static <T> List<T> multipleRequestToStepik(String apiUrl, String[] ids, final Class<T> container) throws URISyntaxException, IOException {
    List<T> result = new ArrayList<>();

    int length = ids.length;
    for (int i = 0; i < length ; i += MAX_REQUEST_PARAMS) {
      URIBuilder builder = new URIBuilder(apiUrl);
      List<String> sublist = Arrays.asList(ids).subList(i, Math.min(i + MAX_REQUEST_PARAMS, length));
      for (String id : sublist) {
        builder.addParameter("ids[]", id);
      }
      String link = builder.build().toString();
      result.add(getFromStepik(link, container));
    }

    return result;
  }

  public static void postSolution(@NotNull final Task task, boolean passed, @NotNull final Project project) {
    if (task.getStepId() <= 0) {
      return;
    }

    try {
      final String response = postAttempt(task.getStepId());
      if (response.isEmpty()) return;
      final AttemptWrapper.Attempt attempt =
        new Gson().fromJson(response, AttemptContainer.class).attempts.get(0);
      final Map<String, TaskFile> taskFiles = task.getTaskFiles();
      final ArrayList<SolutionFile> files = new ArrayList<>();
      final VirtualFile taskDir = task.getTaskDir(project);
      if (taskDir == null) {
        LOG.error("Failed to find task directory " + task.getName());
        return;
      }
      for (TaskFile taskFile : taskFiles.values()) {
        final String fileName = taskFile.name;
        final VirtualFile virtualFile = EduUtils.findTaskFileInDir(taskFile, taskDir);
        if (virtualFile != null) {
          ApplicationManager.getApplication().runReadAction(() -> {
            final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document != null) {
              String text = document.getText();
              int insertedTextLength = 0;
              StringBuilder builder = new StringBuilder(text);
              for (AnswerPlaceholder placeholder : taskFile.getAnswerPlaceholders()) {
                builder.insert(placeholder.getOffset() + insertedTextLength, OPEN_PLACEHOLDER_TAG);
                builder.insert(placeholder.getOffset() + insertedTextLength + placeholder.getLength() + OPEN_PLACEHOLDER_TAG.length(),
                               CLOSE_PLACEHOLDER_TAG);
                insertedTextLength += OPEN_PLACEHOLDER_TAG.length() + CLOSE_PLACEHOLDER_TAG.length();
              }
              files.add(new SolutionFile(fileName, builder.toString()));
            }
          });
        }
      }

      postSubmission(passed, attempt, files, task);
    }
    catch (IOException e) {
      LOG.error(e.getMessage());
    }
  }

  static String postAttempt(int id) throws IOException {
    final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
    if (client == null || !EduSettings.isLoggedIn()) return "";
    final HttpPost attemptRequest = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.ATTEMPTS);
    String attemptRequestBody = new Gson().toJson(new AttemptWrapper(id));
    attemptRequest.setEntity(new StringEntity(attemptRequestBody, ContentType.APPLICATION_JSON));

    final CloseableHttpResponse attemptResponse = client.execute(attemptRequest);
    final HttpEntity responseEntity = attemptResponse.getEntity();
    final String attemptResponseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
    final StatusLine statusLine = attemptResponse.getStatusLine();
    EntityUtils.consume(responseEntity);
    if (statusLine.getStatusCode() != HttpStatus.SC_CREATED) {
      LOG.warn("Failed to make attempt " + attemptResponseString);
      return "";
    }
    return attemptResponseString;
  }

  private static void postSubmission(boolean passed, AttemptWrapper.Attempt attempt,
                                     ArrayList<SolutionFile> files, Task task) throws IOException {
    final HttpPost request = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.SUBMISSIONS);
    String requestBody = new Gson().toJson(new SubmissionWrapper(attempt.id, passed ? "1" : "0", files, task));
    request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
    final CloseableHttpClient client = StepikAuthorizedClient.getHttpClient();
    if (client == null) return;
    final CloseableHttpResponse response = client.execute(request);
    final HttpEntity responseEntity = response.getEntity();
    final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
    final StatusLine line = response.getStatusLine();
    EntityUtils.consume(responseEntity);
    if (line.getStatusCode() != HttpStatus.SC_CREATED) {
      LOG.error("Failed to make submission " + responseString);
    }
  }

  @NotNull
  private static String createOAuthLink(String authRedirectUrl) {
    return "https://stepik.org/oauth2/authorize/" +
           "?client_id=" + StepikNames.CLIENT_ID +
           "&redirect_uri=" + authRedirectUrl +
           "&response_type=code";
  }

  @NotNull
  public static String getOAuthRedirectUrl() {
    if (EduUtils.isAndroidStudio()) {
      final CustomAuthorizationServer startedServer = CustomAuthorizationServer.getServerIfStarted(StepikNames.STEPIK);

      if (startedServer != null) {
        return startedServer.getHandlingUri();
      }

      try {
        return CustomAuthorizationServer.create(
          StepikNames.STEPIK,
          "",
          StepikConnector::codeHandler
        ).getHandlingUri();
      } catch (IOException e) {
        LOG.warn(e.getMessage());
        return StepikNames.EXTERNAL_REDIRECT_URL;
      }
    } else {
      int port = BuiltInServerManager.getInstance().getPort();

      // according to https://confluence.jetbrains.com/display/IDEADEV/Remote+communication
      int defaultPort = BuiltInServerOptions.getInstance().builtInServerPort;
      if (port >= defaultPort && port < (defaultPort + 20)) {
        return "http://localhost:" + port + "/api/" + StepikNames.OAUTH_SERVICE_NAME;
      }
    }

    return StepikNames.EXTERNAL_REDIRECT_URL;
  }

  private static String codeHandler(@NotNull String code, @NotNull String redirectUri) {
    final StepicUser user = StepikAuthorizedClient.login(code, redirectUri);
    if (user != null) {
      EduSettings.getInstance().setUser(user);
      return null;
    }
    return "Couldn't get user info";
  }

  public static void doAuthorize(@NotNull Runnable externalRedirectUrlHandler) {
    String redirectUrl = getOAuthRedirectUrl();
    String link = createOAuthLink(redirectUrl);
    BrowserUtil.browse(link);
    if (!redirectUrl.startsWith("http://localhost")) {
      externalRedirectUrlHandler.run();
    }
  }

  public static Unit getUnit(int unitId) {
    try {
      List<Unit> units =
        getFromStepik(StepikNames.UNITS + "/" + String.valueOf(unitId), UnitContainer.class).units;
      if (!units.isEmpty()) {
        return units.get(0);
      }
    }
    catch (IOException e) {
      LOG.warn("Failed getting unit: " + unitId);
    }
    return new Unit();
  }

  @NotNull
  public static Section getSection(int sectionId) {
    try {
      List<Section> sections =
        getFromStepik(StepikNames.SECTIONS + "/" + String.valueOf(sectionId), SectionContainer.class).getSections();
      if (!sections.isEmpty()) {
        return sections.get(0);
      }
    }
    catch (IOException e) {
      LOG.warn("Failed getting section: " + sectionId);
    }
    return new Section();
  }

  public static Lesson getLesson(int lessonId) {
    try {
      List<Lesson> lessons =
        getFromStepik(StepikNames.LESSONS + "/" + String.valueOf(lessonId), LessonContainer.class).lessons;
      if (!lessons.isEmpty()) {
        return lessons.get(0);
      }
    }
    catch (IOException e) {
      LOG.warn("Failed getting section: " + lessonId);
    }
    return new Lesson();
  }

  public static void postTheory(Task task, final Project project) {
    if (!EduSettings.isLoggedIn()) {
      return;
    }
    final int stepId = task.getStepId();
    int lessonId = task.getLesson().getId();
    ProgressManager.getInstance().runProcessWithProgressAsynchronously(
      new Backgroundable(project, "Posting Theory to Stepik", false) {
        @Override
        public void run(@NotNull ProgressIndicator progressIndicator) {
          try {
            markStepAsViewed(lessonId, stepId);
          }
          catch (URISyntaxException | IOException e) {
            LOG.warn(e.getMessage());
          }
        }
      }, new EmptyProgressIndicator());
  }

  private static void markStepAsViewed(int lessonId, int stepId) throws URISyntaxException, IOException {
    final URI unitsUrl = new URIBuilder(StepikNames.UNITS).addParameter("lesson", String.valueOf(lessonId)).build();
    final UnitContainer unitContainer = getFromStepik(unitsUrl.toString(), UnitContainer.class);
    if (unitContainer.units.size() == 0) {
      LOG.warn("Got unexpected numbers of units: " + unitContainer.units.size());
      return;
    }

    final URIBuilder builder = new URIBuilder(StepikNames.ASSIGNMENTS);
    for (Integer assignmentId : unitContainer.units.get(0).assignments) {
      builder.addParameter("ids[]", String.valueOf(assignmentId));
    }

    final AssignmentsWrapper assignments = getFromStepik(builder.toString(), AssignmentsWrapper.class);
    if (assignments.assignments.size() > 0) {
      for (Assignment assignment : assignments.assignments) {
        if (assignment.step != stepId) {
          continue;
        }
        final HttpPost post = new HttpPost(StepikNames.STEPIK_API_URL + StepikNames.VIEWS);
        final ViewsWrapper viewsWrapper = new ViewsWrapper(assignment.id, stepId);
        post.setEntity(new StringEntity(new Gson().toJson(viewsWrapper)));
        post.addHeader(new BasicHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType()));

        CloseableHttpClient httpClient = StepikAuthorizedClient.getHttpClient();
        if (httpClient != null) {
          final CloseableHttpResponse viewPostResult = httpClient.execute(post);
          if (viewPostResult.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
            LOG.warn("Error while Views post, code: " + viewPostResult.getStatusLine().getStatusCode());
          }
        }
      }
    }
    else {
      LOG.warn("Got assignments of incorrect length: " + assignments.assignments.size());
    }
  }

  @NotNull
  private static List<Integer> getFeaturedCoursesIds() {
    return getCoursesIds(PROMOTED_COURSES_LINK);
  }

  private static List<Integer> getCoursesIds(@NotNull final String link) {
    try {
      final URL url = new URL(link);
      URLConnection conn = url.openConnection();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        return reader.lines().map(s -> Integer.valueOf(s.split("#")[0].trim())).collect(Collectors.toList());
      }
    } catch (IOException e) {
      LOG.warn("Failed to get courses from " + link);
    }
    return Lists.newArrayList();
  }

  @NotNull
  private static List<Integer> getInProgressCoursesIds() {
    return getCoursesIds(IN_PROGRESS_COURSES_LINK);
  }
}
