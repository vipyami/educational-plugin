package com.jetbrains.edu.jbserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jetbrains.edu.learning.EduTestCase
import com.jetbrains.edu.learning.courseFormat.*
import com.jetbrains.edu.learning.courseFormat.tasks.EduTask
import com.jetbrains.edu.learning.courseFormat.tasks.OutputTask
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseFormat.tasks.TheoryTask
import org.junit.Test
import java.util.*
import org.junit.Assert.assertTrue as check


class JacksonDeserializationTest : EduTestCase() {

  val mapper = jacksonObjectMapper().setupMapper()

  /* Test course deserialization */

  @Test
  fun `test empty course`() {
    val json = readTestRes("empty_course.json")
    val course = mapper.readValue<EduCourse>(json)
    check(course.name == "test-title")
    check(course.description == "test-summary")
    check(course.languageID == "rust")
    check(course.humanLanguage == "English")
    check(course.items.size == 0)
    check(course.lastModified == Date(1532010325513))
  }

  @Test
  fun `test empty section`() {
    val json = readTestRes("empty_section.json")
    val section = mapper.readValue<Section>(json)
    check(section.name == "test-section-title")
    check(section.items.size == 0)
    check(section.updateDate == Date(1532010325513))
  }

  @Test
  fun `test empty lesson`() {
    val json = readTestRes("empty_lesson.json")
    val lesson = mapper.readValue<Lesson>(json)
    check(lesson.name == "test-lesson-title")
    check(lesson.taskList.size == 0)
    check(lesson.updateDate == Date(1532010325513))
  }

  @Test
  fun `test course structure`() {
    val json = readTestRes("course_struct.json")
    val course = mapper.readValue<EduCourse>(json)
    check(course.name == "test-course-title")
    check(course.description == "test-course-summary")
    check(course.languageID == "rust")
    check(course.humanLanguage == "English")
    check(course.lastModified == Date(1533121031124))
    check(course.items.size == 2)
    check(course.items[0] is Section)
    check(course.items[1] is Lesson)
    val section = course.items[0] as Section
    val lesson = course.items[1] as Lesson
    check(section.name == "item-1-1")
    check(section.updateDate == Date(1533121031131))
    check(section.items.size == 2)
    check(section.items[0] is Lesson)
    check(section.items[1] is Lesson)
    val sl1 = section.items[0] as Lesson
    val sl2 = section.items[1] as Lesson
    check(sl1.name == "item-1-1-1")
    check(sl1.taskList.size == 0)
    check(sl1.updateDate == Date(1533121031138))
    check(sl2.name == "item-1-1-2")
    check(sl2.taskList.size == 0)
    check(sl2.updateDate == Date(1533121031143))
    check(lesson.name == "item-1-2")
    check(lesson.updateDate == Date(1533121031147))
    check(lesson.taskList.size == 1)
    check(lesson.taskList[0] is OutputTask)
    check(lesson.taskList[0].stepId == 18654)
    check(lesson.taskList[0].name == "item-1-2-1-name")
    check(lesson.taskList[0].descriptionText == "item-1-2-1-decs")
    check(lesson.taskList[0].descriptionFormat == DescriptionFormat.MD)
    check(lesson.taskList[0].taskFiles.isEmpty())
    check(lesson.taskList[0].testsText.isEmpty())
    check(lesson.taskList[0].updateDate == Date(1533566967151))
  }

  /* Test task file deserialization */

  @Test
  fun `test AnswerPlaceholderDependency`() {
    val json = readTestRes("answer_dependency.json")
    val obj = mapper.readValue<AnswerPlaceholderDependency>(json)
    check(obj.sectionName == "section-name")
    check(obj.lessonName == "lesson-name")
    check(obj.taskName == "task-name")
    check(obj.fileName == "file-name")
    check(obj.placeholderIndex == 3)
  }

  @Test
  fun `test AnswerPlaceholder`() {
    val json = readTestRes("placeholder.json")
    val obj = mapper.readValue<AnswerPlaceholder>(json)
    check(obj.offset == 301)
    check(obj.length == 7)
    check(obj.hints.size == 2)
    check(obj.hints[0] == "hint 1")
    check(obj.hints[1] == "hint 2")
    check(obj.possibleAnswer == "answer")
    check(obj.placeholderText == "todo")
    val dep = obj.placeholderDependency
    check(dep?.sectionName == "section-dep")
    check(dep?.lessonName == "lesson-dep")
    check(dep?.taskName == "task-dep")
    check(dep?.fileName == "file-dep")
    check(dep?.placeholderIndex == 1)
  }

  @Test
  fun `test task file`() {
    val json = readTestRes("taskfile.json")
    val file = mapper.readValue<TaskFile>(json)
    check(file.name == "file-name-3301")
    check(file.text == "file-content-08672241")
    check(file.answerPlaceholders.size == 2)
    val ph1 = file.answerPlaceholders[0]
    val ph2 = file.answerPlaceholders[1]
    check(ph1.offset == 42)
    check(ph1.length == 7)
    check(ph1.hints.size == 0)
    check(ph1.possibleAnswer == "answer-23174611")
    check(ph1.placeholderText == "todo-67")
    check(ph1.placeholderDependency?.sectionName == "section-dep-9645696")
    check(ph1.placeholderDependency?.lessonName == "lesson-dep-5654756")
    check(ph1.placeholderDependency?.taskName == "task-dep-1265799")
    check(ph1.placeholderDependency?.fileName == "file-dep-8633256")
    check(ph1.placeholderDependency?.placeholderIndex == 2)
    check(ph2.offset == 163)
    check(ph2.length == 5)
    check(ph2.hints.size == 2)
    check(ph2.hints[0] == "hint-13")
    check(ph2.hints[1] == "hint-72")
    check(ph2.possibleAnswer == "answer-2940345937")
    check(ph2.placeholderText == "todo-46")
    check(ph2.placeholderDependency == null)
  }

  /* Test task deserialization */

  @Test
  fun `test empty task`() {
    val json = readTestRes("empty_task.json")
    val task = mapper.readValue<Task>(json)
    check(task is TheoryTask)
    check(task.id == 1307)
    check(task.name == "test-task-theory")
    check(task.descriptionText == "test-task-theory-decs")
    check(task.descriptionFormat == DescriptionFormat.MD)
    check(task.taskFiles.isEmpty())
    check(task.testsText.isEmpty())
    check(task.updateDate == Date(1533548147513))
  }

  @Test
  fun `test edu task`() {
    val json = readTestRes("task_edu.json")
    val task = mapper.readValue<Task>(json)
    check(task is EduTask)
    check(task.stepId == 96145)
    check(task.name == "task-name-94255")
    check(task.descriptionText == "test-task-edu-decs-15633")
    check(task.descriptionFormat == DescriptionFormat.HTML)
    check(task.updateDate == Date(1533562753513))
    check(task.testsText.size == 3)
    check(task.testsText["test-1"] == "test-content-13658")
    check(task.testsText["test-2"] == "test-content-96584")
    check(task.testsText["test-3"] == "test-content-55897")
    check(task.taskFiles.size == 3)
    check("file-1" in task.taskFiles)
    check("file-2" in task.taskFiles)
    check("file-3" in task.taskFiles)
    val f1 = task.taskFiles["file-1"]
    check(f1?.name == "file-1")
    check(f1?.text == "content-13695")
    check(f1?.answerPlaceholders?.size == 0)
    val f2 = task.taskFiles["file-2"]
    check(f2?.name == "file-2")
    check(f2?.text == "content-84632")
    check(f2?.answerPlaceholders?.size == 2)
    val f3 = task.taskFiles["file-3"]
    check(f3?.name == "file-3")
    check(f3?.text == "content-23665")
    check(f3?.answerPlaceholders?.size == 1)
    val ph21 = f2?.answerPlaceholders?.get(0)
    val ph22 = f2?.answerPlaceholders?.get(1)
    val ph31 = f3?.answerPlaceholders?.get(0)
    check(ph21?.offset == 27)
    check(ph21?.length == 11)
    check(ph21?.hints?.size == 2)
    check(ph21?.hints?.get(0) == "hint-13658")
    check(ph21?.hints?.get(1) == "hint-45782")
    check(ph21?.possibleAnswer == "answer-13659")
    check(ph21?.placeholderText == "todo-12569")
    check(ph21?.placeholderDependency == null)
    check(ph22?.offset == 46)
    check(ph22?.length == 11)
    check(ph22?.hints?.size == 0)
    check(ph22?.possibleAnswer == "answer-94365")
    check(ph22?.placeholderText == "todo-24658")
    check(ph22?.placeholderDependency == null)
    check(ph31?.offset == 79)
    check(ph31?.length == 15)
    check(ph31?.hints?.size == 0)
    check(ph31?.possibleAnswer == "answer-57582")
    check(ph31?.placeholderText == "todo-57277")
    check(ph31?.placeholderDependency?.sectionName == "section-dep-94748")
    check(ph31?.placeholderDependency?.lessonName == "lesson-dep-13654")
    check(ph31?.placeholderDependency?.taskName == "task-dep-96354")
    check(ph31?.placeholderDependency?.fileName == "file-dep-16583")
    check(ph31?.placeholderDependency?.placeholderIndex == 2)
  }

}