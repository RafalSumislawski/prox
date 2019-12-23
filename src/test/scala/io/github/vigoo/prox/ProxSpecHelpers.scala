package io.github.vigoo.prox

import java.io.File

import cats.instances.string._
import cats.effect.Blocker
import zio._
import zio.console._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._

trait ProxSpecHelpers {

  def proxTest[Nothing, Throwable, L](label: L)(assertion: Blocker => Task[TestResult]): ZSpec[Any, scala.Throwable, L, Unit] = {
    testM(label)(Blocker[Task].use { blocker => assertion(blocker) })
  }

  def withTempFile[A](inner: File => Task[A]): Task[A] =
    Task(File.createTempFile("test", "txt")).bracket(
      file => URIO(file.delete()),
      inner)

}
