package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}
import java.nio.file.Path

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.kernel.Monoid
import cats.syntax.flatMap._
import io.github.vigoo.prox.TypedRedirection._
import fs2._
import _root_.io.github.vigoo.prox.path._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, blocking}
import scala.jdk.CollectionConverters._
import scala.language.higherKinds

object TypedRedirection {

  // Let's define a process type and a process runner abstraction

  trait Process[O, E] {
    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]

    val outputRedirection: OutputRedirection
    val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O]
    val errorRedirection: OutputRedirection
    val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E]
    val inputRedirection: InputRedirection

    def start(blocker: Blocker)(implicit runner: ProcessRunner): Resource[IO, Fiber[IO, ProcessResult[O, E]]] =
      runner.start(this, blocker)
  }

  // Redirection is an extra capability
  trait RedirectableOutput[+P[_] <: Process[_, _]] {
    def connectOutput[R <: OutputRedirection, O](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, O]): P[O]

    def >(sink: Pipe[IO, Byte, Unit]): P[Unit] =
      toSink(sink)

    def toSink(sink: Pipe[IO, Byte, Unit]): P[Unit] =
      connectOutput(OutputStream(sink, (s: Stream[IO, Unit]) => s.compile.drain))

    // Note: these operators can be merged with > with further type classes and implicit prioritization
    def >#[O: Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      toFoldMonoid(pipe)

    def toFoldMonoid[O: Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.foldMonoid))

    def >?[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      toVector(pipe)

    def toVector[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.toVector))

    def drainOutput[O](pipe: Pipe[IO, Byte, O]): P[Unit] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.drain))

    def foldOutput[O, R](pipe: Pipe[IO, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.fold(init)(fn)))

    def >(path: Path): P[Unit] =
      toFile(path)

    def toFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = false))

    def >>(path: Path): P[Unit] =
      appendToFile(path)

    def appendToFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = true))
  }

  trait RedirectableError[+P[_] <: Process[_, _]] {
    def connectError[R <: OutputRedirection, E](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, E]): P[E]

    def !>(sink: Pipe[IO, Byte, Unit]): P[Unit] =
      errorToSink(sink)

    def errorToSink(sink: Pipe[IO, Byte, Unit]): P[Unit] =
      connectError(OutputStream(sink, (s: Stream[IO, Unit]) => s.compile.drain))

    // Note: these operators can be merged with > with further type classes and implicit prioritization
    def !>#[O: Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      errorToFoldMonoid(pipe)

    def errorToFoldMonoid[O: Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      connectError(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.foldMonoid))

    def !>?[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      errorToVector(pipe)

    def errorToVector[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      connectError(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.toVector))

    def drainError[O](pipe: Pipe[IO, Byte, O]): P[Unit] =
      connectError(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.drain))

    def foldError[O, R](pipe: Pipe[IO, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectError(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.fold(init)(fn)))

    def !>(path: Path): P[Unit] =
      errorToFile(path)

    def errorToFile(path: Path): P[Unit] =
      connectError(OutputFile(path, append = false))

    def !>>(path: Path): P[Unit] =
      appendErrorToFile(path)

    def appendErrorToFile(path: Path): P[Unit] =
      connectError(OutputFile(path, append = true))
  }

  trait RedirectableInput[+P] {
    def connectInput(source: InputRedirection): P

    def <(path: Path): P =
      fromFile(path)

    def fromFile(path: Path): P =
      connectInput(InputFile(path))

    def <(stream: Stream[IO, Byte]): P =
      fromStream(stream, flushChunks = false)

    def !<(stream: Stream[IO, Byte]): P =
      fromStream(stream, flushChunks = true)

    def fromStream(stream: Stream[IO, Byte], flushChunks: Boolean): P =
      connectInput(InputStream(stream, flushChunks))
  }

  trait ProcessConfiguration[+P <: Process[_, _]] {
    this: Process[_, _] =>

    protected def selfCopy(command: String,
                           arguments: List[String],
                           workingDirectory: Option[Path],
                           environmentVariables: Map[String, String],
                           removedEnvironmentVariables: Set[String]): P

    def in(workingDirectory: Path): P =
      selfCopy(command, arguments, workingDirectory = Some(workingDirectory), environmentVariables, removedEnvironmentVariables)

    def `with`(nameValuePair: (String, String)): P =
      selfCopy(command, arguments, workingDirectory, environmentVariables = environmentVariables + nameValuePair, removedEnvironmentVariables)

    def without(name: String): P =
      selfCopy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables = removedEnvironmentVariables + name)
  }

  object Process {

    case class ProcessImplIOE[O, E](override val command: String,
                                    override val arguments: List[String],
                                    override val workingDirectory: Option[Path],
                                    override val environmentVariables: Map[String, String],
                                    override val removedEnvironmentVariables: Set[String],
                                    override val outputRedirection: OutputRedirection,
                                    override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                    override val errorRedirection: OutputRedirection,
                                    override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                    override val inputRedirection: InputRedirection)
      extends Process[O, E] with ProcessConfiguration[ProcessImplIOE[O, E]] {

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIOE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplIO[O, E](override val command: String,
                                   override val arguments: List[String],
                                   override val workingDirectory: Option[Path],
                                   override val environmentVariables: Map[String, String],
                                   override val removedEnvironmentVariables: Set[String],
                                   override val outputRedirection: OutputRedirection,
                                   override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                   override val errorRedirection: OutputRedirection,
                                   override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                   override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableError[ProcessImplIOE[O, *]]
        with ProcessConfiguration[ProcessImplIO[O, E]] {

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplIOE[O, RE] =
        ProcessImplIOE[O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplIE[O, E](override val command: String,
                                   override val arguments: List[String],
                                   override val workingDirectory: Option[Path],
                                   override val environmentVariables: Map[String, String],
                                   override val removedEnvironmentVariables: Set[String],
                                   override val outputRedirection: OutputRedirection,
                                   override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                   override val errorRedirection: OutputRedirection,
                                   override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                   override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableOutput[ProcessImplIOE[*, E]]
        with ProcessConfiguration[ProcessImplIE[O, E]] {

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplIOE[RO, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplOE[O, E](override val command: String,
                                   override val arguments: List[String],
                                   override val workingDirectory: Option[Path],
                                   override val environmentVariables: Map[String, String],
                                   override val removedEnvironmentVariables: Set[String],
                                   override val outputRedirection: OutputRedirection,
                                   override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                   override val errorRedirection: OutputRedirection,
                                   override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                   override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableInput[ProcessImplIOE[O, E]]
        with ProcessConfiguration[ProcessImplOE[O, E]] {

      override def connectInput(source: InputRedirection): ProcessImplIOE[O, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplOE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplO[O, E](override val command: String,
                                  override val arguments: List[String],
                                  override val workingDirectory: Option[Path],
                                  override val environmentVariables: Map[String, String],
                                  override val removedEnvironmentVariables: Set[String],
                                  override val outputRedirection: OutputRedirection,
                                  override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                  override val errorRedirection: OutputRedirection,
                                  override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                  override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableError[ProcessImplOE[O, *]]
        with RedirectableInput[ProcessImplIO[O, E]]
        with ProcessConfiguration[ProcessImplO[O, E]] {

      override def connectInput(source: InputRedirection): ProcessImplIO[O, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplOE[O, RE] =
        ProcessImplOE[O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplE[O, E](override val command: String,
                                  override val arguments: List[String],
                                  override val workingDirectory: Option[Path],
                                  override val environmentVariables: Map[String, String],
                                  override val removedEnvironmentVariables: Set[String],
                                  override val outputRedirection: OutputRedirection,
                                  override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                  override val errorRedirection: OutputRedirection,
                                  override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                  override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableInput[ProcessImplIO[O, E]]
        with RedirectableOutput[ProcessImplOE[*, E]]
        with ProcessConfiguration[ProcessImplE[O, E]] {

      override def connectInput(source: InputRedirection): ProcessImplIO[O, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplOE[RO, E] =
        ProcessImplOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplI[O, E](override val command: String,
                                  override val arguments: List[String],
                                  override val workingDirectory: Option[Path],
                                  override val environmentVariables: Map[String, String],
                                  override val removedEnvironmentVariables: Set[String],
                                  override val outputRedirection: OutputRedirection,
                                  override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                  override val errorRedirection: OutputRedirection,
                                  override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                  override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableOutput[ProcessImplIO[*, E]]
        with RedirectableError[ProcessImplIE[O, *]]
        with ProcessConfiguration[ProcessImplI[O, E]] {

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplIO[RO, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplIE[O, RE] =
        ProcessImplIE[O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImpl[O, E](override val command: String,
                                 override val arguments: List[String],
                                 override val workingDirectory: Option[Path],
                                 override val environmentVariables: Map[String, String],
                                 override val removedEnvironmentVariables: Set[String],
                                 override val outputRedirection: OutputRedirection,
                                 override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[O],
                                 override val errorRedirection: OutputRedirection,
                                 override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[IO]) => IO[E],
                                 override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableOutput[ProcessImplO[*, E]]
        with RedirectableError[ProcessImplE[O, *]]
        with RedirectableInput[ProcessImplI[O, E]]
        with ProcessConfiguration[ProcessImpl[O, E]] {

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplO[RO, E] =
        ProcessImplO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplE[O, RE] =
        ProcessImplE[O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override def connectInput(source: InputRedirection): ProcessImplI[O, E] =
        ProcessImplI(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    def apply(command: String, arguments: List[String]): ProcessImpl[Unit, Unit] =
      ProcessImpl[Unit, Unit](
        command,
        arguments,
        workingDirectory = None,
        environmentVariables = Map.empty,
        removedEnvironmentVariables = Set.empty,

        outputRedirection = StdOut,
        runOutputStream = (_, _, _) => IO.unit,
        errorRedirection = StdOut,
        runErrorStream = (_, _, _) => IO.unit,
        inputRedirection = StdIn
      )
  }

  trait ProcessResult[+O, +E] {
    val exitCode: ExitCode
    val output: O
    val error: E
  }

  // And a process runner

  trait ProcessRunner {
    def start[O, E](process: Process[O, E], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O, E]]]

    def start[O](processGroup: ProcessGroup[O], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O, Unit]]]
  }

  // Simple JVM implementation

  case class SimpleProcessResult[+O, +E](override val exitCode: ExitCode,
                                         override val output: O,
                                         override val error: E)
    extends ProcessResult[O, E]

  class JVMRunningProcess[O, E](val nativeProcess: JvmProcess,
                                val runningInput: Fiber[IO, Unit],
                                val runningOutput: Fiber[IO, O],
                                val runningError: Fiber[IO, E]) {
    def isAlive: IO[Boolean] =
      IO.delay(nativeProcess.isAlive)

    def kill(): IO[ProcessResult[O, E]] =
      debugLog(s"kill ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroyForcibly()) >> waitForExit()

    def terminate(): IO[ProcessResult[O, E]] =
      debugLog(s"terminate ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroy()) >> waitForExit()

    def waitForExit(): IO[ProcessResult[O, E]] =
      for {
        _ <- debugLog(s"waitforexit ${nativeProcess.toString}")
        exitCode <- IO.delay(nativeProcess.waitFor())
        _ <- runningInput.join
        output <- runningOutput.join
        error <- runningError.join
      } yield SimpleProcessResult(ExitCode(exitCode), output, error)

    private def debugLog(line: String): IO[Unit] =
      IO.delay(println(line))
  }

  class JVMProcessRunner(implicit contextShift: ContextShift[IO]) extends ProcessRunner {

    import JVMProcessRunner._

    // TODO: make run the default and start just a +fiber convenience stuff?
    override def start[O, E](process: Process[O, E], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O, E]]] = {
      val run = startProcess(process, blocker).bracketCase { runningProcess =>
        runningProcess.waitForExit()
      } {
        case (_, Completed) =>
          IO.unit
        case (_, Error(reason)) =>
          IO.raiseError(reason)
        case (runningProcess, Canceled) =>
          runningProcess.terminate() >> IO.unit
      }.start

      Resource.make(run)(_.cancel)
    }

    private def startProcess[O, E](process: Process[O, E], blocker: Blocker): IO[JVMRunningProcess[O, E]] = {
      val builder = withEnvironmentVariables(process,
        withWorkingDirectory(process,
          new ProcessBuilder((process.command :: process.arguments).asJava)))

      builder.redirectOutput(ouptutRedirectionToNative(process.outputRedirection))
      builder.redirectError(ouptutRedirectionToNative(process.errorRedirection))
      builder.redirectInput(inputRedirectionToNative(process.inputRedirection))

      for {
        nativeProcess <- IO.delay(builder.start())
        nativeOutputStream <- IO.delay(nativeProcess.getInputStream)
        nativeErrorStream <- IO.delay(nativeProcess.getErrorStream)

        inputStream = runInputStream(process, nativeProcess, blocker)
        runningInput <- inputStream.start
        runningOutput <- process.runOutputStream(nativeOutputStream, blocker, contextShift).start
        runningError <- process.runErrorStream(nativeErrorStream, blocker, contextShift).start
      } yield new JVMRunningProcess(nativeProcess, runningInput, runningOutput, runningError)
    }

    def startProcessGroup[O](processGroup: ProcessGroup[O], blocker: Blocker): IO[JVMRunningProcess[O, Unit]] =
      for {
        first <- startProcess(processGroup.firstProcess, blocker)
        firstOutput <- first.runningOutput.join
        // TODO: inner
        last <- startProcess(processGroup.lastProcess.connectInput(InputStream(firstOutput, flushChunks = false)), blocker)
      } yield new JVMRunningProcess(last.nativeProcess, first.runningInput, last.runningOutput, last.runningError) // TODO: replace with a group thing joining/terminating everything

    def start[O](processGroup: ProcessGroup[O], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O, Unit]]] = {
      val run = startProcessGroup(processGroup, blocker).bracketCase { runningProcess =>
        runningProcess.waitForExit()
      } {
        case (_, Completed) =>
          IO.unit
        case (_, Error(reason)) =>
          IO.raiseError(reason)
        case (runningProcess, Canceled) =>
          runningProcess.terminate() >> IO.unit
      }.start

      Resource.make(run)(_.cancel)
    }

    private def runInputStream[O, E](process: Process[O, E], nativeProcess: JvmProcess, blocker: Blocker): IO[Unit] = {
      process.inputRedirection match {
        case StdIn => IO.unit
        case InputFile(_) => IO.unit
        case InputStream(stream, false) =>
          stream
            .observe(
              io.writeOutputStream[IO](
                IO.delay(nativeProcess.getOutputStream),
                closeAfterUse = true,
                blocker = blocker))
            .compile
            .drain
        case InputStream(stream, true) =>
          stream
            .observe(writeAndFlushOutputStream(nativeProcess.getOutputStream, blocker))
            .compile
            .drain
      }
    }
  }

  object JVMProcessRunner {
    def withWorkingDirectory[O, E](process: Process[O, E], builder: ProcessBuilder): ProcessBuilder =
      process.workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    def withEnvironmentVariables[O, E](process: Process[O, E], builder: ProcessBuilder): ProcessBuilder = {
      process.environmentVariables.foreach { case (name, value) =>
        builder.environment().put(name, value)
      }
      process.removedEnvironmentVariables.foreach { name =>
        builder.environment().remove(name)
      }
      builder
    }

    def ouptutRedirectionToNative(outputRedirection: OutputRedirection): ProcessBuilder.Redirect = {
      outputRedirection match {
        case StdOut => ProcessBuilder.Redirect.INHERIT
        case OutputFile(path, false) => ProcessBuilder.Redirect.to(path.toFile)
        case OutputFile(path, true) => ProcessBuilder.Redirect.appendTo(path.toFile)
        case OutputStream(_, _, _) => ProcessBuilder.Redirect.PIPE
      }
    }

    def inputRedirectionToNative(inputRedirection: InputRedirection): ProcessBuilder.Redirect = {
      inputRedirection match {
        case StdIn => ProcessBuilder.Redirect.INHERIT
        case InputFile(path) => ProcessBuilder.Redirect.from(path.toFile)
        case InputStream(_, _) => ProcessBuilder.Redirect.PIPE
      }
    }

    def writeAndFlushOutputStream(stream: java.io.OutputStream,
                                  blocker: Blocker)
                                 (implicit contextShift: ContextShift[IO]): Pipe[IO, Byte, Unit] =
      s => {
        Stream
          .bracket(IO.pure(stream))(os => IO.delay(os.close()))
          .flatMap { os =>
            s.chunks.evalMap { chunk =>
              blocker.blockOn {
                IO.delay {
                  blocking {
                    os.write(chunk.toArray)
                    os.flush()
                  }
                }
              }
            }
          }
      }
  }

  // Output Redirection

  // made streaming first-class.
  // Fixed set of types:
  // - stdout
  // - file
  // - fs2 pipe

  // Extension methods could be defined to convert arbitrary types to these

  // Some dependent typing necessary because we have to run the connected stream somehow, and
  // we want to specify the redirections purely.
  // => inject stream runner function, provide predefined (drain, tovector, etc) and propagate the output type

  sealed trait OutputRedirection

  case object StdOut extends OutputRedirection

  case class OutputFile(path: Path, append: Boolean) extends OutputRedirection

  case class OutputStream[O, +OR](pipe: Pipe[IO, Byte, O],
                                  runner: Stream[IO, O] => IO[OR],
                                  chunkSize: Int = 8192) extends OutputRedirection

  sealed trait InputRedirection

  case object StdIn extends InputRedirection

  case class InputFile(path: Path) extends InputRedirection

  case class InputStream(stream: Stream[IO, Byte], flushChunks: Boolean) extends InputRedirection

  // Dependent typing helper
  trait OutputRedirectionType[R] {
    type Out

    def runner(of: R)(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[IO]): IO[Out]
  }

  object OutputRedirectionType {
    type Aux[R, O] = OutputRedirectionType[R] {
      type Out = O
    }

    implicit val outputRedirectionTypeOfStdOut: Aux[StdOut.type, Unit] = new OutputRedirectionType[StdOut.type] {
      override type Out = Unit

      override def runner(of: StdOut.type)(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[IO]): IO[Unit] = IO.unit
    }

    implicit val outputRedirectionTypeOfFile: Aux[OutputFile, Unit] = new OutputRedirectionType[OutputFile] {
      override type Out = Unit

      override def runner(of: OutputFile)(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[IO]): IO[Unit] = IO.unit
    }

    implicit def outputRedirectionTypeOfStream[O, OR]: Aux[OutputStream[O, OR], OR] = new OutputRedirectionType[OutputStream[O, OR]] {
      override type Out = OR

      override def runner(of: OutputStream[O, OR])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[IO]): IO[OR] = {
        implicit val cs: ContextShift[IO] = contextShift
        of.runner(
          io.readInputStream[IO](
            IO.pure(nativeStream),
            of.chunkSize,
            closeAfterUse = true,
            blocker = blocker)
            .through(of.pipe))
      }
    }
  }

  // Piping processes together

  // TODO: how to bind error streams. compound error output indexed by process ids?

  trait ProcessGroup[O] {
    val firstProcess: Process[Stream[IO, Byte], Unit]
    val innerProcesses: List[Process[_, Unit]]
    val lastProcess: Process[O, Unit] with RedirectableInput[Process[O, Unit]]

    def start(blocker: Blocker)(implicit runner: ProcessRunner): Resource[IO, Fiber[IO, ProcessResult[O, Unit]]] =
      runner.start(this, blocker)
  }

  trait PipingSupport {
    def |[O2, E2, P2 <: Process[O2, E2]](other: Process[O2, E2] with RedirectableInput[P2]): ProcessGroup[O2]
  }

  object ProcessGroup {

    case class ProcessGroupImpl[O](override val firstProcess: Process[Stream[IO, Byte], Unit],
                                   override val innerProcesses: List[Process[_, Unit]],
                                   override val lastProcess: Process[O, Unit] with RedirectableInput[Process[O, Unit]])
      extends ProcessGroup[O] {
//        with PipingSupport {
      // TODO: redirection support

//
//      override def |[O2, E2, P2 <: Process[O2, E2]](other: Process[O2, E2] with RedirectableInput[P2]): ProcessGroup[O2] =
//        copy(
//          innerProcesses = lastProcess :: innerProcesses,
//          lastProcess = other
//        )
    }

  }

  // TODO: support any E
  implicit class ProcessPiping[O1, P1[_] <: Process[_, _]](private val process: Process[O1, Unit] with RedirectableOutput[P1]) extends AnyVal {

    // TODO: do not allow pre-redirected IO
    def |[O2, P2 <: Process[O2, Unit]](other: Process[O2, Unit] with RedirectableInput[P2]): ProcessGroup.ProcessGroupImpl[O2] = {

      val channel = identity[Stream[IO, Byte]] _ // TODO: customizable
//      val p1 = process.drainOutput(channel)
      val p1 = process.connectOutput(OutputStream(channel, (stream: Stream[IO, Byte]) => IO.pure(stream))).asInstanceOf[Process[Stream[IO, Byte], Unit]] // TODO: try to get rid of this

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other
      )
    }
  }

  implicit class ProcessStringContext(private val ctx: StringContext) extends AnyVal {
    def proc(args: Any*): Process.ProcessImpl[Unit, Unit] = {
      val staticParts = ctx.parts.map(Left.apply)
      val injectedParts = args.map(Right.apply)
      val parts = (injectedParts zip staticParts).flatMap { case (a, b) => List(b, a) }
      val words = parts.flatMap {
        case Left(value) => value.trim.split(' ')
        case Right(value) => List(value.toString)
      }.toList
      words match {
        case head :: remaining =>
          Process(head, remaining)
        case Nil =>
          throw new IllegalArgumentException(s"The proc interpolator needs at least a process name")
      }
    }
  }
}


// Trying out things

object Playground extends App {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val runner: ProcessRunner = new JVMProcessRunner
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  println("--1--")
  val input = Stream("This is a test string").through(text.utf8Encode)
  val output = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])
    .andThen(_.evalMap(s => IO(println(s))))
  val process1 = (((Process("cat", List.empty) in home) > output) < input) without "TEMP"

  val program1 = Blocker[IO].use { blocker =>
    for {
      result <- process1.start(blocker).use(_.join)
    } yield result.exitCode
  }

  val result1 = program1.unsafeRunSync()
  println(result1)

  println("--2--")
  val sleep = "sleep 500"
  val process2 = proc"sh -c $sleep"
  val program2 = Blocker[IO].use { blocker =>
    for {
      result <- process2.start(blocker).use { runningProcess =>
        runningProcess.join.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), (), ())))
      }
    } yield result.exitCode
  }
  val result2 = program2.unsafeRunSync()

  println(result2)

  println("--3--")
  val process3 = Process("sh", List("-c", "sleep 500"))
  val program3 = Blocker[IO].use { blocker =>
    for {
      result <- process3.start(blocker).use { runningProcess =>
        runningProcess.join
      }.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), (), ())))
    } yield result.exitCode
  }
  val result3 = program3.unsafeRunSync()

  println(result3)

  println("--4--")

  def withInput[O, E, P <: Process[O, E] with ProcessConfiguration[P]](s: String)(process: Process[O, E] with RedirectableInput[P]): P = {
    val input = Stream("This is a test string").through(text.utf8Encode)
    process < input `with` ("hello" -> "world")
  }

  val process4 = withInput("Test string")(Process("cat", List.empty))

  val program4 = Blocker[IO].use { blocker =>
    for {
      result <- process4.start(blocker).use(_.join)
    } yield result.exitCode
  }

  val result4 = program4.unsafeRunSync()
  println(result4)

  println("--5--")
  val output5 = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])

  val process5 = (Process("echo", List("Hello", "\n", "world")) in home) >? output5

  val program5 = Blocker[IO].use { blocker =>
    for {
      result <- process5.start(blocker).use(_.join)
    } yield result.output
  }
  val result5 = program5.unsafeRunSync()
  println(result5)

  println("--6--")
  val output6 = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])

  val process6 = (Process("perl", List("-e", """print STDERR "Hello\nworld"""")) in home) !>? output6

  val program6 = Blocker[IO].use { blocker =>
    for {
      result <- process6.start(blocker).use(_.join)
    } yield result.error
  }
  val result6 = program6.unsafeRunSync()
  println(result6)

  println("--7--")
  val sink = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])
    .andThen(_.evalMap(s => IO(println(s))))
  val process7 = Process("echo", List("Hello", "\n", "world")).errorToSink(sink) | Process("wc", List("-l")).errorToSink(sink)
  val program7 = Blocker[IO].use { blocker =>
    for {
      result <- process7.start(blocker).use(_.join)
    } yield result.error
  }
  val result7 = program7.unsafeRunSync()
  println(result7)

  println("--8--")
//  val process8 = Process("ls", List("-hal")) | Process("sort", List.empty) | Process("uniq", List("-c"))
}
